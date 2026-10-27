package eu.explorerseden.nicecontrolcenter.rewards;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;

import eu.explorerseden.nicecontrolcenter.Pets;
import eu.explorerseden.nicecontrolcenter.Rewards;
import eu.explorerseden.nicecontrolcenter.claims.ClaimStore;
import eu.explorerseden.nicecontrolcenter.command.Everyone;
import eu.explorerseden.nicecontrolcenter.market.MarketStore;
import eu.explorerseden.nicecontrolcenter.pets.PetStore;
import eu.explorerseden.nicecontrolcenter.players.Perks;
import eu.explorerseden.nicecontrolcenter.players.TempFly;

/**
 * Hands out non-item rewards, for /reward (admins) and rules in Rewards. Every give returns a short
 * line for the player ("+30 min flight"), or throws Refused with why it didn't work.
 */
public final class RewardActions {
	public static final String PERMISSION = "nicecontrolcenter.reward";

	public static final class Refused extends RuntimeException {
		Refused(String why) {
			super(why, null, false, false);
		}
	}

	private RewardActions() {
	}

	// ---------------------------------------------------------------- giving

	/** Gives one action to a player (online may be null: then what needs them waits or works offline). */
	public static String give(MinecraftServer server, UUID uuid, String name, ServerPlayer online, Rewards.Action a, String by) {
		String problem = Rewards.validate(a, RewardsControl.settings());
		if (problem != null) throw new Refused(problem);
		String id = uuid.toString();
		long ms = a.seconds * 1000L;
		switch (a.type) {
			case "fly" -> {
				TempFly.add(uuid, online, ms);
				return "+" + Perks.human(ms) + " flight in your claims";
			}
			case "claimblocks" -> {
				var data = ClaimStore.data();
				var pb = data.player(id);
				data.setBonus(id, name, (pb == null ? 0 : pb.bonus) + a.amount);
				ClaimStore.touched();
				return (a.amount > 0 ? "+" : "") + String.format(Locale.ROOT, "%,d", a.amount) + " claim blocks";
			}
			case "pet" -> {
				Pets.Pet pet = pet(a.target);
				if (pet == null) throw new Refused("there's no pet called \"" + a.target + "\".");
				String err = PetStore.adminAction(server, id, name, "give", pet.id, null, by);
				if (err != null) throw new Refused(name + " has " + pet.name + " already.");
				return "the pet " + pet.name;
			}
			case "petslots" -> {
				Pets.Owner o = PetStore.data().owners.computeIfAbsent(id, k -> new Pets.Owner());
				if (o.name == null) o.name = name;
				if (o.slots >= 2) throw new Refused(name + " can already have 3 pets out.");
				int before = o.slots;
				o.slots = Math.min(2, o.slots + a.amount);
				PetStore.changed();
				return "+" + (o.slots - before) + " pet out at once (" + (o.slots + 1) + " in all)";
			}
			case "listings" -> {
				MarketStore.data().addExtraListings(id, a.amount);
				MarketStore.saveNow();
				return "+" + a.amount + " market listing slot" + (a.amount == 1 ? "" : "s");
			}
			case "coupons" -> {
				int value = (int) Math.round(a.level);
				Perks.addCoupons(id, name, value, a.amount);
				return a.amount + " × " + Perks.couponName(value) + (a.amount == 1 ? "" : "s") + " for the server shop";
			}
			case "joinmessage" -> {
				long left = Perks.add(id, name, Perks.Kind.JOINMESSAGE, 0, a.seconds == 0 ? -1 : ms);
				return "your own join message (" + Perks.human(left) + "; set it with /joinmessage <text>)";
			}
			default -> {
				Perks.Kind k = Perks.Kind.of(a.type);
				if (k == null) throw new Refused("unknown reward \"" + a.type + "\".");
				int level = k == Perks.Kind.HASTE ? (int) Math.round(a.level) : k == Perks.Kind.SPOTLIGHT || k == Perks.Kind.TOOLCARE ? 0 : (int) Math.round(a.level * 10);
				long left = Perks.add(id, name, k, level, ms);
				String lv = Perks.levelText(k, level);
				return k.label + (lv.isEmpty() ? "" : " " + lv) + " for " + Perks.human(ms) + (left > ms ? " (" + Perks.human(left) + " in all)" : "");
			}
		}
	}

	/** Takes a reward kind away; false when they had none. */
	public static boolean take(UUID uuid, ServerPlayer online, String type) {
		String id = uuid.toString();
		return switch (type) {
			case "fly" -> TempFly.take(uuid, online);
			case "coupons" -> Perks.takeCoupons(id);
			case "listings" -> {
				boolean had = MarketStore.data().extraListings(id) > 0;
				MarketStore.data().addExtraListings(id, -MarketStore.data().extraListings(id));
				MarketStore.saveNow();
				yield had;
			}
			case "petslots" -> {
				Pets.Owner o = PetStore.data().owners.get(id);
				if (o == null || o.slots == 0) yield false;
				o.slots = 0;
				o.alsoOut.clear();
				PetStore.changed();
				yield true;
			}
			default -> {
				Perks.Kind k = Perks.Kind.of(type);
				yield k != null && Perks.take(id, k);
			}
		};
	}

	private static Pets.Pet pet(String nameOrId) {
		Pets.Settings s = PetStore.settings();
		return s.pets.stream().filter(p -> p.name.equalsIgnoreCase(nameOrId)).findFirst().orElse(s.pet(nameOrId));
	}

	// ---------------------------------------------------------------- commands

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> register(dispatcher));
	}

	private static void register(CommandDispatcher<CommandSourceStack> d) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("reward").requires(Permissions.require(PERMISSION, PermissionLevel.GAMEMASTERS));
		var players = (java.util.function.Supplier<com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, ?>>) () -> Commands.argument("players", GameProfileArgument.gameProfile());
		// Timed with a level/multiplier.
		for (String type : List.of("xp", "blockboost", "bondboost")) {
			root.then(Commands.literal(type).then(players.get()
					.then(Commands.literal("off").executes(ctx -> off(ctx, type)))
					.then(Commands.argument("multiplier", DoubleArgumentType.doubleArg(1.1, 5)).then(Commands.argument("time", TimeArgument.time(1))
							.executes(ctx -> run(ctx, type, a -> {
								a.level = DoubleArgumentType.getDouble(ctx, "multiplier");
								a.seconds = ctx.getArgument("time", Integer.class) / 20L;
							}))))));
		}
		root.then(Commands.literal("haste").then(players.get()
				.then(Commands.literal("off").executes(ctx -> off(ctx, "haste")))
				.then(Commands.argument("level", IntegerArgumentType.integer(1, 3)).then(Commands.argument("time", TimeArgument.time(1))
						.executes(ctx -> run(ctx, "haste", a -> {
							a.level = IntegerArgumentType.getInteger(ctx, "level");
							a.seconds = ctx.getArgument("time", Integer.class) / 20L;
						}))))));
		// Timed without a level.
		for (String type : List.of("fly", "spotlight", "toolcare")) {
			root.then(Commands.literal(type).then(players.get()
					.then(Commands.literal("off").executes(ctx -> off(ctx, type)))
					.then(Commands.argument("time", TimeArgument.time(1)).executes(ctx -> run(ctx, type, a -> a.seconds = ctx.getArgument("time", Integer.class) / 20L)))));
		}
		root.then(Commands.literal("joinmessage").then(players.get()
				.then(Commands.literal("off").executes(ctx -> off(ctx, "joinmessage")))
				.then(Commands.literal("forever").executes(ctx -> run(ctx, "joinmessage", a -> a.seconds = 0)))
				.then(Commands.argument("time", TimeArgument.time(1)).executes(ctx -> run(ctx, "joinmessage", a -> a.seconds = ctx.getArgument("time", Integer.class) / 20L)))));
		// Amounts.
		root.then(Commands.literal("claimblocks").then(players.get()
				.then(Commands.argument("amount", IntegerArgumentType.integer(-1_000_000, 1_000_000))
						.executes(ctx -> run(ctx, "claimblocks", a -> a.amount = IntegerArgumentType.getInteger(ctx, "amount"))))));
		root.then(Commands.literal("listings").then(players.get()
				.then(Commands.literal("off").executes(ctx -> off(ctx, "listings")))
				.then(Commands.argument("amount", IntegerArgumentType.integer(1, 100))
						.executes(ctx -> run(ctx, "listings", a -> a.amount = IntegerArgumentType.getInteger(ctx, "amount"))))));
		root.then(Commands.literal("petslots").then(players.get()
				.then(Commands.literal("off").executes(ctx -> off(ctx, "petslots")))
				.then(Commands.argument("amount", IntegerArgumentType.integer(1, 2))
						.executes(ctx -> run(ctx, "petslots", a -> a.amount = IntegerArgumentType.getInteger(ctx, "amount"))))));
		root.then(Commands.literal("coupons").then(players.get()
				.then(Commands.literal("off").executes(ctx -> off(ctx, "coupons")))
				.then(Commands.argument("worth", IntegerArgumentType.integer(1, 10_000)).then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
						.executes(ctx -> run(ctx, "coupons", a -> {
							a.level = IntegerArgumentType.getInteger(ctx, "worth");
							a.amount = IntegerArgumentType.getInteger(ctx, "count");
						}))))));
		root.then(Commands.literal("pet").then(players.get()
				.then(Commands.argument("pet", StringArgumentType.string())
						.suggests((ctx, b) -> SharedSuggestionProvider.suggest(PetStore.settings().pets.stream().map(p -> p.name.contains(" ") ? "\"" + p.name + "\"" : p.name), b))
						.executes(ctx -> run(ctx, "pet", a -> a.target = StringArgumentType.getString(ctx, "pet")))
						.then(Commands.literal("remove").executes(RewardActions::takePet)))));
		// Rules with the trigger "Given with /reward give", by name.
		root.then(Commands.literal("give").then(players.get()
				.then(Commands.argument("rule", StringArgumentType.greedyString())
						.suggests((ctx, b) -> SharedSuggestionProvider.suggest(RewardsControl.manualRules().stream().map(r -> r.name), b))
						.executes(ctx -> {
							String rule = StringArgumentType.getString(ctx, "rule");
							MinecraftServer server = ctx.getSource().getServer();
							int done = 0;
							for (NameAndId w : GameProfileArgument.getGameProfiles(ctx, "players")) {
								try {
									String what = RewardsControl.giveManual(server, w.id(), server.getPlayerList().getPlayer(w.id()), rule);
									done++;
									ctx.getSource().sendSuccess(() -> Component.literal(w.name() + ": " + what + "."), true);
								} catch (Refused r) {
									ctx.getSource().sendFailure(Component.literal(r.getMessage()));
									return 0;
								}
							}
							return done;
						}))));
		root.then(Commands.literal("info").then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(ctx -> {
			for (NameAndId who : GameProfileArgument.getGameProfiles(ctx, "player")) {
				List<String> lines = summary(who.id());
				ctx.getSource().sendSuccess(() -> Component.literal(who.name() + ": " + (lines.isEmpty() ? "no rewards." : String.join(" · ", lines))), false);
			}
			return 1;
		})));
		d.register(root);

		// The market's mailbox, also where game claims and others leave items.
		d.register(Commands.literal("inbox").requires(Everyone.unless("nicecontrolcenter.inbox.use")).executes(ctx -> {
			eu.explorerseden.nicecontrolcenter.market.MarketDialogs.openInbox(ctx.getSource().getPlayerOrException());
			return 1;
		}));
		d.register(Commands.literal("perks").requires(Everyone.unless("nicecontrolcenter.perks.use")).executes(ctx -> {
			ServerPlayer p = ctx.getSource().getPlayerOrException();
			List<String> lines = summary(p.getUUID());
			p.sendSystemMessage(Component.literal(lines.isEmpty() ? "You have no perks right now. They come as rewards." : "Your perks:\n• " + String.join("\n• ", lines))
					.withStyle(lines.isEmpty() ? ChatFormatting.GRAY : ChatFormatting.GOLD));
			return 1;
		}));
		d.register(Commands.literal("joinmessage").requires(Everyone.unless("nicecontrolcenter.joinmessage.use"))
				.then(Commands.literal("off").executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					Perks.setJoinText(p.getStringUUID(), p.getPlainTextName(), null);
					ctx.getSource().sendSuccess(() -> Component.literal("Your join message is the normal one again."), false);
					return 1;
				}))
				.then(Commands.argument("text", StringArgumentType.greedyString()).executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					if (!Perks.has(p.getStringUUID(), Perks.Kind.JOINMESSAGE)) {
						ctx.getSource().sendFailure(Component.literal("An own join message is a reward; you don't have it right now."));
						return 0;
					}
					String text = StringArgumentType.getString(ctx, "text").strip();
					if (text.length() > 80) {
						ctx.getSource().sendFailure(Component.literal("Up to 80 characters, please."));
						return 0;
					}
					if (!text.contains("{player}") && !text.toLowerCase(Locale.ROOT).contains(p.getPlainTextName().toLowerCase(Locale.ROOT))) text = "{player} " + text;
					Perks.setJoinText(p.getStringUUID(), p.getPlainTextName(), text);
					String shown = text.replace("{player}", p.getPlainTextName());
					ctx.getSource().sendSuccess(() -> Component.literal("Your join message: ").withStyle(ChatFormatting.GRAY).append(Component.literal(shown).withStyle(ChatFormatting.YELLOW)), false);
					return 1;
				})));
	}

	private interface Fill {
		void apply(Rewards.Action a) throws CommandSyntaxException;
	}

	private static int run(CommandContext<CommandSourceStack> ctx, String type, Fill fill) throws CommandSyntaxException {
		Rewards.Action a = new Rewards.Action();
		a.type = type;
		fill.apply(a);
		Collection<NameAndId> who = GameProfileArgument.getGameProfiles(ctx, "players");
		MinecraftServer server = ctx.getSource().getServer();
		int done = 0;
		for (NameAndId w : who) {
			ServerPlayer online = server.getPlayerList().getPlayer(w.id());
			try {
				String line = give(server, w.id(), w.name(), online, a, ctx.getSource().getTextName());
				done++;
				if (online != null && !type.equals("fly") && !type.equals("pet")) online.sendSystemMessage(Component.literal("Reward: " + line).withStyle(ChatFormatting.GOLD));
				ctx.getSource().sendSuccess(() -> Component.literal(w.name() + ": " + line), true);
			} catch (Refused r) {
				ctx.getSource().sendFailure(Component.literal(w.name() + ": " + r.getMessage()));
			}
		}
		return done;
	}

	private static int off(CommandContext<CommandSourceStack> ctx, String type) throws CommandSyntaxException {
		MinecraftServer server = ctx.getSource().getServer();
		int done = 0;
		for (NameAndId w : GameProfileArgument.getGameProfiles(ctx, "players")) {
			boolean had = take(w.id(), server.getPlayerList().getPlayer(w.id()), type);
			if (had) done++;
			ctx.getSource().sendSuccess(() -> Component.literal(w.name() + (had ? ": taken away." : ": had none.")), true);
		}
		return done;
	}

	private static int takePet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Pets.Pet pet = pet(StringArgumentType.getString(ctx, "pet"));
		if (pet == null) {
			ctx.getSource().sendFailure(Component.literal("There's no such pet."));
			return 0;
		}
		int done = 0;
		for (NameAndId w : GameProfileArgument.getGameProfiles(ctx, "players")) {
			String err = PetStore.adminAction(ctx.getSource().getServer(), w.id().toString(), w.name(), "take", pet.id, null, ctx.getSource().getTextName());
			if (err == null) done++;
			ctx.getSource().sendSuccess(() -> Component.literal(w.name() + (err == null ? ": " + pet.name + " taken away." : ": doesn't have " + pet.name + ".")), true);
		}
		return done;
	}

	/** What a player has, for /perks, /reward info and the panel. */
	public static List<String> summary(UUID uuid) {
		String id = uuid.toString();
		List<String> out = new ArrayList<>();
		long fly = TempFly.secondsLeft(uuid);
		if (fly > 0) out.add("Flight in your claims: " + Perks.human(fly * 1000) + " (/fly)");
		Perks.Holder h = Perks.holder(id);
		if (h != null) {
			h.perks.forEach((k, p) -> {
				Perks.Kind kind = Perks.Kind.of(k);
				if (kind == null || (p.left <= 0 && p.left != -1)) return;
				String lv = Perks.levelText(kind, p.level);
				out.add(kind.label + (lv.isEmpty() ? "" : " " + lv) + ": " + Perks.human(p.left));
			});
			h.coupons.forEach((value, n) -> out.add(n + " × " + Perks.couponName(value) + (n == 1 ? "" : "s")));
		}
		int extra = MarketStore.data().extraListings(id);
		if (extra > 0) out.add("+" + extra + " market listing slots");
		Pets.Owner o = PetStore.data().owners.get(id);
		if (o != null && o.slots > 0) out.add((o.slots + 1) + " pets out at once");
		return out;
	}
}
