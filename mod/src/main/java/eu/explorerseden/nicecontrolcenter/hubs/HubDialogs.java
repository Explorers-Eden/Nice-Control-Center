package eu.explorerseden.nicecontrolcenter.hubs;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.SingleOptionInput;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Marker;

import eu.explorerseden.nicecontrolcenter.claims.ClaimTool;
import eu.explorerseden.nicecontrolcenter.core.Plop;

/**
 * The owner's menu of a waypoint hub. Warping Wonders opens its waypoint list when a hub is
 * right-clicked; for the hub's owner that dialog is swapped for this menu (name, description,
 * public/private, trusted players) with a button that opens the pack's list after all. Buttons send
 * "nicecontrolcenter:hubs/&lt;what&gt;" with the hub id; {@link #handle} checks ownership again each time.
 */
public final class HubDialogs {
	private static final String NS = "nicecontrolcenter";
	private static final int WIDTH = 150;
	private static final int TEXT_WIDTH = 310;
	/** A line of the pack's waypoint list, to recognise that dialog. */
	private static final String LIST_KEY = "menu.warping_wonders.waypoint_hub.main.description";
	private static final Identifier TEMP = Identifier.fromNamespaceAndPath("eden", "temp");

	/** Set while the pack's list is opened from this menu, so it isn't swapped again. */
	private static boolean openingList;
	/** The pack's list was shown while {@link #openingList} was set. */
	private static boolean listShown;

	private HubDialogs() {
	}

	/** Called for every dialog the server shows; true = it was the pack's list for the hub's owner, and the owner menu opened instead. */
	public static boolean intercept(ServerPlayer player, Holder<Dialog> dialog) {
		if (openingList && dialog.value() instanceof MultiActionDialog shown && isHubList(shown)) listShown = true;
		if (openingList || !eu.explorerseden.nicecontrolcenter.claims.ClaimStore.settings().hubsOwnerMenu || !(dialog.value() instanceof MultiActionDialog d) || !isHubList(d)) return false;
		MinecraftServer server = player.level().getServer();
		// The pack's menu function leaves the hub it was opened for in eden:temp while the dialog is shown.
		int id = server.getCommandStorage().get(TEMP).getCompoundOrEmpty("waypoint_hub").getCompoundOrEmpty("menu").getIntOr("home_id", -1);
		if (id < 0 || !Hubs.ownedBy(Hubs.hub(server, id), player.getUUID())) return false;
		main(player, id);
		return true;
	}

	private static boolean isHubList(MultiActionDialog d) {
		for (DialogBody body : d.common().body()) {
			if (body instanceof PlainMessage m && mentions(m.contents(), LIST_KEY)) return true;
		}
		return false;
	}

	private static boolean mentions(Component c, String key) {
		if (c.getContents() instanceof TranslatableContents t && key.equals(t.getKey())) return true;
		for (Component s : c.getSiblings()) if (mentions(s, key)) return true;
		return false;
	}

	// ---------------------------------------------------------------- building blocks

	private static CompoundTag args(int hub, String... pairs) {
		CompoundTag tag = new CompoundTag();
		tag.putString("hub", String.valueOf(hub));
		for (int i = 0; i + 1 < pairs.length; i += 2) tag.putString(pairs[i], pairs[i + 1]);
		return tag;
	}

	private static ActionButton button(String label, String tooltip, String action, CompoundTag args) {
		return new ActionButton(new CommonButtonData(Component.literal(label), Optional.of(Component.literal(tooltip)), WIDTH),
				Optional.of(new CustomAll(Identifier.fromNamespaceAndPath(NS, "hubs/" + action), Optional.of(args))));
	}

	private static ActionButton close() {
		return new ActionButton(new CommonButtonData(Component.literal("Close"), WIDTH), Optional.empty());
	}

	private static DialogBody text(Component c) {
		return new PlainMessage(c, TEXT_WIDTH);
	}

	private static DialogBody text(String s, ChatFormatting color) {
		return text(Component.literal(s).withStyle(color));
	}

	private static void menu(ServerPlayer player, Component title, List<DialogBody> body, List<Input> inputs, List<ActionButton> buttons, ActionButton exit) {
		CommonDialogData common = new CommonDialogData(title, Optional.empty(), true, false, DialogAction.CLOSE, body, inputs);
		player.openDialog(Holder.direct(new MultiActionDialog(common, buttons, Optional.ofNullable(exit), 2)));
	}

	private static MutableComponent title(MinecraftServer server, CompoundTag hub) {
		return Component.literal(Hubs.name(server, hub)).withColor(Hubs.color(hub));
	}

	private static void notice(List<DialogBody> body, ServerPlayer player, String notice) {
		if (notice == null) return;
		body.add(text(notice, ChatFormatting.RED));
		Plop.play(player);
	}

	// ---------------------------------------------------------------- menus

	private static void main(ServerPlayer player, int id) {
		MinecraftServer server = player.level().getServer();
		CompoundTag hub = Hubs.hub(server, id);
		if (hub == null) return;
		CompoundTag pos = hub.getCompoundOrEmpty("pos");
		List<DialogBody> body = new ArrayList<>();
		body.add(text("Your waypoint hub · " + pos.getIntOr("x", 0) + ", " + pos.getIntOr("y", 0) + ", " + pos.getIntOr("z", 0)
				+ " · " + hub.getStringOr("dimension_name", Hubs.dimension(hub)), ChatFormatting.GRAY));
		String description = Hubs.description(server, hub);
		if (!description.isEmpty()) body.add(text(description, ChatFormatting.WHITE));
		List<String> trusted = Hubs.trusted(hub);
		body.add(text(Hubs.isPublic(hub) ? "Public: everyone can travel here."
				: "Private: only you" + (trusted.isEmpty() ? "" : " and the players you trust") + " can travel here.", ChatFormatting.GRAY));
		List<ActionButton> buttons = new ArrayList<>();
		buttons.add(button("Waypoints", "Open the list of hubs to travel to", "list", args(id)));
		buttons.add(button("Name & colour", "Name, description and colour, shown above the hub and in the waypoint list", "edit", args(id)));
		buttons.add(Hubs.isPublic(hub) ? button("Make private", "Only you and the players you trust can travel here", "access", args(id))
				: button("Make public", "Everyone can travel here", "access", args(id)));
		buttons.add(button("Trusted players (" + trusted.size() + ")", "Who may travel here while the hub is private", "trust", args(id)));
		buttons.add(Hubs.announces(hub) ? button("Arrival message: on", "You hear and read who teleports here. Click to switch it off", "announce", args(id))
				: button("Arrival message: off", "Click to hear and read who teleports here", "announce", args(id)));
		menu(player, title(server, hub), body, List.of(), buttons, close());
	}

	private static void edit(ServerPlayer player, int id, CompoundTag hub, String notice) {
		MinecraftServer server = player.level().getServer();
		List<DialogBody> body = new ArrayList<>();
		body.add(text("The name floats above the hub in its colour; all three show in everyone's waypoint list.", ChatFormatting.GRAY));
		notice(body, player, notice);
		String current = Hubs.dye(hub);
		List<SingleOptionInput.Entry> colors = new ArrayList<>();
		if (current == null) colors.add(new SingleOptionInput.Entry("", Optional.of(Component.literal("■ Current").withColor(Hubs.color(hub))), true));
		Hubs.COLORS.forEach((dye, hex) -> colors.add(new SingleOptionInput.Entry(dye,
				Optional.of(Component.literal("■ " + Character.toUpperCase(dye.charAt(0)) + dye.substring(1).replace('_', ' ')).withColor(Integer.parseInt(hex.substring(1), 16))), dye.equals(current))));
		List<Input> inputs = List.of(
				new Input("name", new TextInput(250, Component.literal("Name"), true, Hubs.clean(Hubs.name(server, hub)), Hubs.MAX_TEXT, Optional.empty())),
				new Input("description", new TextInput(250, Component.literal("Description"), true, Hubs.clean(Hubs.description(server, hub)), Hubs.MAX_TEXT, Optional.empty())),
				new Input("color", new SingleOptionInput(250, colors, Component.literal("Colour"), true)));
		List<ActionButton> buttons = List.of(button("Save", "Save the name, description and colour", "edit_save", args(id)),
				button("Back", "Back to the hub, without saving", "main", args(id)));
		menu(player, title(server, hub), body, inputs, buttons, null);
	}

	private static void trust(ServerPlayer player, int id, CompoundTag hub) {
		MinecraftServer server = player.level().getServer();
		List<DialogBody> body = new ArrayList<>();
		body.add(text("Trusted players may travel here while the hub is private." + (Hubs.isPublic(hub) ? " It's public now, so everyone can." : ""), ChatFormatting.GRAY));
		List<String> trusted = Hubs.trusted(hub);
		List<ActionButton> buttons = new ArrayList<>();
		buttons.add(button("Trust a player", "Let someone travel here while it's private", "trust_new", args(id)));
		for (String name : trusted.stream().limit(40).toList()) buttons.add(button(name, "Stop trusting " + name, "untrust", args(id, "name", name)));
		buttons.add(button("Back", "Back to the hub", "main", args(id)));
		menu(player, title(server, hub).append(Component.literal(" · Trusted").withStyle(ChatFormatting.WHITE)), body, List.of(), buttons, null);
	}

	private static void trustNew(ServerPlayer player, int id, CompoundTag hub, String notice) {
		List<DialogBody> body = new ArrayList<>();
		notice(body, player, notice);
		List<String> trusted = Hubs.trusted(hub);
		List<SingleOptionInput.Entry> online = new ArrayList<>();
		online.add(new SingleOptionInput.Entry("", Optional.of(Component.literal("— type a name below —")), true));
		for (ServerPlayer p : player.level().getServer().getPlayerList().getPlayers()) {
			if (p == player || trusted.contains(p.getPlainTextName())) continue;
			if (online.size() > 40) break;
			online.add(new SingleOptionInput.Entry(p.getStringUUID(), Optional.of(Component.literal(p.getPlainTextName())), false));
		}
		List<Input> inputs = new ArrayList<>();
		if (online.size() > 1) inputs.add(new Input("online", new SingleOptionInput(250, online, Component.literal("Online"), true)));
		inputs.add(new Input("name", new TextInput(250, Component.literal("Player name"), true, "", 16, Optional.empty())));
		List<ActionButton> buttons = List.of(button("Trust", "Let them travel here while the hub is private", "trust_add", args(id)),
				button("Back", "Back to the trusted players", "trust", args(id)));
		menu(player, Component.literal("Trust a player"), body, inputs, buttons, null);
	}

	/**
	 * The pack's own waypoint list, as a right-click without this menu would have opened it. When it doesn't
	 * open, the player is told and the command's error (if any) goes to the log, instead of nothing happening.
	 */
	private static void openList(ServerPlayer player, int id) {
		MinecraftServer server = player.level().getServer();
		// Only errors are kept: the function's own messages stay out of chat and the log.
		StringBuilder errors = new StringBuilder();
		net.minecraft.commands.CommandSource capture = new net.minecraft.commands.CommandSource() {
			@Override
			public void sendSystemMessage(Component message) {
				if (!errors.isEmpty()) errors.append(" / ");
				errors.append(message.getString());
			}

			@Override
			public boolean acceptsSuccess() {
				return false;
			}

			@Override
			public boolean acceptsFailure() {
				return true;
			}

			@Override
			public boolean shouldInformAdmins() {
				return false;
			}
		};
		String command = "function wawo:waypoint_hub/interact/right_click/menu/init {waypoint_id:" + id + "}";
		openingList = true;
		listShown = false;
		try {
			// The level before the position: withLevel scales the position like /execute in (÷ 8 into the Nether),
			// which put the source far from the hub there, so the pack found neither the hub nor the player.
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSource(capture).withLevel(player.level()).withEntity(player)
					.withPosition(player.position()).withRotation(player.getRotationVector()), command);
		} finally {
			openingList = false;
		}
		if (!listShown) {
			eu.explorerseden.nicecontrolcenter.NiceControlCenter.LOGGER.warn("Hubs: the waypoint list of hub {} didn't open for {} at {} in {}: {}", id, player.getGameProfile().name(),
					player.blockPosition().toShortString(), player.level().dimension().identifier(), errors.isEmpty() ? "no error, but no list was shown" : errors);
			ClaimTool.tell(player, "The waypoint list didn't open" + (errors.isEmpty() ? "." : ": " + errors) + " Please tell an admin.",
					ChatFormatting.RED);
		}
	}

	// ---------------------------------------------------------------- answers

	/** A button was clicked ({@code what} is the part after "hubs/"). Runs on the server thread. */
	public static void handle(ServerPlayer player, String what, Optional<Tag> payload) {
		CompoundTag in = payload.orElse(null) instanceof CompoundTag t ? t : new CompoundTag();
		MinecraftServer server = player.level().getServer();
		int id;
		try {
			id = Integer.parseInt(in.getStringOr("hub", ""));
		} catch (NumberFormatException e) {
			return;
		}
		CompoundTag hub = Hubs.hub(server, id);
		if (hub == null) {
			ClaimTool.tell(player, "That waypoint hub doesn't exist any more.", ChatFormatting.RED);
			return;
		}
		if (!Hubs.ownedBy(hub, player.getUUID())) {
			ClaimTool.tell(player, "Only the hub's owner can change it.", ChatFormatting.RED);
			return;
		}
		switch (what) {
			case "main" -> main(player, id);
			case "edit" -> edit(player, id, hub, null);
			case "trust" -> trust(player, id, hub);
			case "trust_new" -> trustNew(player, id, hub, null);
			default -> {
				// Changes (and the list, which finds the hub around the player) need the hub nearby, like the pack's own tools.
				Marker marker = Hubs.marker(player, id);
				if (marker == null) {
					// In chat: the menu just closed, so a line on the action bar is easy to miss.
					ClaimTool.tell(player, "Go back to the hub to do that (within 16 blocks of it).", ChatFormatting.RED);
					return;
				}
				change(player, id, hub, marker, what, in);
			}
		}
	}

	private static void change(ServerPlayer player, int id, CompoundTag hub, Marker marker, String what, CompoundTag in) {
		MinecraftServer server = player.level().getServer();
		switch (what) {
			case "list" -> openList(player, id);
			case "edit_save" -> {
				String name = Hubs.clean(in.getStringOr("name", ""));
				String description = Hubs.clean(in.getStringOr("description", ""));
				if (name.isEmpty()) {
					edit(player, id, hub, "Give the hub a name.");
					return;
				}
				if (!name.equals(Hubs.name(server, hub))) Hubs.rename(server, id, marker, name);
				if (!description.equals(Hubs.description(server, hub))) Hubs.describe(server, id, description);
				String dye = in.getStringOr("color", "");
				if (Hubs.COLORS.containsKey(dye) && !dye.equals(Hubs.dye(hub))) Hubs.recolor(server, id, marker, dye);
				player.level().playSound(null, marker.blockPosition(), SoundEvents.PLAYER_ATTACK_WEAK, SoundSource.PLAYERS, 1f, 1f);
				main(player, id);
			}
			case "announce" -> {
				String problem = Hubs.setAnnounces(player, id, marker, !Hubs.announces(hub));
				if (problem != null) ClaimTool.tell(player, problem, ChatFormatting.RED);
				main(player, id);
			}
			case "access" -> {
				Hubs.setPublic(server, id, !Hubs.isPublic(hub));
				player.level().playSound(null, marker.blockPosition(), SoundEvents.CHEST_LOCKED, SoundSource.PLAYERS, 0.4f, 1.5f);
				main(player, id);
			}
			case "trust_add" -> {
				String typed = in.getStringOr("name", "").strip();
				String pick = in.getStringOr("online", "");
				NameAndId who = null;
				if (!typed.isEmpty()) {
					ServerPlayer online = server.getPlayerList().getPlayerByName(typed);
					who = online != null ? online.nameAndId() : server.services().nameToIdCache().get(typed).orElse(null);
					if (who == null) {
						trustNew(player, id, hub, "Nobody called \"" + typed + "\" has played here.");
						return;
					}
				} else if (!pick.isEmpty()) {
					try {
						ServerPlayer online = server.getPlayerList().getPlayer(UUID.fromString(pick));
						if (online != null) who = online.nameAndId();
					} catch (IllegalArgumentException e) {
						who = null;
					}
				}
				if (who == null) {
					trustNew(player, id, hub, "Pick an online player or type a name.");
					return;
				}
				if (who.id().equals(player.getUUID())) {
					trustNew(player, id, hub, "You can always travel to your own hub.");
					return;
				}
				List<String> trusted = new ArrayList<>(Hubs.trusted(hub));
				// The pack compares names exactly, so the trusted name is spelled as the game knows the player.
				if (!trusted.contains(who.name())) trusted.add(who.name());
				Hubs.setTrusted(server, id, trusted);
				ClaimTool.tell(player, who.name() + " may now travel to " + Hubs.name(server, hub) + ".", ChatFormatting.GREEN);
				trust(player, id, Hubs.hub(server, id));
			}
			case "untrust" -> {
				List<String> trusted = new ArrayList<>(Hubs.trusted(hub));
				String name = in.getStringOr("name", "");
				if (trusted.remove(name)) {
					Hubs.setTrusted(server, id, trusted);
					ClaimTool.tell(player, name + " is no longer trusted at " + Hubs.name(server, hub) + ".", ChatFormatting.GRAY);
				}
				trust(player, id, Hubs.hub(server, id));
			}
			default -> {
			}
		}
	}
}
