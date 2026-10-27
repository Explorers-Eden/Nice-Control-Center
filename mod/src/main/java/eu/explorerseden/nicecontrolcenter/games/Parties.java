package eu.explorerseden.nicecontrolcenter.games;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
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

import eu.explorerseden.nicecontrolcenter.command.Everyone;

/**
 * /party: up to four players (Dungeons.Settings.maxParty) who go into dungeons together. The first one is the
 * leader, who starts runs from a pad. Kept in memory only; logging out leaves the party.
 */
public final class Parties {
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
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> leave(server, handler.player.getUUID(), true));
	}

	/** The player's party with them in it (leader first); just them without a party. */
	public static List<UUID> members(UUID player) {
		UUID leader = leaderOf.get(player);
		return leader == null ? List.of(player) : List.copyOf(parties.get(leader));
	}

	public static boolean isLeader(UUID player) {
		UUID leader = leaderOf.get(player);
		return leader == null || leader.equals(player);
	}

	private static int max() {
		return DungeonRuntime.settings().maxParty;
	}

	private static void tell(MinecraftServer server, UUID who, Component text) {
		ServerPlayer p = server.getPlayerList().getPlayer(who);
		if (p != null) p.sendSystemMessage(text);
	}

	private static void tellAll(MinecraftServer server, UUID leader, String text) {
		Set<UUID> all = parties.get(leader);
		if (all == null) return;
		for (UUID id : all) tell(server, id, Component.literal("[Party] " + text).withStyle(ChatFormatting.LIGHT_PURPLE));
	}

	private static void leave(MinecraftServer server, UUID player, boolean quietly) {
		invites.remove(player);
		UUID leader = leaderOf.remove(player);
		if (leader == null) return;
		Set<UUID> all = parties.get(leader);
		if (all == null) return;
		all.remove(player);
		String name = name(server, player);
		if (all.size() <= 1) {
			// One left: no party any more.
			for (UUID id : all) {
				leaderOf.remove(id);
				tell(server, id, Component.literal("[Party] " + name + " left; the party is over.").withStyle(ChatFormatting.GRAY));
			}
			parties.remove(leader);
			return;
		}
		if (leader.equals(player)) {
			// The next one leads.
			UUID next = all.iterator().next();
			parties.remove(leader);
			parties.put(next, all);
			for (UUID id : all) leaderOf.put(id, next);
			tellAll(server, next, name + " left; " + name(server, next) + " leads now.");
		} else {
			tellAll(server, leader, name + (quietly ? " logged out and left the party." : " left the party."));
		}
	}

	private static String name(MinecraftServer server, UUID id) {
		ServerPlayer p = server.getPlayerList().getPlayer(id);
		return p == null ? "Someone" : p.getGameProfile().name();
	}

	private static void commands(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("party").requires(Everyone.unless("nicecontrolcenter.party.use"))
				.executes(Parties::list)
				.then(Commands.literal("list").executes(Parties::list))
				.then(Commands.literal("invite").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
					ServerPlayer me = ctx.getSource().getPlayerOrException();
					ServerPlayer them = EntityArgument.getPlayer(ctx, "player");
					MinecraftServer server = ctx.getSource().getServer();
					if (them == me) return fail(ctx, "That's you.");
					if (!isLeader(me.getUUID())) return fail(ctx, "Only the party leader invites.");
					if (leaderOf.containsKey(them.getUUID())) return fail(ctx, them.getGameProfile().name() + " is in a party already.");
					if (members(me.getUUID()).size() >= max()) return fail(ctx, "A party has at most " + max() + " players.");
					invites.put(them.getUUID(), me.getUUID());
					inviteUntil.put(them.getUUID(), System.currentTimeMillis() + 120_000);
					them.sendSystemMessage(Component.literal(me.getGameProfile().name() + " invites you to their dungeon party. ").withStyle(ChatFormatting.LIGHT_PURPLE)
							.append(Component.literal("[Join]").withStyle(s -> s.withColor(ChatFormatting.GREEN).withUnderlined(true)
									.withClickEvent(new ClickEvent.RunCommand("/party accept")).withHoverEvent(new HoverEvent.ShowText(Component.literal("/party accept"))))));
					ctx.getSource().sendSuccess(() -> Component.literal("Invited " + them.getGameProfile().name() + " (two minutes to accept).").withStyle(ChatFormatting.LIGHT_PURPLE), false);
					return 1;
				})))
				.then(Commands.literal("accept").executes(ctx -> {
					ServerPlayer me = ctx.getSource().getPlayerOrException();
					MinecraftServer server = ctx.getSource().getServer();
					UUID leader = invites.remove(me.getUUID());
					Long until = inviteUntil.remove(me.getUUID());
					if (leader == null || until == null || until < System.currentTimeMillis()) return fail(ctx, "You have no invite (they last two minutes).");
					if (server.getPlayerList().getPlayer(leader) == null) return fail(ctx, "They're not online any more.");
					if (leaderOf.containsKey(me.getUUID())) leave(server, me.getUUID(), false);
					// The inviter may have joined someone else meanwhile: their leader leads.
					UUID real = leaderOf.getOrDefault(leader, leader);
					Set<UUID> all = parties.computeIfAbsent(real, k -> new LinkedHashSet<>(List.of(k)));
					if (all.size() >= max()) return fail(ctx, "That party is full.");
					leaderOf.put(real, real);
					all.add(me.getUUID());
					leaderOf.put(me.getUUID(), real);
					tellAll(server, real, me.getGameProfile().name() + " joined (" + all.size() + "/" + max() + ").");
					return 1;
				}))
				.then(Commands.literal("leave").executes(ctx -> {
					ServerPlayer me = ctx.getSource().getPlayerOrException();
					if (!leaderOf.containsKey(me.getUUID())) return fail(ctx, "You're not in a party.");
					leave(ctx.getSource().getServer(), me.getUUID(), false);
					ctx.getSource().sendSuccess(() -> Component.literal("You left the party."), false);
					return 1;
				}))
				.then(Commands.literal("kick").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
					ServerPlayer me = ctx.getSource().getPlayerOrException();
					ServerPlayer them = EntityArgument.getPlayer(ctx, "player");
					UUID leader = leaderOf.get(me.getUUID());
					if (leader == null || !leader.equals(me.getUUID())) return fail(ctx, "Only the party leader kicks.");
					if (!me.getUUID().equals(leaderOf.get(them.getUUID())) || them == me) return fail(ctx, "They're not in your party.");
					leave(ctx.getSource().getServer(), them.getUUID(), false);
					them.sendSystemMessage(Component.literal("You were removed from the party.").withStyle(ChatFormatting.GRAY));
					return 1;
				}))));
	}

	private static int list(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer me = ctx.getSource().getPlayerOrException();
		MinecraftServer server = ctx.getSource().getServer();
		if (!leaderOf.containsKey(me.getUUID())) {
			ctx.getSource().sendSuccess(() -> Component.literal("You're not in a party. /party invite <player> makes one (up to " + max() + " players).").withStyle(ChatFormatting.GRAY), false);
			return 1;
		}
		List<String> names = new ArrayList<>();
		List<UUID> all = members(me.getUUID());
		for (int i = 0; i < all.size(); i++) names.add(name(server, all.get(i)) + (i == 0 ? " (leader)" : ""));
		ctx.getSource().sendSuccess(() -> Component.literal("Your party (" + all.size() + "/" + max() + "): " + String.join(", ", names)).withStyle(ChatFormatting.LIGHT_PURPLE), false);
		return 1;
	}

	private static int fail(CommandContext<CommandSourceStack> ctx, String why) {
		ctx.getSource().sendFailure(Component.literal(why));
		return 0;
	}
}
