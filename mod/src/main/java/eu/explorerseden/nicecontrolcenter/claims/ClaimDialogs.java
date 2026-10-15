package eu.explorerseden.nicecontrolcenter.claims;

import eu.explorerseden.nicecontrolcenter.Claims;
import eu.explorerseden.nicecontrolcenter.Claims.Claim;
import eu.explorerseden.nicecontrolcenter.Claims.Role;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.ConfirmationDialog;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.BooleanInput;
import net.minecraft.server.dialog.input.SingleOptionInput;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import eu.explorerseden.nicecontrolcenter.core.Plop;

/**
 * The claim menus, as Minecraft dialogs (no client mod needed). Buttons send a custom action
 * "nicecontrolcenter:claim/&lt;what&gt;" with the claim id and the dialog's inputs; {@link #handle} checks
 * the player's rights again every time, so nothing in the payload is trusted.
 */
public final class ClaimDialogs {
	private static final String NS = "nicecontrolcenter";
	private static final int WIDTH = 150;
	private static final int TEXT_WIDTH = 310;

	private ClaimDialogs() {
	}

	// ---------------------------------------------------------------- building blocks

	private static void show(ServerPlayer player, Dialog dialog) {
		player.openDialog(Holder.direct(dialog));
	}

	private static CompoundTag args(String... pairs) {
		CompoundTag tag = new CompoundTag();
		for (int i = 0; i + 1 < pairs.length; i += 2) tag.putString(pairs[i], pairs[i + 1]);
		return tag;
	}

	private static ActionButton button(Component label, String tooltip, String action, CompoundTag args) {
		return new ActionButton(new CommonButtonData(label, tooltip == null ? Optional.empty() : Optional.of(Component.literal(tooltip)), WIDTH),
				Optional.of(new CustomAll(Identifier.fromNamespaceAndPath(NS, "claim/" + action), Optional.of(args))));
	}

	private static ActionButton button(String label, String tooltip, String action, CompoundTag args) {
		return button(Component.literal(label), tooltip, action, args);
	}

	private static ActionButton close() {
		return new ActionButton(new CommonButtonData(Component.literal("Close"), WIDTH), Optional.empty());
	}

	private static CommonDialogData common(Component title, List<DialogBody> body, List<Input> inputs) {
		return new CommonDialogData(title, Optional.empty(), true, false, DialogAction.CLOSE, body, inputs);
	}

	private static DialogBody text(Component c) {
		return new PlainMessage(c, TEXT_WIDTH);
	}

	private static DialogBody text(String s, ChatFormatting color) {
		return text(Component.literal(s).withStyle(color));
	}

	private static void menu(ServerPlayer player, Component title, List<DialogBody> body, List<Input> inputs, List<ActionButton> buttons, ActionButton exit) {
		show(player, new MultiActionDialog(common(title, body, inputs), buttons, Optional.ofNullable(exit), 2));
	}

	private static MutableComponent title(Claim c) {
		return Component.literal(c.displayName()).withColor(c.color);
	}

	private static String roleName(Role r) {
		return r == null ? "not a member" : r == Role.OWNER ? "owner" : r.label.toLowerCase(Locale.ROOT);
	}

	private static String dimName(String dim) {
		String path = dim.substring(dim.indexOf(':') + 1).replace('_', ' ');
		return Character.toUpperCase(path.charAt(0)) + path.substring(1);
	}

	// ---------------------------------------------------------------- menus

	/** The claim at a spot (or where the player stands), else the player's own claims. */
	public static void openAt(ServerPlayer player, BlockPos pos) {
		if (!ClaimStore.enabled()) {
			ClaimTool.tell(player, "Claims are off on this server.", ChatFormatting.RED);
			return;
		}
		Claim c = ClaimStore.claimAt(player.level(), pos);
		if (c == null) c = ClaimStore.claimAt(player.level(), player.blockPosition());
		if (c != null) main(player, c);
		else home(player);
	}

	/** Claim blocks, claim mode and the player's claims. */
	public static void home(ServerPlayer player) {
		Claims.Data data = ClaimStore.data();
		Claims.Settings s = ClaimStore.settings();
		String me = player.getStringUUID();
		Claims.PlayerBlocks pb = data.ensurePlayer(me, player.getPlainTextName(), s);
		List<Claim> mine = data.ownedBy(me);
		List<DialogBody> body = new ArrayList<>();
		body.add(text(Component.literal(data.available(me) + " claim blocks available").withStyle(ChatFormatting.GOLD)));
		StringBuilder from = new StringBuilder(pb.accrued + " from playing");
		if (pb.bought != 0) from.append(", ").append(pb.bought).append(" bought");
		if (pb.bonus != 0) from.append(", ").append(pb.bonus > 0 ? "+" : "").append(pb.bonus).append(" from admins");
		from.append(", ").append(data.used(me)).append(" used by ").append(mine.size()).append(mine.size() == 1 ? " claim" : " claims");
		body.add(text(from.toString(), ChatFormatting.GRAY));
		if (s.blocksPerHour > 0) body.add(text("You earn " + s.blocksPerHour + " blocks per hour of play (up to " + s.maxAccruedBlocks + ").", ChatFormatting.GRAY));
		if (mine.isEmpty()) body.add(text("Enter claim mode and right-click two opposite corners to claim land. Everything inside, from bedrock to the sky, is protected.", ChatFormatting.WHITE));
		List<ActionButton> buttons = new ArrayList<>();
		buttons.add(modeButton(player));
		if (ClaimTool.hasSelection(player)) buttons.add(button("Cancel selection", null, "sel_cancel", args()));
		if (eu.explorerseden.nicecontrolcenter.market.MarketDialogs.sellsClaimBlocks()) buttons.add(button("Buy claim blocks", "In the market", "shop", args()));
		for (Claim c : mine.stream().limit(24).toList()) {
			buttons.add(button(Component.literal(c.displayName()).withColor(c.color), dimName(c.dim) + ", " + c.box().width() + " × " + c.box().length()
					+ " around " + (c.minX + c.maxX) / 2 + ", " + (c.minZ + c.maxZ) / 2, "main", args("claim", c.id)));
		}
		menu(player, Component.literal("Your claims"), body, List.of(), buttons, close());
	}

	private static ActionButton modeButton(ServerPlayer player) {
		return ClaimMode.active(player) ? button("Leave claim mode", "Puts the claim tool away", "mode", args())
				: button("Enter claim mode", "Gives you the claim tool", "mode", args());
	}

	public static void main(ServerPlayer player, Claim c) {
		Role r = ClaimTool.role(player, c);
		Claim top = c.isSub() ? ClaimStore.byId(c.parent) : c;
		List<DialogBody> body = new ArrayList<>();
		String owner = c.owner == null ? "the server" : c.ownerName;
		body.add(text((c.isSub() ? "Subclaim of " + top.displayName() + ", owned by " : "Owned by ") + owner, ChatFormatting.WHITE));
		body.add(text(c.box().width() + " × " + c.box().length() + " blocks (" + c.box().area() + ") · x " + c.minX + " to " + c.maxX + ", z " + c.minZ + " to " + c.maxZ
				+ " · " + dimName(c.dim), ChatFormatting.GRAY));
		body.add(text("You're " + roleName(r) + (c.members.isEmpty() ? "" : " · " + c.members.size() + (c.members.size() == 1 ? " member" : " members")), ChatFormatting.GRAY));
		List<String> open = new ArrayList<>();
		for (String[] f : Claims.Flags.LABELS) if (c.flags.get(f[0])) open.add(f[1].replace("Everyone may ", "").replace("Players may ", ""));
		body.add(text(open.isEmpty() ? "Everyone else can only look." : "Allowed for everyone: " + String.join(", ", open) + ".", ChatFormatting.GRAY));
		List<ActionButton> buttons = new ArrayList<>();
		buttons.add(modeButton(player));
		buttons.add(button("Show border", "Marks the edges for a while", "show", args("claim", c.id)));
		boolean manage = r != null && r.includes(Role.MANAGE);
		if (manage) {
			buttons.add(button("Members", "Who may do what here", "members", args("claim", c.id)));
			buttons.add(button("Everyone else", "What players who aren't members may do", "access", args("claim", c.id)));
			buttons.add(button("Name & colour", null, "look", args("claim", c.id)));
			if (!c.isSub()) buttons.add(button("Subclaims (" + ClaimStore.subclaims(c.id).size() + ")", "Areas inside with their own members", "subclaims", args("claim", c.id)));
			else buttons.add(button("Delete subclaim", null, "abandon", args("claim", c.id)));
		}
		if (!c.isSub() && r == Role.OWNER) buttons.add(button("Abandon claim", "Gives the land and its claim blocks back", "abandon", args("claim", c.id)));
		if (c.isSub()) buttons.add(button("Back to " + top.displayName(), null, "main", args("claim", top.id)));
		buttons.add(button("My claims", null, "home", args()));
		menu(player, title(c), body, List.of(), buttons, close());
		ClaimVisuals.show(player, c);
	}

	public static void look(ServerPlayer player, Claim c, boolean fresh) {
		look(player, c, fresh, null);
	}

	private static void look(ServerPlayer player, Claim c, boolean fresh, String notice) {
		Claims.Settings s = ClaimStore.settings();
		List<DialogBody> body = new ArrayList<>();
		if (fresh) body.add(text("Your land is protected. Give it a name and a colour (shown on its border and the web map).", ChatFormatting.WHITE));
		if (notice != null) {
			body.add(text(notice, ChatFormatting.RED));
			Plop.play(player);
		}
		List<SingleOptionInput.Entry> colors = new ArrayList<>();
		boolean matched = false;
		for (Map.Entry<String, String> e : s.colors.entrySet()) {
			int rgb = Claims.parseColor(e.getValue());
			if (rgb < 0) continue;
			boolean current = rgb == c.color && !matched;
			matched |= current;
			colors.add(new SingleOptionInput.Entry(Claims.hex(rgb), Optional.of(Component.literal("■ " + e.getKey()).withColor(rgb)), current));
		}
		if (!matched) colors.add(0, new SingleOptionInput.Entry(Claims.hex(c.color), Optional.of(Component.literal("■ Current").withColor(c.color)), true));
		List<Input> inputs = List.of(
				new Input("name", new TextInput(250, Component.literal("Name"), true, c.name, 32, Optional.empty())),
				new Input("color", new SingleOptionInput(250, colors, Component.literal("Colour"), true)),
				new Input("hex", new TextInput(250, Component.literal("Or your own colour (#RRGGBB)"), true, "", 7, Optional.empty())));
		List<ActionButton> buttons = List.of(button("Save", null, "look_save", args("claim", c.id)), button("Back", null, "main", args("claim", c.id)));
		menu(player, fresh ? Component.literal("Your new claim") : title(c), body, inputs, buttons, null);
	}

	private static void members(ServerPlayer player, Claim c) {
		List<DialogBody> body = new ArrayList<>();
		body.add(text("Members may do more here than everyone else:", ChatFormatting.WHITE));
		for (Role r : Role.values()) if (r != Role.OWNER) body.add(text(r.label + ": " + r.description.toLowerCase(Locale.ROOT), ChatFormatting.GRAY));
		if (c.isSub()) body.add(text("Members of the claim around keep their rights here.", ChatFormatting.GRAY));
		List<ActionButton> buttons = new ArrayList<>();
		buttons.add(button("Add a player", null, "member_new", args("claim", c.id)));
		c.members.forEach((id, m) -> buttons.add(button(m.name + " — " + m.role.label, "Change or remove", "member", args("claim", c.id, "uuid", id))));
		buttons.add(button("Back", null, "main", args("claim", c.id)));
		menu(player, title(c).append(Component.literal(" · Members").withStyle(ChatFormatting.WHITE)), body, List.of(), buttons, null);
	}

	private static List<SingleOptionInput.Entry> roleEntries(ServerPlayer player, Claim c, Role current) {
		boolean owner = ClaimTool.role(player, c) == Role.OWNER;
		List<SingleOptionInput.Entry> roles = new ArrayList<>();
		for (Role r : Role.values()) {
			if (r == Role.OWNER || (r == Role.MANAGE && !owner && current != Role.MANAGE)) continue;
			roles.add(new SingleOptionInput.Entry(r.name(), Optional.of(Component.literal(r.label)), r == current));
		}
		return roles;
	}

	private static void member(ServerPlayer player, Claim c, String uuid) {
		Claims.Member m = c.members.get(uuid);
		if (m == null) {
			members(player, c);
			return;
		}
		List<DialogBody> body = List.of(text(m.role.label + ": " + m.role.description.toLowerCase(Locale.ROOT), ChatFormatting.GRAY));
		List<Input> inputs = List.of(new Input("role", new SingleOptionInput(250, roleEntries(player, c, m.role), Component.literal("Role"), true)));
		List<ActionButton> buttons = List.of(button("Save", null, "member_set", args("claim", c.id, "uuid", uuid)),
				button("Remove", null, "member_remove", args("claim", c.id, "uuid", uuid)),
				button("Back", null, "members", args("claim", c.id)));
		menu(player, Component.literal(m.name).withStyle(ChatFormatting.WHITE), body, inputs, buttons, null);
	}

	private static void memberNew(ServerPlayer player, Claim c, String notice) {
		List<DialogBody> body = new ArrayList<>();
		if (notice != null) {
			body.add(text(notice, ChatFormatting.RED));
			Plop.play(player);
		}
		List<SingleOptionInput.Entry> online = new ArrayList<>();
		online.add(new SingleOptionInput.Entry("", Optional.of(Component.literal("— type a name below —")), true));
		for (ServerPlayer p : player.level().getServer().getPlayerList().getPlayers()) {
			String id = p.getStringUUID();
			if (p == player || id.equals(c.owner) || c.members.containsKey(id)) continue;
			if (online.size() > 40) break;
			online.add(new SingleOptionInput.Entry(id, Optional.of(Component.literal(p.getPlainTextName())), false));
		}
		List<Input> inputs = new ArrayList<>();
		if (online.size() > 1) inputs.add(new Input("online", new SingleOptionInput(250, online, Component.literal("Online"), true)));
		inputs.add(new Input("name", new TextInput(250, Component.literal("Player name"), true, "", 16, Optional.empty())));
		inputs.add(new Input("role", new SingleOptionInput(250, roleEntries(player, c, Role.BUILD), Component.literal("Role"), true)));
		List<ActionButton> buttons = List.of(button("Add", null, "member_add", args("claim", c.id)), button("Back", null, "members", args("claim", c.id)));
		menu(player, Component.literal("Add a member"), body, inputs, buttons, null);
	}

	private static void access(ServerPlayer player, Claim c) {
		List<Input> inputs = new ArrayList<>();
		for (String[] f : Claims.Flags.LABELS) inputs.add(new Input(f[0], new BooleanInput(Component.literal(f[1]), c.flags.get(f[0]), "true", "false")));
		List<DialogBody> body = List.of(text("What players who aren't members may do, and what may happen to the land.", ChatFormatting.GRAY));
		List<ActionButton> buttons = List.of(button("Save", null, "flags_save", args("claim", c.id)), button("Back", null, "main", args("claim", c.id)));
		menu(player, title(c).append(Component.literal(" · Everyone else").withStyle(ChatFormatting.WHITE)), body, inputs, buttons, null);
	}

	private static void subclaims(ServerPlayer player, Claim c) {
		List<DialogBody> body = List.of(text("Subclaims are areas inside your claim where members can get more rights, like a shop or a friend's house.", ChatFormatting.GRAY));
		List<ActionButton> buttons = new ArrayList<>();
		buttons.add(button("Make a subclaim", "Then right-click two corners inside the claim", "sub_start", args("claim", c.id)));
		for (Claim s : ClaimStore.subclaims(c.id)) buttons.add(button(Component.literal(s.displayName()).withColor(s.color), s.box().width() + " × " + s.box().length(), "main", args("claim", s.id)));
		buttons.add(button("Back", null, "main", args("claim", c.id)));
		menu(player, title(c).append(Component.literal(" · Subclaims").withStyle(ChatFormatting.WHITE)), body, List.of(), buttons, null);
	}

	private static void abandon(ServerPlayer player, Claim c) {
		String what = c.isSub() ? "Delete the subclaim " + c.displayName() + "?" : "Abandon " + c.displayName() + "? Anyone can build there afterwards, and you get its "
				+ c.box().area() + " claim blocks back.";
		CommonDialogData data = common(Component.literal(c.isSub() ? "Delete subclaim" : "Abandon claim"), List.of(text(what, ChatFormatting.WHITE)), List.of());
		show(player, new ConfirmationDialog(data, button(Component.literal(c.isSub() ? "Delete" : "Abandon").withStyle(ChatFormatting.RED), null, "abandon_yes", args("claim", c.id)),
				button("Keep it", null, "main", args("claim", c.id))));
	}

	// ---------------------------------------------------------------- answers

	private static String str(CompoundTag tag, String key) {
		return tag.getStringOr(key, "");
	}

	private static boolean bool(CompoundTag tag, String key) {
		Tag t = tag.get(key);
		if (t instanceof ByteTag) return tag.getBooleanOr(key, false);
		return "true".equals(tag.getStringOr(key, ""));
	}

	/** A button was clicked ({@code what} is the part after "claim/"). Runs on the server thread. */
	public static void handle(ServerPlayer player, String what, Optional<Tag> payload) {
		if (!ClaimStore.enabled()) {
			ClaimTool.tell(player, "Claims are off on this server.", ChatFormatting.RED);
			return;
		}
		CompoundTag in = payload.orElse(null) instanceof CompoundTag t ? t : new CompoundTag();
		Claims.Data data = ClaimStore.data();
		long now = System.currentTimeMillis();
		switch (what) {
			case "home" -> home(player);
			case "mode" -> ClaimMode.toggle(player);
			case "sel_cancel" -> {
				ClaimTool.clear(player);
				home(player);
			}
			case "shop" -> {
				if (eu.explorerseden.nicecontrolcenter.market.MarketDialogs.sellsClaimBlocks()) eu.explorerseden.nicecontrolcenter.market.MarketDialogs.claimBlocks(player);
			}
			default -> {
				Claim c = ClaimStore.byId(str(in, "claim"));
				if (c == null) {
					ClaimTool.tell(player, "That claim doesn't exist any more.", ChatFormatting.RED);
					return;
				}
				Role r = ClaimTool.role(player, c);
				boolean manage = r != null && r.includes(Role.MANAGE);
				switch (what) {
					case "main" -> main(player, c);
					case "show" -> {
						ClaimVisuals.show(player, c);
						ClaimTool.bar(player, "Showing " + c.displayName() + ".", ChatFormatting.GOLD);
					}
					default -> {
						if (!manage) {
							ClaimTool.tell(player, "Only the owner and co-owners can change " + c.displayName() + ".", ChatFormatting.RED);
							return;
						}
						claimAction(player, c, r, what, in, data, now);
					}
				}
			}
		}
	}

	private static void claimAction(ServerPlayer player, Claim c, Role r, String what, CompoundTag in, Claims.Data data, long now) {
		switch (what) {
			case "members" -> members(player, c);
			case "member" -> member(player, c, str(in, "uuid"));
			case "member_new" -> memberNew(player, c, null);
			case "member_add" -> {
				String name = str(in, "name").strip();
				String id = str(in, "online");
				NameAndId who = null;
				var server = player.level().getServer();
				if (!name.isEmpty()) {
					ServerPlayer online = server.getPlayerList().getPlayerByName(name);
					if (online != null) who = online.nameAndId();
					else who = server.services().nameToIdCache().get(name).orElse(null);
					if (who == null) {
						memberNew(player, c, "Nobody called \"" + name + "\" has played here.");
						return;
					}
				} else if (!id.isEmpty()) {
					ServerPlayer online = server.getPlayerList().getPlayer(UUID.fromString(id));
					if (online != null) who = online.nameAndId();
				}
				if (who == null) {
					memberNew(player, c, "Pick an online player or type a name.");
					return;
				}
				Role role = role(player, c, str(in, "role"));
				String err = role == null ? "Pick a role." : data.setMember(c, who.id().toString(), who.name(), role, now);
				if (err != null) {
					memberNew(player, c, err);
					return;
				}
				ClaimStore.changed();
				ClaimTool.tell(player, who.name() + " is now " + role.label.toLowerCase(Locale.ROOT) + " in " + c.displayName() + ".", ChatFormatting.GREEN);
				ServerPlayer them = server.getPlayerList().getPlayer(who.id());
				if (them != null) ClaimTool.tell(them, player.getPlainTextName() + " made you " + role.label.toLowerCase(Locale.ROOT) + " in " + c.displayName() + ".", ChatFormatting.GOLD);
				members(player, c);
			}
			case "member_set" -> {
				String uuid = str(in, "uuid");
				Claims.Member m = c.members.get(uuid);
				Role role = role(player, c, str(in, "role"));
				if (m == null || role == null) {
					members(player, c);
					return;
				}
				if (m.role == Role.MANAGE && r != Role.OWNER) {
					ClaimTool.tell(player, "Only the owner can change co-owners.", ChatFormatting.RED);
					members(player, c);
					return;
				}
				data.setMember(c, uuid, m.name, role, now);
				ClaimStore.changed();
				members(player, c);
			}
			case "member_remove" -> {
				String uuid = str(in, "uuid");
				Claims.Member m = c.members.get(uuid);
				if (m != null && m.role == Role.MANAGE && r != Role.OWNER) {
					ClaimTool.tell(player, "Only the owner can remove co-owners.", ChatFormatting.RED);
				} else if (m != null) {
					data.removeMember(c, uuid, now);
					ClaimStore.changed();
					ClaimTool.tell(player, m.name + " is no longer a member of " + c.displayName() + ".", ChatFormatting.GRAY);
				}
				members(player, c);
			}
			case "access" -> access(player, c);
			case "flags_save" -> {
				Claims.Flags f = new Claims.Flags();
				for (String[] flag : Claims.Flags.LABELS) f.set(flag[0], bool(in, flag[0]));
				data.setFlags(c, f, now);
				ClaimStore.changed();
				main(player, c);
			}
			case "look" -> look(player, c, false, null);
			case "look_save" -> {
				String hex = str(in, "hex").strip();
				int rgb = hex.isEmpty() ? Claims.parseColor(str(in, "color")) : Claims.parseColor(hex);
				if (rgb < 0) {
					look(player, c, false, "\"" + hex + "\" isn't a colour like #3FC9E8.");
					return;
				}
				String err = data.rename(c, str(in, "name"), now);
				if (err != null) {
					look(player, c, false, err);
					return;
				}
				data.recolor(c, rgb, now);
				ClaimStore.changed();
				main(player, c);
			}
			case "subclaims" -> {
				if (!c.isSub()) subclaims(player, c);
			}
			case "sub_start" -> {
				if (!c.isSub()) ClaimTool.startSubclaim(player, c);
			}
			case "abandon" -> {
				if (c.isSub() || r == Role.OWNER) abandon(player, c);
			}
			case "abandon_yes" -> {
				if (!c.isSub() && r != Role.OWNER) return;
				data.delete(c);
				ClaimStore.changed();
				ClaimTool.tell(player, (c.isSub() ? "Subclaim " + c.displayName() + " deleted." : c.displayName() + " abandoned. "
						+ (c.owner != null ? data.available(c.owner) + " claim blocks available." : "")), ChatFormatting.GRAY);
				if (c.isSub()) {
					Claim top = ClaimStore.byId(c.parent);
					if (top != null) main(player, top);
				}
			}
			default -> {
			}
		}
	}

	/** A role from a dialog; only owners hand out co-owner. */
	private static Role role(ServerPlayer player, Claim c, String text) {
		Role role = Role.parse(text);
		if (role == null || role == Role.OWNER) return null;
		if (role == Role.MANAGE && ClaimTool.role(player, c) != Role.OWNER) return null;
		return role;
	}
}
