package eu.explorerseden.nicecontrolcenter.players;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.storage.LevelResource;

import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.Points;
import eu.explorerseden.nicecontrolcenter.command.Everyone;

/**
 * The points balances (see {@link Points}): given by Rewards rules and /points, taken by the reward shops and /points.
 * /points (balance and recent changes) and /points top for everyone; give, take and check for admins and command
 * blocks (take fails when they don't have enough, check succeeds when they have at least that many), so games built
 * by hand can pay out and charge points. Server thread.
 */
public final class PointsStore {
	public static final String PERMISSION = "nicecontrolcenter.points.admin";

	private static volatile Points.Settings settings = new Points.Settings().fill();
	private static long modified = -1;
	private static Points.Data data = new Points.Data();
	private static Path dataFile;
	private static boolean dirty;

	private PointsStore() {
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(Points.FILE);
	}

	public static Points.Settings settings() {
		return settings;
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((d, access, env) -> commands(d));
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			modified = -1;
			reload();
			dataFile = server.getWorldPath(LevelResource.ROOT).resolve(Points.DATA);
			data = Points.readData(dataFile);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> save());
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 100 == 0) reload();
			if (dirty && server.getTickCount() % 600 == 0) save();
		});
	}

	public static void reload() {
		long now;
		try {
			now = eu.explorerseden.nicecontrolcenter.core.FileWatch.modified(file());
		} catch (IOException e) {
			return;
		}
		if (now == modified) return;
		modified = now;
		settings = Points.read(file());
	}

	/** Saves the settings (in-game changes: NPC spots, items from the hand). */
	public static void saveSettings() {
		try {
			Points.write(file(), settings);
			modified = eu.explorerseden.nicecontrolcenter.core.FileWatch.modified(file());
		} catch (IOException e) {
			NiceControlCenter.LOGGER.warn("Points: could not save the reward shops: {}", e.getMessage());
		}
	}

	// ---------------------------------------------------------------- balances

	public static long balance(String uuid) {
		Points.Account a = data.accounts.get(uuid);
		return a == null ? 0 : a.balance;
	}

	/** Adds (or with a negative amount takes, never below 0) points; returns the new balance. */
	public static long add(String uuid, String name, long amount, String why) {
		Points.Account a = data.accounts.computeIfAbsent(uuid, k -> new Points.Account());
		if (name != null) a.name = name;
		long before = a.balance;
		a.balance = Math.max(0, Math.min(1_000_000_000_000L, a.balance + amount));
		long changed = a.balance - before;
		if (changed == 0) return a.balance;
		if (changed > 0) a.earned += changed;
		a.history.add(new Points.Change(System.currentTimeMillis(), changed, why == null || why.isBlank() ? "" : why.strip()));
		while (a.history.size() > Points.HISTORY) a.history.remove(0);
		dirty = true;
		NiceControlCenter.LOGGER.info("Points: {} {} {} ({}), now {}", a.name == null ? uuid : a.name, changed > 0 ? "got" : "lost",
				Points.amount(settings, Math.abs(changed)), why == null || why.isBlank() ? "no reason given" : why.strip(), a.balance);
		return a.balance;
	}

	/** Takes points when they have enough (false and nothing taken when they don't). */
	public static boolean take(String uuid, String name, long amount, String why) {
		if (amount < 0 || balance(uuid) < amount) return false;
		if (amount > 0) add(uuid, name, -amount, why);
		return true;
	}

	/** Times a player bought a shop entry, and everyone together. */
	public static int bought(String uuid, String entryId) {
		Points.Account a = data.accounts.get(uuid);
		return a == null ? 0 : a.bought.getOrDefault(entryId, 0);
	}

	public static int sold(String entryId) {
		return data.sold.getOrDefault(entryId, 0);
	}

	static void countPurchase(String uuid, String name, String entryId) {
		Points.Account a = data.accounts.computeIfAbsent(uuid, k -> new Points.Account());
		if (name != null) a.name = name;
		a.bought.merge(entryId, 1, Integer::sum);
		data.sold.merge(entryId, 1, Integer::sum);
		dirty = true;
	}

	// ---------------------------------------------------------------- for the panel and the website

	/** One player's balance and recent changes (newest first). */
	public static Map<String, Object> view(String uuid) {
		Map<String, Object> m = new LinkedHashMap<>();
		Points.Account a = data.accounts.get(uuid);
		m.put("name", settings.name);
		m.put("singular", Points.one(settings));
		m.put("abbreviation", settings.abbreviation);
		m.put("balance", a == null ? 0 : a.balance);
		m.put("earned", a == null ? 0 : a.earned);
		List<Points.Change> h = a == null ? List.of() : new ArrayList<>(a.history);
		java.util.Collections.reverse(h);
		m.put("history", h);
		return m;
	}

	/** Everyone's balances (highest first) and what the shops sold. */
	public static Map<String, Object> all() {
		Map<String, Object> m = new LinkedHashMap<>();
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Map.Entry<String, Points.Account> e : data.accounts.entrySet()) {
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("uuid", e.getKey());
			r.put("name", e.getValue().name);
			r.put("balance", e.getValue().balance);
			r.put("earned", e.getValue().earned);
			List<Points.Change> h = new ArrayList<>(e.getValue().history);
			java.util.Collections.reverse(h);
			r.put("history", h.size() > 10 ? h.subList(0, 10) : h);
			rows.add(r);
		}
		rows.sort((x, y) -> Long.compare((long) y.get("balance"), (long) x.get("balance")));
		m.put("accounts", rows);
		m.put("sold", data.sold);
		return m;
	}

	/** From the panel: give or take points (amount negative takes); by uuid, or a name we know. */
	public static Map<String, Object> adjust(MinecraftServer server, String who, long amount, String why) {
		String uuid = null;
		String name = null;
		ServerPlayer online = server.getPlayerList().getPlayerByName(who);
		if (online != null) {
			uuid = online.getStringUUID();
			name = online.getGameProfile().name();
		} else {
			for (Map.Entry<String, Points.Account> e : data.accounts.entrySet()) {
				if (e.getKey().equalsIgnoreCase(who) || who.equalsIgnoreCase(e.getValue().name)) {
					uuid = e.getKey();
					name = e.getValue().name;
				}
			}
			if (uuid == null) {
				var cached = server.services().nameToIdCache().get(who);
				if (cached.isPresent()) {
					uuid = cached.get().id().toString();
					name = cached.get().name();
				}
			}
		}
		if (uuid == null) return Map.of("error", "No player called " + who + " has been here.");
		if (amount == 0) return Map.of("error", "How many?");
		long now = add(uuid, name, amount, why);
		if (online != null) online.sendSystemMessage(Component.literal((amount > 0 ? "+" : "") + Points.amount(settings, amount) + (why == null || why.isBlank() ? "" : " · " + why.strip())).withStyle(amount > 0 ? ChatFormatting.GOLD : ChatFormatting.YELLOW));
		return Map.of("ok", true, "balance", now, "name", name == null ? who : name);
	}

	// ---------------------------------------------------------------- commands

	private static void commands(CommandDispatcher<CommandSourceStack> d) {
		var admin = Permissions.require(PERMISSION, PermissionLevel.GAMEMASTERS);
		d.register(Commands.literal("points").requires(Everyone.unless("nicecontrolcenter.points.use"))
				.executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					Points.Account a = data.accounts.get(p.getStringUUID());
					StringBuilder b = new StringBuilder("You have " + Points.amount(settings, a == null ? 0 : a.balance) + ".");
					if (a != null && !a.history.isEmpty()) {
						b.append(" Lately:");
						List<Points.Change> h = a.history.subList(Math.max(0, a.history.size() - 5), a.history.size());
						for (int i = h.size() - 1; i >= 0; i--) b.append("\n• ").append(h.get(i).amount > 0 ? "+" : "").append(h.get(i).amount).append(h.get(i).why.isEmpty() ? "" : " " + h.get(i).why);
					}
					if (!settings.shops.isEmpty()) b.append("\nSpend them at the reward shops.");
					ctx.getSource().sendSuccess(() -> Component.literal(b.toString()).withStyle(ChatFormatting.GOLD), false);
					return (int) Math.min(Integer.MAX_VALUE, a == null ? 0 : a.balance);
				})
				.then(Commands.literal("top").executes(ctx -> {
					List<Points.Account> list = new ArrayList<>(data.accounts.values());
					list.sort((x, y) -> Long.compare(y.balance, x.balance));
					StringBuilder b = new StringBuilder(settings.name + ":");
					int i = 1;
					for (Points.Account a : list.subList(0, Math.min(10, list.size()))) b.append("\n").append(i++).append(". ").append(a.name).append(" · ").append(String.format(Locale.ROOT, "%,d", a.balance));
					if (list.isEmpty()) b.append(" nobody has any yet.");
					ctx.getSource().sendSuccess(() -> Component.literal(b.toString()).withStyle(ChatFormatting.GOLD), false);
					return 1;
				}))
				.then(Commands.literal("give").requires(admin).then(Commands.argument("players", GameProfileArgument.gameProfile())
						.then(Commands.argument("amount", IntegerArgumentType.integer(1, 1_000_000_000)).executes(ctx -> give(ctx, ""))
								.then(Commands.argument("reason", StringArgumentType.greedyString()).executes(ctx -> give(ctx, StringArgumentType.getString(ctx, "reason")))))))
				.then(Commands.literal("take").requires(admin).then(Commands.argument("players", GameProfileArgument.gameProfile())
						.then(Commands.argument("amount", IntegerArgumentType.integer(1, 1_000_000_000)).executes(ctx -> take(ctx, ""))
								.then(Commands.argument("reason", StringArgumentType.greedyString()).executes(ctx -> take(ctx, StringArgumentType.getString(ctx, "reason")))))))
				.then(Commands.literal("check").requires(admin).then(Commands.argument("players", GameProfileArgument.gameProfile())
						.then(Commands.argument("amount", IntegerArgumentType.integer(0, 1_000_000_000)).executes(PointsStore::check)))));
	}

	private static String why(CommandContext<CommandSourceStack> ctx, String reason) {
		return reason.isBlank() ? "from " + ctx.getSource().getTextName() : reason;
	}

	private static int give(CommandContext<CommandSourceStack> ctx, String reason) throws CommandSyntaxException {
		int amount = IntegerArgumentType.getInteger(ctx, "amount");
		int done = 0;
		for (NameAndId w : profiles(ctx)) {
			long now = add(w.id().toString(), w.name(), amount, why(ctx, reason));
			ServerPlayer online = ctx.getSource().getServer().getPlayerList().getPlayer(w.id());
			if (online != null) online.sendOverlayMessage(Component.literal("+" + Points.shortAmount(settings, amount) + (reason.isBlank() ? "" : " · " + reason) + " (" + String.format(Locale.ROOT, "%,d", now) + ")").withStyle(ChatFormatting.GOLD));
			done++;
			ctx.getSource().sendSuccess(() -> Component.literal(w.name() + ": +" + Points.amount(settings, amount) + ", now " + String.format(Locale.ROOT, "%,d", now) + "."), false);
		}
		return done;
	}

	/** Each player pays if they can; the command fails (for conditional command blocks) when nobody could. */
	private static int take(CommandContext<CommandSourceStack> ctx, String reason) throws CommandSyntaxException {
		int amount = IntegerArgumentType.getInteger(ctx, "amount");
		int done = 0;
		for (NameAndId w : profiles(ctx)) {
			String id = w.id().toString();
			if (!take(id, w.name(), amount, why(ctx, reason))) {
				long have = balance(id);
				ctx.getSource().sendFailure(Component.literal(w.name() + " has only " + Points.amount(settings, have) + "."));
				continue;
			}
			done++;
			ServerPlayer online = ctx.getSource().getServer().getPlayerList().getPlayer(w.id());
			if (online != null) online.sendOverlayMessage(Component.literal("-" + Points.shortAmount(settings, amount) + (reason.isBlank() ? "" : " · " + reason) + " (" + String.format(Locale.ROOT, "%,d", balance(id)) + " left)").withStyle(ChatFormatting.YELLOW));
			ctx.getSource().sendSuccess(() -> Component.literal(w.name() + ": -" + Points.amount(settings, amount) + ", " + String.format(Locale.ROOT, "%,d", balance(id)) + " left."), false);
		}
		// A real failure (not just 0), so execute store success and conditional command blocks see it.
		if (done == 0) throw new SimpleCommandExceptionType(Component.literal("Nobody had " + Points.amount(settings, amount) + " to pay.")).create();
		return done;
	}

	/** Succeeds (for execute if / conditional command blocks) when every player named has at least that many. */
	private static int check(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		int amount = IntegerArgumentType.getInteger(ctx, "amount");
		Collection<NameAndId> who = profiles(ctx);
		for (NameAndId w : who) {
			if (balance(w.id().toString()) < amount) {
				throw new SimpleCommandExceptionType(Component.literal(w.name() + " has " + Points.amount(settings, balance(w.id().toString())) + ", fewer than " + amount + ".")).create();
			}
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Yes: at least " + Points.amount(settings, amount) + "."), false);
		return who.size();
	}

	private static Collection<NameAndId> profiles(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return GameProfileArgument.getGameProfiles(ctx, "players");
	}

	// ---------------------------------------------------------------- storage

	private static void save() {
		dirty = false;
		if (dataFile == null) return;
		try {
			Path tmp = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
			Files.writeString(tmp, Json.GSON.toJson(data), StandardCharsets.UTF_8);
			Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			NiceControlCenter.LOGGER.warn("Points: could not save the balances: {}", e.getMessage());
		}
	}

	/** Forces a save soon after a purchase (so a crash doesn't hand the points back). */
	static void saveSoon() {
		save();
	}

	static UUID uuid(String s) {
		try {
			return UUID.fromString(s);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
