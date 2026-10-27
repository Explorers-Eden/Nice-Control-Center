package eu.explorerseden.nicecontrolcenter.claims;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.scores.Objective;

import eu.explorerseden.nicecontrolcenter.Claims;
import eu.explorerseden.nicecontrolcenter.Claims.Claim;
import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.market.MarketStore;
import eu.explorerseden.nicecontrolcenter.tweaks.TweaksControl;

/**
 * Game claims (Claims.AdminOptions, set by admins on server or admin claims): on entering, what the player carries
 * (inventory, armour, off hand, XP, effects) is put aside and a kit handed out; on leaving, kit items vanish, items
 * picked up inside vanish or go to the market inbox, and their own things come back exactly. Dying inside makes no
 * grave; logging out inside sends them home. Also: no homes or spawn points inside, Nice Actions /back can't lead
 * back in, and the Nice Actions home whitelist (Tweaks.homes).
 */
public final class GameClaims {
	/** Custom-data marks on items: which claim they belong to and how they came (kit or pickup). */
	public static final String MARK = "ncc_claim";
	private static final String KIND = "ncc_claim_kind";

	/** What a player had before entering a game claim. */
	static final class Stash {
		String claim;
		/** Slot → the stack as the game writes it. */
		Map<Integer, String> items = new LinkedHashMap<>();
		int xpLevel;
		float xpProgress;
		int xpTotal;
		List<String> effects = new ArrayList<>();
	}

	private static final Map<String, Stash> stashes = new HashMap<>();
	/** The game claim each online player is in right now (null outside). */
	private static final Map<UUID, String> inside = new HashMap<>();
	private static Path file;

	private GameClaims() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(GameClaims::load);
		ServerTickEvents.START_SERVER_TICK.register(GameClaims::triggers);
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 5 == 0) tick(server);
		});
		// Before death drops: game items are dealt with here, so no grave and nothing of theirs is lost.
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayer p) died(p);
			return true;
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> loggedOut(server, handler.player));
		// No ender chests where things are put aside: they'd carry game items out (or own things in).
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (!(player instanceof ServerPlayer p) || !(world.getBlockState(hit.getBlockPos()).getBlock() instanceof net.minecraft.world.level.block.EnderChestBlock)) {
				return net.minecraft.world.InteractionResult.PASS;
			}
			if (!inInventoryClaim(p)) return net.minecraft.world.InteractionResult.PASS;
			p.sendOverlayMessage(Component.literal("Ender chests don't open in this game area").withStyle(ChatFormatting.RED));
			return net.minecraft.world.InteractionResult.FAIL;
		});
	}

	/** A game item (kit or picked up in a game area) lying outside its area: it vanishes (no smuggling over the edge). */
	public static boolean strayGameItem(ItemEntity item) {
		CustomData d = item.getItem().get(DataComponents.CUSTOM_DATA);
		if (d == null) return false;
		CompoundTag t = d.copyTag();
		if (!t.contains(MARK)) return false;
		String claim = t.getStringOr(MARK, "");
		Claim here = ClaimStore.claimAt(item.level(), item.blockPosition());
		// Inside its claim, or a subclaim of it, or the claim around its subclaim: still in the game.
		if (here != null && (here.id.equals(claim) || claim.equals(here.parent))) return false;
		Claim own = ClaimStore.data().claims.get(claim);
		return own == null || here == null || own.parent == null || !own.parent.equals(here.id);
	}

	// ---------------------------------------------------------------- which claim

	/** The game claim at the player's spot, or null. */
	public static Claim gameClaimAt(Level level, BlockPos pos) {
		Claim c = ClaimStore.claimAt(level, pos);
		return c != null && c.admin != null && c.admin.active() ? c : null;
	}

	public static Claim gameClaimAt(String dim, int x, int z) {
		Claim c = ClaimStore.claimAt(dim, x, z);
		return c != null && c.admin != null && c.admin.active() ? c : null;
	}

	/** Inside a game claim that puts inventories aside (for blocking graves). */
	public static boolean inInventoryClaim(ServerPlayer p) {
		Claim c = gameClaimAt(p.level(), p.blockPosition());
		return c != null && c.admin.inventory;
	}

	/** No homes or spawn points here (a game claim that says so). */
	public static boolean blocksHomes(Level level, BlockPos pos) {
		Claim c = gameClaimAt(level, pos);
		return c != null && c.admin.blocksHomes();
	}

	// ---------------------------------------------------------------- entering and leaving

	private static void tick(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!p.isAlive()) continue;
			Claim now = gameClaimAt(p.level(), p.blockPosition());
			String nowId = now == null ? null : now.id;
			String was = inside.get(p.getUUID());
			Stash kept = stashes.get(p.getStringUUID());
			// After a crash or restart while inside the same claim: carry on (their stash is still kept).
			if (was == null && nowId != null && kept != null && nowId.equals(kept.claim)) {
				inside.put(p.getUUID(), nowId);
				continue;
			}
			if (java.util.Objects.equals(was, nowId)) {
				// A stash left from before (a crash, a restart) while they're outside: given back.
				if (nowId == null && stashes.containsKey(p.getStringUUID())) leave(p, null);
				continue;
			}
			if (was != null || kept != null) leave(p, ClaimStore.data().claims.get(was != null ? was : kept.claim));
			if (now != null) enter(p, now);
			if (nowId == null) inside.remove(p.getUUID());
			else inside.put(p.getUUID(), nowId);
		}
	}

	private static void enter(ServerPlayer p, Claim c) {
		Claims.AdminOptions o = c.admin;
		if (o.inventory) {
			Stash s = new Stash();
			s.claim = c.id;
			var inv = p.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				ItemStack st = inv.getItem(i);
				if (!st.isEmpty()) s.items.put(i, encode(p, st));
			}
			s.xpLevel = p.experienceLevel;
			s.xpProgress = p.experienceProgress;
			s.xpTotal = p.totalExperience;
			for (MobEffectInstance e : p.getActiveEffects()) {
				MobEffectInstance.CODEC.encodeStart(p.registryAccess().createSerializationContext(JsonOps.INSTANCE), e).result().ifPresent(j -> s.effects.add(j.toString()));
			}
			stashes.put(p.getStringUUID(), s);
			save();
			inv.clearContent();
			p.setExperienceLevels(0);
			p.setExperiencePoints(0);
			p.removeAllEffects();
		}
		if (o.kit != null) {
			for (String json : o.kit) {
				ItemStack st = decode(p, json);
				if (st.isEmpty()) continue;
				mark(st, c.id, "kit");
				if (!p.getInventory().add(st)) p.level().addFreshEntity(new ItemEntity(p.level(), p.getX(), p.getY(), p.getZ(), st));
			}
		}
		p.inventoryMenu.broadcastChanges();
		p.sendOverlayMessage(Component.literal(c.displayName() + (o.inventory ? ": your things are kept safe until you leave" : "")).withStyle(ChatFormatting.AQUA));
	}

	/** Leaving (c may be gone): game items are handled, then their own things come back. */
	private static void leave(ServerPlayer p, Claim c) {
		Claims.AdminOptions o = c == null ? null : c.admin;
		Stash s = stashes.get(p.getStringUUID());
		boolean swap = s != null;
		boolean remove = o != null && o.removePickups;
		var inv = p.getInventory();
		List<ItemStack> toInbox = new ArrayList<>();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack st = inv.getItem(i);
			if (st.isEmpty()) continue;
			String kind = kind(st);
			// With the swap, everything carried now is from the game; without it only what's marked.
			if (kind == null && !swap) continue;
			inv.setItem(i, ItemStack.EMPTY);
			if ("kit".equals(kind) || remove) continue;
			toInbox.add(unmark(st));
		}
		if (swap) {
			for (Map.Entry<Integer, String> e : s.items.entrySet()) {
				ItemStack st = decode(p, e.getValue());
				if (st.isEmpty()) continue;
				if (inv.getItem(e.getKey()).isEmpty()) inv.setItem(e.getKey(), st);
				else if (!inv.add(st)) toInbox.add(st);
			}
			p.setExperienceLevels(s.xpLevel);
			p.experienceProgress = s.xpProgress;
			p.totalExperience = s.xpTotal;
			p.removeAllEffects();
			for (String json : s.effects) {
				MobEffectInstance.CODEC.parse(p.registryAccess().createSerializationContext(JsonOps.INSTANCE), JsonParser.parseString(json)).result().ifPresent(p::addEffect);
			}
			stashes.remove(p.getStringUUID());
			save();
		}
		inbox(p, toInbox);
		p.inventoryMenu.broadcastChanges();
		if (swap) p.sendOverlayMessage(Component.literal("Your things are back").withStyle(ChatFormatting.GREEN));
	}

	/** Dying inside: kit items vanish, picked-up ones drop (option) or go to the inbox; never a grave. */
	private static void died(ServerPlayer p) {
		String id = inside.get(p.getUUID());
		Claim c = id == null ? null : ClaimStore.data().claims.get(id);
		if (c == null || c.admin == null) return;
		boolean swap = stashes.containsKey(p.getStringUUID());
		var inv = p.getInventory();
		List<ItemStack> toInbox = new ArrayList<>();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack st = inv.getItem(i);
			if (st.isEmpty()) continue;
			String kind = kind(st);
			if (kind == null && !swap) continue;
			inv.setItem(i, ItemStack.EMPTY);
			if ("kit".equals(kind) || c.admin.removePickups && !c.admin.dropPickupsOnDeath) continue;
			ItemStack plain = unmark(st);
			if (c.admin.dropPickupsOnDeath) {
				ItemEntity drop = new ItemEntity(p.level(), p.getX(), p.getY() + 0.5, p.getZ(), plain);
				drop.setDefaultPickUpDelay();
				p.level().addFreshEntity(drop);
			} else {
				toInbox.add(plain);
			}
		}
		if (swap) {
			// No XP drops from the game either: their own XP comes back with the stash.
			p.setExperienceLevels(0);
			p.setExperiencePoints(0);
		}
		inbox(p, toInbox);
	}

	/** Logging out inside: things back, and out to their home (or respawn point) before the player is saved. */
	private static void loggedOut(MinecraftServer server, ServerPlayer p) {
		String id = inside.remove(p.getUUID());
		Claim c = id == null ? null : ClaimStore.data().claims.get(id);
		if (c == null && !stashes.containsKey(p.getStringUUID())) return;
		leave(p, c);
		sendHome(server, p);
	}

	private static void sendHome(MinecraftServer server, ServerPlayer p) {
		// The Nice Actions home, if it's allowed and not in a game claim.
		CompoundTag home = playerData(server, p.getUUID()).getCompoundOrEmpty("home");
		String dim = home.getStringOr("dimension", "");
		if (!dim.isEmpty() && homeAllowed(dim) && gameClaimAt(dim, home.getIntOr("x", 0), home.getIntOr("z", 0)) == null) {
			ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dim)));
			if (level != null) {
				p.teleportTo(level, home.getIntOr("x", 0) + 0.5, home.getIntOr("y", 64), home.getIntOr("z", 0) + 0.5, Set.of(), p.getYRot(), p.getXRot(), true);
				return;
			}
		}
		// Else where they'd respawn (bed, anchor or world spawn).
		var to = p.findRespawnPositionAndUseSpawnBlock(false, net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING);
		p.teleportTo(to.newLevel(), to.position().x, to.position().y, to.position().z, Set.of(), to.yRot(), to.xRot(), true);
	}

	// ---------------------------------------------------------------- pickups

	/** From the pickup mixin: an item picked up inside a game claim gets the claim's mark. */
	public static void pickedUp(ServerPlayer p, ItemStack stack) {
		String id = inside.get(p.getUUID());
		if (id == null || stack.isEmpty() || kind(stack) != null) return;
		mark(stack, id, "pickup");
	}

	private static void mark(ItemStack st, String claim, String kind) {
		CustomData.update(DataComponents.CUSTOM_DATA, st, t -> {
			t.putString(MARK, claim);
			t.putString(KIND, kind);
		});
	}

	private static String kind(ItemStack st) {
		CustomData d = st.get(DataComponents.CUSTOM_DATA);
		if (d == null) return null;
		CompoundTag t = d.copyTag();
		return t.contains(MARK) ? t.getStringOr(KIND, "pickup") : null;
	}

	private static ItemStack unmark(ItemStack st) {
		CustomData d = st.get(DataComponents.CUSTOM_DATA);
		if (d == null) return st;
		CompoundTag t = d.copyTag();
		t.remove(MARK);
		t.remove(KIND);
		if (t.isEmpty()) st.remove(DataComponents.CUSTOM_DATA);
		else st.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
		return st;
	}

	/** Items into the player's market inbox (mailbox parcels), with a line in chat. */
	private static void inbox(ServerPlayer p, List<ItemStack> items) {
		if (items.isEmpty()) return;
		var data = MarketStore.data();
		var box = data.mailbox(p.getStringUUID(), p.getPlainTextName());
		int n = 0;
		for (ItemStack st : items) {
			eu.explorerseden.nicecontrolcenter.Market.Parcel parcel = new eu.explorerseden.nicecontrolcenter.Market.Parcel();
			parcel.item = MarketStore.store(p, st);
			parcel.amount = st.getCount();
			box.parcels.add(parcel);
			n += st.getCount();
		}
		data.mailChanged(box);
		MarketStore.saveNow();
		p.sendSystemMessage(Component.literal(n + " item" + (n == 1 ? "" : "s") + " you picked up went to your inbox: /inbox").withStyle(ChatFormatting.GOLD));
	}

	// ---------------------------------------------------------------- homes and /back (Nice Actions)

	private static final String TRIGGER = "nice_actions.dialog_trigger";

	/** Before the data packs' tick: set-home (5, 16) and /back (15) where they aren't allowed are cancelled. */
	private static void triggers(MinecraftServer server) {
		Objective obj = server.getScoreboard().getObjective(TRIGGER);
		if (obj == null) return;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			var info = server.getScoreboard().getPlayerScoreInfo(p, obj);
			if (info == null) continue;
			int v = info.value();
			String why = null;
			if (v == 5 || v == 16) {
				String dim = p.level().dimension().identifier().toString();
				if (!homeAllowed(dim)) why = "Homes can't be set in this dimension.";
				else if (blocksHomes(p.level(), p.blockPosition())) why = "You can't set a home here.";
			} else if (v == 15) {
				CompoundTag back = playerData(server, p.getUUID()).getCompoundOrEmpty("back");
				String dim = back.getStringOr("dimension", "");
				if (!dim.isEmpty() && gameClaimAt(dim, (int) Math.floor(number(back, "x")), (int) Math.floor(number(back, "z"))) != null) why = "/back can't take you back into a game area.";
			}
			if (why == null) continue;
			server.getScoreboard().getOrCreatePlayerScore(p, obj).set(0);
			p.sendSystemMessage(Component.literal("▊ ").withColor(0xFF4A4A).append(Component.literal(why).withStyle(ChatFormatting.WHITE)));
		}
	}

	/** Nice Actions homes are allowed in this dimension (Tweaks.homes.dimensions; empty = everywhere). */
	public static boolean homeAllowed(String dim) {
		var dims = TweaksControl.settings().homes.dimensions;
		return dims.isEmpty() || dims.contains(dim);
	}

	private static double number(CompoundTag t, String k) {
		var tag = t.get(k);
		return tag == null ? 0 : tag.asDouble().orElse(0.0);
	}

	/** The player's entry in eden:database (Nice Actions): player.<the four UUID ints>. */
	private static CompoundTag playerData(MinecraftServer server, UUID uuid) {
		int[] a = UUIDUtil.uuidToIntArray(uuid);
		CompoundTag db = server.getCommandStorage().get(Identifier.fromNamespaceAndPath("eden", "database"));
		return db.getCompoundOrEmpty("player").getCompoundOrEmpty("" + a[0] + a[1] + a[2] + a[3]);
	}

	// ---------------------------------------------------------------- kit (admins)

	/** The admin's current inventory becomes the claim's kit; returns how many stacks. */
	public static int setKit(ServerPlayer admin, Claim c) {
		List<String> kit = new ArrayList<>();
		var inv = admin.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack st = inv.getItem(i);
			if (!st.isEmpty()) kit.add(encode(admin, unmark(st.copy())));
		}
		if (c.admin == null) c.admin = new Claims.AdminOptions();
		c.admin.kit = kit;
		ClaimStore.changed();
		return kit.size();
	}

	// ---------------------------------------------------------------- storage

	private static String encode(ServerPlayer p, ItemStack st) {
		return ItemStack.CODEC.encodeStart(p.registryAccess().createSerializationContext(JsonOps.INSTANCE), st).getOrThrow().toString();
	}

	private static ItemStack decode(ServerPlayer p, String json) {
		try {
			return ItemStack.CODEC.parse(p.registryAccess().createSerializationContext(JsonOps.INSTANCE), JsonParser.parseString(json)).getOrThrow();
		} catch (RuntimeException e) {
			NiceControlCenter.LOGGER.warn("A game-claim item can't be read: {}", e.getMessage());
			return ItemStack.EMPTY;
		}
	}

	private static void load(MinecraftServer server) {
		file = server.getWorldPath(LevelResource.ROOT).resolve("nicecontrolcenter-claim-stash.json");
		stashes.clear();
		inside.clear();
		try {
			if (Files.exists(file)) {
				Map<String, Stash> saved = Json.GSON.fromJson(Files.readString(file), new com.google.gson.reflect.TypeToken<Map<String, Stash>>() { }.getType());
				if (saved != null) stashes.putAll(saved);
			}
		} catch (IOException | RuntimeException e) {
			NiceControlCenter.LOGGER.error("Could not read the game-claim stashes ({}): kept as .broken", file, e);
			try {
				Files.copy(file, file.resolveSibling(file.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
				// Nothing more to do.
			}
		}
	}

	/** Written at once (never lose what someone put aside). */
	private static void save() {
		if (file == null) return;
		try {
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, Json.GSON.toJson(stashes), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			NiceControlCenter.LOGGER.error("Could not save the game-claim stashes", e);
		}
	}
}
