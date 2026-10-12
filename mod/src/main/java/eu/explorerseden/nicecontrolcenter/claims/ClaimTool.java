package eu.explorerseden.nicecontrolcenter.claims;

import eu.explorerseden.nicecontrolcenter.Claims;
import eu.explorerseden.nicecontrolcenter.Claims.Claim;
import eu.explorerseden.nicecontrolcenter.Claims.Role;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the claim tool does. Right-click a block (or point at one up to 100 blocks away):
 * outside claims the first click sets a corner and the second the opposite corner of a new claim; a
 * corner of a claim you may manage is picked up and the next click moves it; sneaking opens the claim
 * menu. Subclaims are started from the menu, then made with two clicks inside the claim.
 */
public final class ClaimTool {
	private static final int REACH = 100;

	enum Kind { CREATE, SUB, RESIZE }

	/** An unfinished click: the first corner of a claim or subclaim, or a picked-up corner. */
	record Selection(Kind kind, String dim, Integer x, Integer z, String claim, int fixedX, int fixedZ) {
	}

	private static final Map<UUID, Selection> selections = new HashMap<>();
	private static final Map<UUID, Integer> lastClick = new HashMap<>();
	/** Messages in the action bar stay this long before the claim mode line comes back. */
	private static final Map<UUID, Integer> quietUntil = new HashMap<>();
	/** Admins who ignore claims (/claim admin bypass). */
	private static final Set<UUID> bypass = new HashSet<>();

	private ClaimTool() {
	}

	public static boolean bypassing(Player player) {
		return bypass.contains(player.getUUID());
	}

	/** Turns bypass on or off; returns the new state. */
	public static boolean toggleBypass(Player player) {
		if (!bypass.remove(player.getUUID())) {
			bypass.add(player.getUUID());
			return true;
		}
		return false;
	}

	/** The player's role in the claim; admins in bypass count as owner. */
	public static Role role(ServerPlayer player, Claim c) {
		if (bypassing(player)) return Role.OWNER;
		return ClaimStore.data().roleOf(c, player.getStringUUID());
	}

	public static boolean can(ServerPlayer player, Claim c, Role needed) {
		Role r = role(player, c);
		return r != null && r.includes(needed);
	}

	public static void clear(ServerPlayer player) {
		if (selections.remove(player.getUUID()) != null) ClaimVisuals.refresh(player);
	}

	public static void forget(UUID id) {
		selections.remove(id);
		lastClick.remove(id);
		quietUntil.remove(id);
		bypass.remove(id);
	}

	/** Starts a subclaim in a claim (from the menu): the next two clicks inside it make it. */
	public static void startSubclaim(ServerPlayer player, Claim parent) {
		if (!ClaimMode.active(player)) ClaimMode.enter(player);
		if (!ClaimMode.active(player)) return;
		selections.put(player.getUUID(), new Selection(Kind.SUB, parent.dim, null, null, parent.id, 0, 0));
		ClaimVisuals.show(player, null);
		tell(player, "Right-click two opposite corners inside " + parent.displayName() + " for the subclaim.", ChatFormatting.YELLOW);
	}

	/** Corner blocks of the selection, for the fake blocks. */
	public static List<int[]> markers(ServerPlayer player) {
		Selection s = selections.get(player.getUUID());
		if (s == null) return List.of();
		if (s.kind == Kind.RESIZE) {
			Claim c = ClaimStore.byId(s.claim);
			if (c == null) return List.of();
			int px = s.fixedX == c.minX ? c.maxX : c.minX;
			int pz = s.fixedZ == c.minZ ? c.maxZ : c.minZ;
			return List.<int[]>of(new int[] { px, pz });
		}
		return s.x == null ? List.of() : List.<int[]>of(new int[] { s.x, s.z });
	}

	public static boolean hasSelection(ServerPlayer player) {
		return selections.containsKey(player.getUUID());
	}

	// ---------------------------------------------------------------- clicks

	public static InteractionResult useBlock(Player p, Level level, InteractionHand hand, BlockHitResult hit) {
		if (!(p instanceof ServerPlayer player) || !ClaimMode.isTool(p.getItemInHand(hand))) return InteractionResult.PASS;
		BlockPos pos = hit.getBlockPos();
		// The client may have tilled the soil already; show it the real block.
		player.connection.send(new ClientboundBlockUpdatePacket(level, pos));
		player.connection.send(new ClientboundBlockUpdatePacket(level, pos.above()));
		if (!checkHolder(player, hand)) return InteractionResult.FAIL;
		if (once(player)) click(player, pos);
		return InteractionResult.SUCCESS;
	}

	public static InteractionResult useItem(Player p, Level level, InteractionHand hand) {
		if (!(p instanceof ServerPlayer player) || !ClaimMode.isTool(p.getItemInHand(hand))) return InteractionResult.PASS;
		if (!checkHolder(player, hand)) return InteractionResult.FAIL;
		if (!once(player)) return InteractionResult.SUCCESS;
		HitResult hit = player.pick(REACH, 1.0f, false);
		if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) click(player, block.getBlockPos());
		else if (player.isShiftKeyDown()) ClaimDialogs.openAt(player, player.blockPosition());
		else bar(player, "Point at a block (up to " + REACH + " blocks away).", ChatFormatting.GRAY);
		return InteractionResult.SUCCESS;
	}

	private static boolean checkHolder(ServerPlayer player, InteractionHand hand) {
		if (!ClaimStore.enabled() || !ClaimMode.active(player)) {
			ClaimMode.removeTools(player);
			return false;
		}
		if (hand != InteractionHand.MAIN_HAND) {
			bar(player, "Hold the claim tool in your main hand.", ChatFormatting.GRAY);
			return false;
		}
		return true;
	}

	/** A click on a block can arrive twice (on the block, then in the air); only the first counts. */
	private static boolean once(ServerPlayer player) {
		int tick = player.level().getServer().getTickCount();
		Integer last = lastClick.put(player.getUUID(), tick);
		return last == null || tick - last > 3;
	}

	private static void click(ServerPlayer player, BlockPos pos) {
		if (player.isShiftKeyDown()) {
			ClaimDialogs.openAt(player, pos);
			return;
		}
		Claims.Data data = ClaimStore.data();
		Claims.Settings settings = ClaimStore.settings();
		String dim = ClaimStore.dim(player.level());
		int x = pos.getX();
		int z = pos.getZ();
		Selection sel = selections.get(player.getUUID());
		if (sel != null && !sel.dim.equals(dim)) {
			selections.remove(player.getUUID());
			sel = null;
		}
		if (sel != null) {
			switch (sel.kind) {
				case CREATE -> {
					if (sel.x == x && sel.z == z) {
						clear(player);
						bar(player, "Selection cancelled.", ChatFormatting.GRAY);
					} else {
						create(player, Claims.Box.of(sel.x, sel.z, x, z), null);
					}
				}
				case SUB -> {
					Claim parent = ClaimStore.byId(sel.claim);
					if (parent == null || !can(player, parent, Role.MANAGE)) {
						clear(player);
						tell(player, "You can't make subclaims there any more.", ChatFormatting.RED);
					} else if (!parent.contains(x, z)) {
						bar(player, "Click inside " + parent.displayName() + " (or /claim to cancel).", ChatFormatting.RED);
					} else if (sel.x == null) {
						selections.put(player.getUUID(), new Selection(Kind.SUB, dim, x, z, parent.id, 0, 0));
						ClaimVisuals.refresh(player);
						bar(player, "First corner of the subclaim set. Now the opposite corner.", ChatFormatting.YELLOW);
					} else if (sel.x == x && sel.z == z) {
						clear(player);
						bar(player, "Subclaim cancelled.", ChatFormatting.GRAY);
					} else {
						create(player, Claims.Box.of(sel.x, sel.z, x, z), parent);
					}
				}
				case RESIZE -> {
					Claim c = ClaimStore.byId(sel.claim);
					if (c == null || !can(player, c, Role.MANAGE)) {
						clear(player);
						tell(player, "You can't change that claim any more.", ChatFormatting.RED);
						return;
					}
					Claims.Box box = Claims.Box.of(sel.fixedX, sel.fixedZ, x, z);
					long before = c.box().area();
					String err = data.resize(c, box, settings, bypassing(player), System.currentTimeMillis());
					if (err != null) {
						tell(player, err, ChatFormatting.RED);
						return;
					}
					selections.remove(player.getUUID());
					ClaimStore.changed();
					ClaimVisuals.show(player, null);
					String size = box.width() + " × " + box.length();
					if (c.isSub()) tell(player, "Subclaim resized to " + size + ".", ChatFormatting.GREEN);
					else tell(player, "Claim resized to " + size + " (" + signed(box.area() - before) + " blocks). "
							+ data.available(c.owner == null ? player.getStringUUID() : c.owner) + " claim blocks left.", ChatFormatting.GREEN);
				}
			}
			return;
		}
		Claim c = ClaimStore.claimAt(dim, x, z);
		if (c != null) {
			Claim cornerOf = isCorner(c, x, z) ? c : c.isSub() && isCorner(ClaimStore.byId(c.parent), x, z) ? ClaimStore.byId(c.parent) : null;
			if (cornerOf != null && can(player, cornerOf, Role.MANAGE)) {
				int fx = x == cornerOf.minX ? cornerOf.maxX : cornerOf.minX;
				int fz = z == cornerOf.minZ ? cornerOf.maxZ : cornerOf.minZ;
				selections.put(player.getUUID(), new Selection(Kind.RESIZE, dim, x, z, cornerOf.id, fx, fz));
				ClaimVisuals.show(player, null);
				bar(player, "Corner picked up. Right-click where it should go.", ChatFormatting.YELLOW);
				return;
			}
			ClaimVisuals.show(player, c);
			Role r = role(player, c);
			String who = c.owner == null ? "the server" : c.ownerName;
			if (r == null) bar(player, c.displayName() + " belongs to " + who + ".", ChatFormatting.GOLD);
			else bar(player, c.displayName() + " — you're " + (r == Role.OWNER ? "the owner" : r.label.toLowerCase(java.util.Locale.ROOT))
					+ ". Right-click a corner to move it, sneak + right-click for the menu.", ChatFormatting.GOLD);
			return;
		}
		if (!settings.dimensionAllowed(dim) && !bypassing(player)) {
			bar(player, "You can't claim land in this dimension.", ChatFormatting.RED);
			return;
		}
		if (settings.onlyPermanentArea && !bypassing(player) && !data.insidePermanent(dim, Claims.Box.of(x, z, x, z))) {
			bar(player, "You can only claim inside the permanent area (land outside it gets reset).", ChatFormatting.RED);
			return;
		}
		selections.put(player.getUUID(), new Selection(Kind.CREATE, dim, x, z, null, 0, 0));
		ClaimVisuals.show(player, null);
		bar(player, "First corner set at " + x + ", " + z + ". Now right-click the opposite corner.", ChatFormatting.YELLOW);
	}

	private static boolean isCorner(Claim c, int x, int z) {
		return c != null && (x == c.minX || x == c.maxX) && (z == c.minZ || z == c.maxZ);
	}

	private static void create(ServerPlayer player, Claims.Box box, Claim parent) {
		Claims.Data data = ClaimStore.data();
		Claims.Data.Result r = data.create(player.getStringUUID(), player.getPlainTextName(), ClaimStore.dim(player.level()), box,
				parent == null ? null : parent.id, ClaimStore.settings(), bypassing(player), System.currentTimeMillis());
		if (!r.ok()) {
			tell(player, r.error, ChatFormatting.RED);
			return;
		}
		selections.remove(player.getUUID());
		ClaimStore.changed();
		ClaimVisuals.show(player, null);
		if (parent != null) {
			tell(player, "Subclaim made (" + box.width() + " × " + box.length() + "). Give members more rights in it from its menu.", ChatFormatting.GREEN);
			ClaimDialogs.main(player, r.claim);
		} else {
			tell(player, "Claimed " + box.width() + " × " + box.length() + " (" + box.area() + " blocks). "
					+ data.available(player.getStringUUID()) + " claim blocks left.", ChatFormatting.GREEN);
			ClaimDialogs.look(player, r.claim, true);
		}
	}

	private static String signed(long n) {
		return n >= 0 ? "+" + n : Long.toString(n);
	}

	/** The claim mode line in the action bar (every second, unless a message is showing). */
	public static void statusBar(ServerPlayer player) {
		int tick = player.level().getServer().getTickCount();
		Integer quiet = quietUntil.get(player.getUUID());
		if (quiet != null && tick < quiet) return;
		Selection sel = selections.get(player.getUUID());
		String text;
		if (sel == null) text = "Claim mode · " + ClaimStore.data().available(player.getStringUUID()) + " claim blocks · /claim mode to leave";
		else text = switch (sel.kind) {
			case CREATE -> "Right-click the opposite corner (the first corner again cancels)";
			case SUB -> sel.x == null ? "Subclaim: right-click its first corner" : "Subclaim: right-click the opposite corner";
			case RESIZE -> "Right-click where the corner should go";
		};
		player.sendOverlayMessage(Component.literal(text).withStyle(ChatFormatting.GOLD));
	}

	/** A short message in the action bar. */
	public static void bar(ServerPlayer player, String text, ChatFormatting color) {
		player.sendOverlayMessage(Component.literal(text).withStyle(color));
		quietUntil.put(player.getUUID(), player.level().getServer().getTickCount() + 60);
	}

	/** A message in chat. */
	public static void tell(ServerPlayer player, String text, ChatFormatting color) {
		player.sendSystemMessage(Component.literal(text).withStyle(color));
	}
}
