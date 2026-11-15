package eu.explorerseden.nicecontrolcenter.games;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.core.AsyncFiles;
import eu.explorerseden.nicecontrolcenter.rewards.RewardsControl;

/**
 * Weekly leaderboards: each game, dungeon and Rogue Run keeps this week's best per player next to its all-time board.
 * When the week turns (Monday 00:00 server time, checked every minute and after a restart), the top 10 of each board
 * fire the Rewards trigger "weeklyPlace" (players who are offline get it when they next join) and last week's boards
 * are kept for the panel and the website. Kept in the world folder.
 */
public final class Weekly {
	/** A player's best this week: the score, a tie-breaker (lower is better: a time), when, and their name. */
	public static final class Entry {
		public String name = "";
		public long best;
		public long tie;
		public long at;
	}

	/** One board: what it is ("game", "dungeon", "rogue"), its name (what Rewards rules match), and the order. */
	public static final class Board {
		public String kind = "game";
		public String name = "";
		public boolean higher = true;
		/** For showing: "jumps", "time", "waves", "rooms"… */
		public String unit = "";
		public Map<String, Entry> players = new LinkedHashMap<>();

		/** The top {@code n}, best first. */
		public List<Map.Entry<String, Entry>> top(int n) {
			List<Map.Entry<String, Entry>> l = new ArrayList<>(players.entrySet());
			l.sort((x, y) -> {
				int c = higher ? Long.compare(y.getValue().best, x.getValue().best) : Long.compare(x.getValue().best, y.getValue().best);
				if (c == 0) c = Long.compare(x.getValue().tie, y.getValue().tie);
				return c != 0 ? c : Long.compare(x.getValue().at, y.getValue().at);
			});
			return l.size() > n ? l.subList(0, n) : l;
		}
	}

	public static final class Data {
		/** "2026-W46". */
		public String week = "";
		/** Board key ("game:<id>", "dungeon:<id>", "rogue:<id>") → this week. */
		public Map<String, Board> boards = new LinkedHashMap<>();
		/** Last week's boards (top 10 each), for the panel and the website. */
		public String lastWeek = "";
		public Map<String, Board> last = new LinkedHashMap<>();
		/** Places for players who were offline at the turn: uuid → [board name, place] … */
		public Map<String, List<String[]>> pending = new LinkedHashMap<>();
	}

	public static final String FILE = "nicecontrolcenter-weekly.json";
	private static Data data = new Data();
	private static Path file;

	private Weekly() {
	}

	public static Data data() {
		return data;
	}

	/** This week's id, ISO weeks ("2026-W46"; weeks start on Monday). */
	public static String weekOf(LocalDate d) {
		return d.get(IsoFields.WEEK_BASED_YEAR) + "-W" + String.format("%02d", d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			file = server.getWorldPath(LevelResource.ROOT).resolve(FILE);
			try {
				if (Files.exists(file)) {
					Data d = Json.GSON.fromJson(Files.readString(file), Data.class);
					if (d != null) data = d;
				}
			} catch (IOException | RuntimeException e) {
				NiceControlCenter.LOGGER.warn("Games: the weekly leaderboards couldn't be read: {}", e.toString());
			}
			if (data.boards == null) data.boards = new LinkedHashMap<>();
			if (data.last == null) data.last = new LinkedHashMap<>();
			if (data.pending == null) data.pending = new LinkedHashMap<>();
			if (data.week == null || data.week.isEmpty()) data.week = weekOf(LocalDate.now());
			turn(server);
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 1200 == 600) turn(server);
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> pendingFor(server, handler.player)));
	}

	/** A result: kept if it's the player's best this week. {@code tie}: lower wins between equal scores (a time). */
	public static void record(String kind, String id, String name, boolean higher, String unit, ServerPlayer p, long score, long tie) {
		if (p == null || id == null) return;
		Board b = data.boards.computeIfAbsent(kind + ":" + id, k -> new Board());
		b.kind = kind;
		b.name = name;
		b.higher = higher;
		b.unit = unit;
		Entry e = b.players.get(p.getStringUUID());
		boolean better = e == null || (higher ? score > e.best : score < e.best) || score == e.best && tie < e.tie;
		if (!better) {
			if (e != null) e.name = p.getGameProfile().name();
			return;
		}
		e = new Entry();
		e.name = p.getGameProfile().name();
		e.best = score;
		e.tie = tie;
		e.at = System.currentTimeMillis();
		b.players.put(p.getStringUUID(), e);
		save();
	}

	/** The week turned: the top 10 of each board get their place, and the boards start again. */
	private static void turn(MinecraftServer server) {
		String now = weekOf(LocalDate.now());
		if (now.equals(data.week)) return;
		String ended = forced != null ? forced : data.week;
		Map<String, Board> last = new LinkedHashMap<>();
		int given = 0;
		for (Map.Entry<String, Board> be : data.boards.entrySet()) {
			Board b = be.getValue();
			List<Map.Entry<String, Entry>> top = b.top(10);
			if (top.isEmpty()) continue;
			Board kept = new Board();
			kept.kind = b.kind;
			kept.name = b.name;
			kept.higher = b.higher;
			kept.unit = b.unit;
			int place = 1;
			for (Map.Entry<String, Entry> pe : top) {
				kept.players.put(pe.getKey(), pe.getValue());
				ServerPlayer p = server.getPlayerList().getPlayer(UUID.fromString(pe.getKey()));
				if (p != null) RewardsControl.fireWeekly(server, p, b.name, place, pe.getValue().best);
				else data.pending.computeIfAbsent(pe.getKey(), k -> new ArrayList<>()).add(new String[] { b.name, String.valueOf(place), String.valueOf(pe.getValue().best) });
				place++;
				given++;
			}
			last.put(be.getKey(), kept);
		}
		data.lastWeek = ended;
		data.last = last;
		data.boards = new LinkedHashMap<>();
		data.week = now;
		save();
		NiceControlCenter.LOGGER.info("Games: week {} is over; {} weekly places on {} boards given (offline players get theirs when they join)", ended, given, last.size());
	}

	/** Places from a week that turned while they were offline. */
	private static void pendingFor(MinecraftServer server, ServerPlayer p) {
		List<String[]> mine = data.pending.remove(p.getStringUUID());
		if (mine == null) return;
		for (String[] x : mine) {
			try {
				RewardsControl.fireWeekly(server, p, x[0], Integer.parseInt(x[1]), x.length > 2 ? Long.parseLong(x[2]) : 0);
			} catch (RuntimeException e) {
				NiceControlCenter.LOGGER.warn("Games: a weekly place for {} couldn't be given: {}", p.getGameProfile().name(), e.toString());
			}
		}
		save();
	}

	/** For testing and admins: the week turns now (as if it were Monday). */
	public static void turnNow(MinecraftServer server) {
		forced = data.week;
		data.week = "";
		turn(server);
		forced = null;
	}

	/** The week ended by hand (its id for the log and last week's boards). */
	private static String forced;

	private static void save() {
		if (file != null) AsyncFiles.write(file, Json.GSON.toJson(data));
	}
}
