package eu.explorerseden.nicecontrolcenter.games;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.SingleOptionInput;

import eu.explorerseden.nicecontrolcenter.command.Everyone;

/**
 * /party: a group of players (up to {@link #MAX}) who play together: dungeons and wave fights start for the whole
 * party from the leader, and game entrances bring the party along. The leader makes it (/party create, or by
 * inviting someone) and it stays, even with only the leader in it, until the leader disbands it, logs out or leaves
 * the game dimensions (Flying Fair, the dungeons, the game hub's); the others can leave whenever they like (going
 * elsewhere leaves too). Kept in memory only.
 */
public final class Parties {
	/** Players in one party at most (each game still has its own limits). */
	public static final int MAX = 8;
	/** leader → members (leader first). */
	private static final Map<UUID, Set<UUID>> parties = new HashMap<>();
	/** player → their party's leader. */
	private static final Map<UUID, UUID> leaderOf = new HashMap<>();
	/** invited player → leader who invited, and until when. */
	private static final Map<UUID, UUID> invites = new HashMap<>();
	private static final Map<UUID, Long> inviteUntil = new HashMap<>();

	private Parties() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((d, access, env) -> commands(d));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID id = handler.player.getUUID();
			if (id.equals(leaderOf.get(id))) disband(server, id, handler.player.getGameProfile().name() + " logged out; the party is over.");
			else leave(server, id, true);
		});
		// Parties are for the game dimensions: leaving them is like logging out.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 20 != 0 || parties.isEmpty()) return;
			for (UUID leader : List.copyOf(parties.keySet())) {
				ServerPlayer l = server.getPlayerList().getPlayer(leader);
				if (l == null) {
					disband(server, leader, "The leader is gone; the party is over.");
					continue;
				}
				if (!here(l)) {
					disband(server, leader, l.getGameProfile().name() + " left the game worlds; the party is over.");
					l.sendSystemMessage(Words.lit("[Party] You left the game worlds: your party is over.").withStyle(ChatFormatting.GRAY));
					continue;
				}
				for (UUID id : List.copyOf(parties.get(leader))) {
					ServerPlayer m = server.getPlayerList().getPlayer(id);
					if (id.equals(leader) || m == null || here(m)) continue;
					leave(server, id, false);
					m.sendSystemMessage(Words.lit("[Party] You left the game worlds and with them the party.").withStyle(ChatFormatting.GRAY));
				}
			}
		});
	}

	/** Flying Fair, the dungeon dimension, or the game hub's dimension: where parties are a thing. */
	public static boolean here(ServerPlayer p) {
		return gameWorld(p.level().dimension().identifier().toString());
	}

	/** One of the game worlds: Flying Fair, the dungeon or Rogue Runs dimension, or the game hub's dimension. */
	public static boolean gameWorld(String dim) {
		if (dim.equals("eden:flying_fair") || eu.explorerseden.nicecontrolcenter.Dungeons.runDimension(dim)) return true;
		var hub = GameRuntime.settings().hub;
		return hub != null && dim.equals(hub.dim);
	}

	private static final String ONLY_HERE = "Parties are for Flying Fair and the dungeons.";

	/** The player's party with them in it (leader first); just them without a party. */
	public static List<UUID> members(UUID player) {
		UUID leader = leaderOf.get(player);
		return leader == null ? List.of(player) : List.copyOf(parties.get(leader));
	}

	/** The party's leader, or someone without a party. */
	public static boolean isLeader(UUID player) {
		UUID leader = leaderOf.get(player);
		return leader == null || leader.equals(player);
	}

	/** For the dashboard: the party's leader and members by name (leader first), or null without a party. */
	public static Map<String, Object> view(MinecraftServer server, UUID player) {
		UUID leader = leaderOf.get(player);
		Set<UUID> all = leader == null ? null : parties.get(leader);
		if (all == null) return null;
		Map<String, Object> m = new java.util.LinkedHashMap<>();
		m.put("leader", name(server, leader));
		m.put("lead", leader.equals(player));
		m.put("members", all.stream().map(id -> name(server, id)).toList());
		return m;
	}

	public static boolean inParty(UUID player) {
		return leaderOf.containsKey(player);
	}

	private static void tell(MinecraftServer server, UUID who, Component text) {
		ServerPlayer p = server.getPlayerList().getPlayer(who);
		if (p != null) p.sendSystemMessage(text);
	}

	private static void tellAll(MinecraftServer server, UUID leader, String text) {
		Set<UUID> all = parties.get(leader);
		if (all == null) return;
		for (UUID id : all) tell(server, id, Words.lit("[Party] " + text).withStyle(ChatFormatting.LIGHT_PURPLE));
	}

	/** A new party with just the leader (nothing when they're in one already). */
	private static Set<UUID> create(UUID leader) {
		UUID in = leaderOf.get(leader);
		if (in != null) return parties.get(in);
		Set<UUID> all = new LinkedHashSet<>(List.of(leader));
		parties.put(leader, all);
		leaderOf.put(leader, leader);
		return all;
	}

	/** The party is over: everyone in it is on their own again. */
	private static void disband(MinecraftServer server, UUID leader, String why) {
		Set<UUID> all = parties.remove(leader);
		if (all == null) return;
		for (UUID id : all) {
			leaderOf.remove(id);
			if (!id.equals(leader)) tell(server, id, Words.lit("[Party] " + why).withStyle(ChatFormatting.GRAY));
		}
		invites.values().removeIf(leader::equals);
	}

	/** A member leaves (the leader can't: they disband it). */
	private static void leave(MinecraftServer server, UUID player, boolean quietly) {
		invites.remove(player);
		UUID leader = leaderOf.get(player);
		if (leader == null || leader.equals(player)) return;
		leaderOf.remove(player);
		Set<UUID> all = parties.get(leader);
		if (all == null) return;
		all.remove(player);
		tellAll(server, leader, name(server, player) + (quietly ? " logged out and left the party." : " left the party."));
	}

	private static String name(MinecraftServer server, UUID id) {
		ServerPlayer p = server.getPlayerList().getPlayer(id);
		return p == null ? "Someone" : p.getGameProfile().name();
	}

	// ---------------------------------------------------------------- what players can do (commands and the menu)

	/** Each returns what to tell the player: "✔ …" when it worked, else why not. */
	static String doCreate(ServerPlayer me) {
		if (inParty(me.getUUID())) return "You're in a party already (leave it first).";
		if (!here(me)) return ONLY_HERE;
		create(me.getUUID());
		return "✔ Your party is ready: invite others (up to " + MAX + " players). It stays until you disband it, log out or leave Flying Fair and the dungeons.";
	}

	static String doInvite(MinecraftServer server, ServerPlayer me, ServerPlayer them) {
		if (them == null) return "They're not online.";
		if (them == me) return "That's you.";
		if (!here(me)) return ONLY_HERE;
		if (!here(them)) return them.getGameProfile().name() + " isn't in Flying Fair or a dungeon.";
		if (!isLeader(me.getUUID())) return "Only the party leader invites.";
		if (inParty(them.getUUID())) return them.getGameProfile().name() + " is in a party already.";
		if (members(me.getUUID()).size() >= MAX) return "A party has at most " + MAX + " players.";
		// Inviting makes the party when there's none yet.
		create(me.getUUID());
		invites.put(them.getUUID(), me.getUUID());
		inviteUntil.put(them.getUUID(), System.currentTimeMillis() + 120_000);
		them.sendSystemMessage(Words.lit(me.getGameProfile().name() + " invites you to their party. ").withStyle(ChatFormatting.LIGHT_PURPLE)
				.append(Words.lit("[Join]").withStyle(st -> st.withColor(ChatFormatting.GREEN).withUnderlined(true)
						.withClickEvent(new ClickEvent.RunCommand("/party accept")).withHoverEvent(new HoverEvent.ShowText(Words.lit("/party accept"))))));
		return "✔ Invited " + them.getGameProfile().name() + " (two minutes to accept).";
	}

	static String doAccept(MinecraftServer server, ServerPlayer me) {
		UUID leader = invites.remove(me.getUUID());
		Long until = inviteUntil.remove(me.getUUID());
		if (leader == null || until == null || until < System.currentTimeMillis()) return "You have no invite (they last two minutes).";
		Set<UUID> all = parties.get(leader);
		if (all == null || server.getPlayerList().getPlayer(leader) == null) return "That party isn't there any more.";
		if (!here(me)) return ONLY_HERE;
		if (inParty(me.getUUID())) {
			if (isLeader(me.getUUID())) return "You lead a party: disband it first.";
			leave(server, me.getUUID(), false);
		}
		if (all.size() >= MAX) return "That party is full.";
		all.add(me.getUUID());
		leaderOf.put(me.getUUID(), leader);
		tellAll(server, leader, me.getGameProfile().name() + " joined (" + all.size() + "/" + MAX + ").");
		return "✔ You're in " + name(server, leader) + "'s party.";
	}

	static String doLeave(MinecraftServer server, ServerPlayer me) {
		if (!inParty(me.getUUID())) return "You're not in a party.";
		if (isLeader(me.getUUID())) return doDisband(server, me);
		leave(server, me.getUUID(), false);
		return "✔ You left the party.";
	}

	static String doDisband(MinecraftServer server, ServerPlayer me) {
		if (!inParty(me.getUUID()) || !isLeader(me.getUUID())) return "Only the party leader ends the party.";
		disband(server, me.getUUID(), me.getGameProfile().name() + " ended the party.");
		return "✔ The party is over.";
	}

	static String doKick(MinecraftServer server, ServerPlayer me, UUID them) {
		UUID leader = leaderOf.get(me.getUUID());
		if (leader == null || !leader.equals(me.getUUID())) return "Only the party leader removes players.";
		if (them == null || them.equals(me.getUUID()) || !me.getUUID().equals(leaderOf.get(them))) return "They're not in your party.";
		String name = name(server, them);
		leave(server, them, false);
		ServerPlayer p = server.getPlayerList().getPlayer(them);
		if (p != null) p.sendSystemMessage(Words.lit("You were removed from the party.").withStyle(ChatFormatting.GRAY));
		return "✔ " + name + " is out of the party.";
	}

	private static int reply(CommandContext<CommandSourceStack> ctx, String result) {
		if (result.startsWith("✔")) {
			ctx.getSource().sendSuccess(() -> Words.lit("[Party] " + result.substring(1).strip()).withStyle(ChatFormatting.LIGHT_PURPLE), false);
			return 1;
		}
		return fail(ctx, result);
	}

	private static void commands(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("party").requires(Everyone.unless("nicecontrolcenter.party.use"))
				.executes(ctx -> {
					menu(ctx.getSource().getPlayerOrException(), null);
					return 1;
				})
				.then(Commands.literal("list").executes(Parties::list))
				.then(Commands.literal("create").executes(ctx -> reply(ctx, doCreate(ctx.getSource().getPlayerOrException()))))
				.then(Commands.literal("invite").then(Commands.argument("player", EntityArgument.player())
						.executes(ctx -> reply(ctx, doInvite(ctx.getSource().getServer(), ctx.getSource().getPlayerOrException(), EntityArgument.getPlayer(ctx, "player"))))))
				.then(Commands.literal("accept").executes(ctx -> reply(ctx, doAccept(ctx.getSource().getServer(), ctx.getSource().getPlayerOrException()))))
				.then(Commands.literal("leave").executes(ctx -> reply(ctx, doLeave(ctx.getSource().getServer(), ctx.getSource().getPlayerOrException()))))
				.then(Commands.literal("disband").executes(ctx -> reply(ctx, doDisband(ctx.getSource().getServer(), ctx.getSource().getPlayerOrException()))))
				.then(Commands.literal("kick").then(Commands.argument("player", EntityArgument.player())
						.executes(ctx -> reply(ctx, doKick(ctx.getSource().getServer(), ctx.getSource().getPlayerOrException(), EntityArgument.getPlayer(ctx, "player").getUUID()))))));
	}

	// ---------------------------------------------------------------- the menu (/party)

	private static final int WIDTH = 150;
	private static final int TEXT_WIDTH = 300;

	private static ActionButton button(String label, String tooltip, String action, CompoundTag args) {
		return new ActionButton(new CommonButtonData(Component.literal(label), Optional.of(Component.literal(tooltip)), WIDTH),
				Optional.of(new CustomAll(Identifier.fromNamespaceAndPath("nicecontrolcenter", "party/" + action), Optional.of(args))));
	}

	private static DialogBody text(String s, ChatFormatting color) {
		return new PlainMessage(Component.literal(s).withStyle(color), TEXT_WIDTH);
	}

	/** The party menu: what they can do now, with a line on top after something happened. */
	public static void menu(ServerPlayer me, String notice) {
		MinecraftServer server = me.level().getServer();
		List<DialogBody> body = new ArrayList<>();
		List<Input> inputs = new ArrayList<>();
		List<ActionButton> buttons = new ArrayList<>();
		if (notice != null) {
			body.add(text(notice.startsWith("✔") ? notice : "✖ " + notice, notice.startsWith("✔") ? ChatFormatting.GREEN : ChatFormatting.RED));
			eu.explorerseden.nicecontrolcenter.core.Plop.play(me);
		}
		UUID id = me.getUUID();
		UUID invitedBy = invites.get(id);
		boolean invite = invitedBy != null && inviteUntil.getOrDefault(id, 0L) >= System.currentTimeMillis() && parties.containsKey(invitedBy);
		if (!inParty(id)) {
			body.add(text("You're not in a party. A party plays together: dungeons and wave fights start for all of it from the leader, and game entrances bring it along. Parties are for Flying Fair and the dungeons.", ChatFormatting.GRAY));
			if (invite) buttons.add(button("Join " + name(server, invitedBy), "Join " + name(server, invitedBy) + "'s party", "accept", new CompoundTag()));
			buttons.add(button("Create a party", "Make a party with you as its leader", "create", new CompoundTag()));
		} else {
			List<UUID> all = members(id);
			boolean lead = isLeader(id);
			StringBuilder who = new StringBuilder("Your party (" + all.size() + "/" + MAX + "): ");
			for (int i = 0; i < all.size(); i++) who.append(i == 0 ? "" : ", ").append(name(server, all.get(i))).append(i == 0 ? " (leader)" : "");
			body.add(text(who.toString(), ChatFormatting.LIGHT_PURPLE));
			if (lead) {
				// Players around who could join: in the game worlds and not in a party.
				List<SingleOptionInput.Entry> options = new ArrayList<>();
				for (ServerPlayer p : server.getPlayerList().getPlayers()) {
					if (p == me || inParty(p.getUUID()) || !here(p)) continue;
					options.add(new SingleOptionInput.Entry(p.getGameProfile().name(), Optional.empty(), options.isEmpty()));
					if (options.size() >= 40) break;
				}
				if (options.isEmpty()) body.add(text("Nobody to invite right now (players in Flying Fair or the dungeons who aren't in a party).", ChatFormatting.GRAY));
				else if (all.size() < MAX) {
					inputs.add(new Input("player", new SingleOptionInput(250, options, Component.literal("Invite"), true)));
					buttons.add(button("Invite", "Invite the player picked above (two minutes to accept)", "invite", new CompoundTag()));
				}
				for (UUID m : all) {
					if (m.equals(id)) continue;
					CompoundTag t = new CompoundTag();
					t.putString("who", m.toString());
					buttons.add(button("Remove " + name(server, m), "Take " + name(server, m) + " out of the party", "kick", t));
				}
				buttons.add(button("Disband", "End the party for everyone", "disband", new CompoundTag()));
			} else {
				buttons.add(button("Leave", "Leave the party", "leave", new CompoundTag()));
			}
		}
		body.add(text("Commands: /party create, /party invite <player>, /party accept, /party leave, /party disband, /party kick <player>, /party list.", ChatFormatting.DARK_GRAY));
		CommonDialogData common = new CommonDialogData(Component.literal("Party"), Optional.empty(), true, false, DialogAction.CLOSE, body, inputs);
		ActionButton close = new ActionButton(new CommonButtonData(Component.literal("Close"), WIDTH), Optional.empty());
		me.openDialog(Holder.direct(new MultiActionDialog(common, buttons, Optional.of(close), 2)));
	}

	/** A menu button (nicecontrolcenter:party/…): checked again here, nothing in it is trusted. */
	public static void handle(ServerPlayer me, String what, Optional<Tag> payload) {
		MinecraftServer server = me.level().getServer();
		CompoundTag in = payload.orElse(null) instanceof CompoundTag t ? t : new CompoundTag();
		String result = switch (what) {
			case "create" -> doCreate(me);
			case "accept" -> doAccept(server, me);
			case "invite" -> doInvite(server, me, server.getPlayerList().getPlayerByName(in.getStringOr("player", "")));
			case "leave" -> doLeave(server, me);
			case "disband" -> doDisband(server, me);
			case "kick" -> {
				UUID who;
				try {
					who = UUID.fromString(in.getStringOr("who", ""));
				} catch (IllegalArgumentException e) {
					who = null;
				}
				yield doKick(server, me, who);
			}
			default -> null;
		};
		menu(me, result);
	}

	private static int list(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer me = ctx.getSource().getPlayerOrException();
		MinecraftServer server = ctx.getSource().getServer();
		if (!inParty(me.getUUID())) {
			ctx.getSource().sendSuccess(() -> Words.lit("You're not in a party. /party create (or /party invite <player>) makes one, up to " + MAX + " players, in Flying Fair and the dungeons.").withStyle(ChatFormatting.GRAY), false);
			return 1;
		}
		List<String> names = new ArrayList<>();
		List<UUID> all = members(me.getUUID());
		for (int i = 0; i < all.size(); i++) names.add(name(server, all.get(i)) + (i == 0 ? " (leader)" : ""));
		ctx.getSource().sendSuccess(() -> Words.lit("Your party (" + all.size() + "/" + MAX + "): " + String.join(", ", names)).withStyle(ChatFormatting.LIGHT_PURPLE), false);
		return 1;
	}

	private static int fail(CommandContext<CommandSourceStack> ctx, String why) {
		ctx.getSource().sendFailure(Words.lit(why));
		return 0;
	}
}
