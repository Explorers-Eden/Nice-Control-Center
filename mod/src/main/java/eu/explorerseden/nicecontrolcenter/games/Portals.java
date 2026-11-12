package eu.explorerseden.nicecontrolcenter.games;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;

import eu.explorerseden.nicecontrolcenter.Dungeons;
import eu.explorerseden.nicecontrolcenter.Games;
import eu.explorerseden.nicecontrolcenter.Games.Arena;
import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.log.ConsoleRunner;
import eu.explorerseden.nicecontrolcenter.mixin.TextDisplayAccessor;

/**
 * The ways into and out of games, placed anywhere (e.g. in a hub), each with particles and a floating label:
 * entrances (to the game; its name, what it is, who plays and the top 3 above them), watch points (to the
 * spectator area) and back points (the way out of spectator areas and the like; inside a wave game or quick round
 * standing on one 5 seconds leaves it, as before). Back points and the end of a game take players to the game hub
 * spawn (/game spawn set), else the entrance they came through. Dungeon pads and waiting areas get labels too. Stepping in needs a second on the spot (no stumbling
 * in), and after being sent somewhere a player has to step off first.
 */
public final class Portals {
	enum Kind { ENTER, WATCH, BACK, POOL }

	private static final String TAG = "ncc_label";
	private static final int HOLD_TICKS = 20;
	/** player → the entrance they came through (dimension and spot), for the way back. */
	private static final Map<UUID, Games.Pos> cameFrom = new HashMap<>();
	/** Players sent somewhere by a portal and still standing on it: nothing happens until they step off. */
	private static final Set<UUID> stepOffFirst = new HashSet<>();
	private static final Map<UUID, Integer> holding = new HashMap<>();
	/** Label key → its entity, and the text it shows. */
	private static final Map<String, Entity> labels = new HashMap<>();

	private Portals() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			int t = server.getTickCount();
			if (t % 2 == 0) step(server);
			if (t % 10 == 0) particles(server);
			if (t % 40 == 0) labels(server, t % 200 == 0);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			holding.remove(handler.player.getUUID());
			stepOffFirst.remove(handler.player.getUUID());
		});
	}

	// ---------------------------------------------------------------- stepping in

	/**
	 * Admins with claim bypass on (/claim admin bypass) aren't pulled into games by pads (entrances, start points,
	 * dungeon pads) or along with their party; standing on one tells them so now and then.
	 */
	public static boolean ignored(ServerPlayer p, boolean onPad) {
		if (!eu.explorerseden.nicecontrolcenter.claims.ClaimTool.bypassing(p)) return false;
		if (onPad && p.level().getGameTime() % 40 < 2) p.sendOverlayMessage(Words.lit("Claim bypass is on: pads leave you alone").withStyle(ChatFormatting.GRAY));
		return true;
	}

	private static boolean on(ServerPlayer p, Games.Pos pad) {
		if (pad == null || !pad.dim.equals(dim(p))) return false;
		double dx = p.getX() - pad.x, dz = p.getZ() - pad.z, dy = p.getY() - pad.y;
		return dx * dx + dz * dz <= 1.0 && dy > -0.6 && dy < 1.5;
	}

	private static void step(MinecraftServer server) {
		if (!GameRuntime.settings().enabled) return;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			UUID id = p.getUUID();
			Arena hit = null;
			Kind kind = null;
			for (Arena a : GameRuntime.settings().arenas) {
				if (!a.enabled) continue;
				for (Games.Pos e : a.entrances) if (on(p, e)) { hit = a; kind = Kind.ENTER; }
				for (Games.Pos w : a.watchEntrances) if (on(p, w)) { hit = a; kind = Kind.WATCH; }
				// Inside a wave game or quick round their own exit pads leave it.
				if (!WaveRuntime.inGame(p) && !QuickRuntime.inGame(p)) for (Games.Pos b : a.exitPads) if (on(p, b)) { hit = a; kind = Kind.BACK; }
				if (hit != null) break;
			}
			// Arena pool entrances: the pool's first arena stands for it here.
			String pool = null;
			if (hit == null) for (Games.PoolPad pp : GameRuntime.settings().poolPads) {
				if (!on(p, pp.pos)) continue;
				List<Arena> in = GameRuntime.settings().pool(pp.pool);
				if (in.isEmpty()) continue;
				pool = pp.pool;
				hit = in.get(0);
				kind = Kind.POOL;
				break;
			}
			if (hit == null || p.isSpectator() || ignored(p, true)) {
				stepOffFirst.remove(id);
				holding.remove(id);
				continue;
			}
			if (stepOffFirst.contains(id)) continue;
			int ticks = holding.merge(id, 2, Integer::sum);
			if (ticks == 2) {
				String what = switch (kind) {
					case ENTER -> hit.shown() + "…";
					case WATCH -> "Watching " + hit.shown() + "…";
					case POOL -> cap(pool) + "…";
					default -> "Back…";
				};
				p.sendOverlayMessage(Words.lit(what).withStyle(kind == Kind.BACK ? ChatFormatting.GRAY : ChatFormatting.GOLD));
				p.level().playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8f, 1.4f);
			}
			if (ticks < HOLD_TICKS) continue;
			holding.remove(id);
			stepOffFirst.add(id);
			switch (kind) {
				case ENTER -> enter(server, p, hit);
				case WATCH -> watch(server, p, hit);
				case POOL -> {
					Games.Pos here = new Games.Pos(dim(p), p.getX(), p.getY(), p.getZ(), p.getYRot());
					List<ServerPlayer> went = WaveRuntime.enterPool(server, p, pool);
					if (went != null) for (ServerPlayer g : went) cameFrom.put(g.getUUID(), here);
				}
				default -> back(server, p, hit);
			}
		}
	}

	/** Into the game: solo games start the run, wave games and quick rounds bring them to the start point (party along). */
	private static void enter(MinecraftServer server, ServerPlayer p, Arena a) {
		if (GameRuntime.inRun(p) || WaveRuntime.inGame(p) || QuickRuntime.inGame(p) || DungeonRuntime.inRun(p)) {
			p.sendSystemMessage(Words.lit("You're in a game already: /game leave first.").withStyle(ChatFormatting.RED));
			return;
		}
		Games.Pos here = new Games.Pos(dim(p), p.getX(), p.getY(), p.getZ(), p.getYRot());
		if (a.type.equals("heads")) {
			HeadHunt.progress(p, a);
			return;
		}
		if (a.start == null) {
			p.sendSystemMessage(Words.lit(a.shown() + " isn't set up yet.").withStyle(ChatFormatting.RED));
			return;
		}
		cameFrom.put(p.getUUID(), here);
		switch (a.type) {
			case "parkour", "maze" -> {
				String err = GameRuntime.start(server, p, a);
				if (err != null) p.sendSystemMessage(Words.lit(err).withStyle(ChatFormatting.RED));
			}
			case "race" -> {
				to(server, p, a.start);
				String err = GameRuntime.start(server, p, a);
				if (err != null) p.sendSystemMessage(Words.lit(err).withStyle(ChatFormatting.RED));
			}
			default -> {
				// Wave games and quick rounds start from their start point: the party (within 8 blocks) comes along.
				List<ServerPlayer> going = new ArrayList<>(List.of(p));
				if ((a.type.equals("waves") || Games.quick(a.type)) && Parties.isLeader(p.getUUID())) {
					for (UUID m : Parties.members(p.getUUID())) {
						ServerPlayer mp = server.getPlayerList().getPlayer(m);
						if (mp != null && mp != p && mp.level() == p.level() && mp.distanceToSqr(p) < 64 && !WaveRuntime.inGame(mp) && !ignored(mp, false)) going.add(mp);
					}
				}
				for (ServerPlayer g : going) {
					cameFrom.put(g.getUUID(), here);
					to(server, g, a.start);
				}
				p.sendSystemMessage(Words.lit(a.type.equals("waves") ? "▶ " + a.shown() + ": the party leader stays on the start point to begin." : "▶ " + a.shown() + ": you're in the next round.").withStyle(ChatFormatting.GREEN));
			}
		}
	}

	private static void watch(MinecraftServer server, ServerPlayer p, Arena a) {
		if (a.spectate == null) {
			p.sendSystemMessage(Words.lit(a.shown() + " has no spectator area yet.").withStyle(ChatFormatting.RED));
			return;
		}
		cameFrom.put(p.getUUID(), new Games.Pos(dim(p), p.getX(), p.getY(), p.getZ(), p.getYRot()));
		to(server, p, a.spectateSpot());
		p.sendSystemMessage(Words.lit("Watching " + a.shown() + ". A back point takes you out again.").withStyle(ChatFormatting.AQUA));
	}

	/** A back point: out of a solo run, then back where they came from. */
	private static void back(MinecraftServer server, ServerPlayer p, Arena a) {
		if (GameRuntime.inRun(p)) GameRuntime.leaveRun(server, p);
		if (!returnTo(server, p, a)) p.sendSystemMessage(Words.lit("There's nowhere to go back to (no entrance or hub spawn set).").withStyle(ChatFormatting.GRAY));
	}

	/**
	 * After a game (or from a back point): to the game hub spawn, else the entrance they came through, else the
	 * game's first entrance; false when there's none (then the game uses its start point as before).
	 */
	public static boolean returnTo(MinecraftServer server, ServerPlayer p, Arena a) {
		Games.Pos from = cameFrom.remove(p.getUUID());
		if (GameRuntime.toHub(server, p)) return true;
		if (from != null && to(server, p, from)) {
			stepOffFirst.add(p.getUUID());
			return true;
		}
		if (a != null && !a.entrances.isEmpty() && to(server, p, a.entrances.get(0))) {
			stepOffFirst.add(p.getUUID());
			return true;
		}
		return false;
	}

	private static boolean to(MinecraftServer server, ServerPlayer p, Games.Pos at) {
		ServerLevel level = GameRuntime.levelOf(server, at.dim);
		if (level == null) return false;
		// Never inside a block (a start point where a grid was laid out later).
		p.teleportTo(level, at.x, GameRuntime.freeY(level, p, at.x, at.y, at.z), at.z, Set.of(), at.yaw, 0, true);
		p.resetFallDistance();
		return true;
	}

	// ---------------------------------------------------------------- looks

	private static void particles(MinecraftServer server) {
		double turn = server.getTickCount() / 20.0 * 0.5;
		for (Arena a : GameRuntime.settings().arenas) {
			if (!a.enabled) continue;
			// The game's own colour (gold without one); watch points a lighter shade of it.
			for (Games.Pos e : a.entrances) ring(server, e, new DustParticleOptions(a.rgb(), 1.3f), turn, true);
			for (Games.Pos w : a.watchEntrances) ring(server, w, new DustParticleOptions(lighter(a.rgb()), 1.3f), turn, true);
			for (Games.Pos b : a.exitPads) ring(server, b, new DustParticleOptions(0xFF5A5A, 1.1f), turn, false);
			// A maze's goal: an exit pad in the game's colour, as wide as its path.
			Games.Pos goal = a.type.equals("maze") && !GameRuntime.mazeBuilding(a) ? GameRuntime.mazeGoal(a) : null;
			if (goal != null) ring(server, goal, new DustParticleOptions(a.rgb(), 1.1f), turn, false, 0.9 * Math.max(1, a.mazePath * 0.6));
		}
		for (Games.PoolPad pp : GameRuntime.settings().poolPads) {
			List<Arena> in = GameRuntime.settings().pool(pp.pool);
			if (!in.isEmpty()) ring(server, pp.pos, new DustParticleOptions(in.get(0).rgb(), 1.3f), turn, true);
		}
	}

	private static void ring(MinecraftServer server, Games.Pos pad, DustParticleOptions dust, double turn, boolean rise) {
		ring(server, pad, dust, turn, rise, 0.9);
	}

	private static void ring(MinecraftServer server, Games.Pos pad, DustParticleOptions dust, double turn, boolean rise, double radius) {
		ServerLevel level = GameRuntime.levelOf(server, pad.dim);
		if (level == null || level.getNearestPlayer(pad.x, pad.y, pad.z, 32, false) == null) return;
		int dots = (int) Math.round(10 * radius / 0.9);
		for (int i = 0; i < dots; i++) {
			double ang = i * 2 * Math.PI / dots + turn;
			level.sendParticles(dust, pad.x + Math.cos(ang) * radius, pad.y + 0.1, pad.z + Math.sin(ang) * radius, 1, 0, 0, 0, 0);
		}
		if (rise) level.sendParticles(ParticleTypes.END_ROD, pad.x, pad.y + 0.3, pad.z, 2, 0.3, 0.6, 0.3, 0.01);
	}

	/** Every label that should stand: key → where and what it says. */
	private record Label(Games.Pos at, double height, Component text) {
	}

	private static Map<String, Label> wanted() {
		Map<String, Label> out = new LinkedHashMap<>();
		for (Arena a : GameRuntime.settings().arenas) {
			if (!a.enabled) continue;
			for (Games.Pos e : a.entrances) out.put(key("enter", a.id, e), new Label(e, 2.2, enterText(a)));
			for (Games.Pos w : a.watchEntrances) out.put(key("watch", a.id, w), new Label(w, 1.9, watchText(a)));
			for (Games.Pos b : a.exitPads) out.put(key("back", a.id, b), new Label(b, 1.6, Words.lit("Back").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
			Games.Pos goal = a.type.equals("maze") ? GameRuntime.mazeGoal(a) : null;
			if (goal != null) out.put(key("mazeexit", a.id, goal), new Label(goal, 1.6, Words.lit("Exit").withStyle(st -> st.withColor(a.rgb()).withBold(true))));
			if (a.spectate != null) out.put(key("spectate", a.id, a.spectate), new Label(a.spectate, 2.4,
					Words.lit("Spectators").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD).append(Words.lit("\n" + a.shown()).withStyle(st -> st.withBold(false).withColor(ChatFormatting.WHITE)))));
		}
		for (Games.PoolPad pp : GameRuntime.settings().poolPads) {
			List<Arena> in = GameRuntime.settings().pool(pp.pool);
			if (in.isEmpty()) continue;
			out.put(key("pool", pp.pool, pp.pos), new Label(pp.pos, 2.2, poolText(pp.pool, in)));
		}
		Dungeons.Settings d = DungeonRuntime.settings();
		for (boolean rogue : new boolean[] { false, true }) {
			if (!(rogue ? d.rogueEnabled : d.enabled)) continue;
			String title = rogue ? "Rogue Run" : "Dungeon";
			for (Dungeons.Pad pad : d.pads(rogue)) {
				if (pad.pos == null) continue;
				out.put(key(rogue ? "rogue" : "dungeon", pad.pool, pad.pos), new Label(pad.pos, 2.2, Words.lit(title).withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD)
						.append(Words.lit("\n" + cap(pad.pool) + " · Party leader steps on").withStyle(st -> st.withBold(false).withColor(ChatFormatting.GRAY)))));
			}
			for (Map.Entry<String, Games.Pos> w : d.waiting(rogue).entrySet()) {
				out.put(key(rogue ? "rqueue" : "dqueue", w.getKey(), w.getValue()), new Label(w.getValue(), 2.4, Words.lit(title + " queue").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD)
						.append(Words.lit("\n" + cap(w.getKey())).withStyle(st -> st.withBold(false).withColor(ChatFormatting.GRAY)))));
			}
		}
		return out;
	}

	/** First letter upper case ("niceron" → "Niceron"). */
	private static String cap(String s) {
		return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	/** Every word's first letter upper case ("42 jumps" → "42 Jumps"). */
	private static String capWords(String s) {
		StringBuilder out = new StringBuilder();
		for (String w : s.split(" ", -1)) out.append(out.length() > 0 ? " " : "").append(cap(w));
		return out.toString();
	}

	/** Labels made with an older look (background, size) go and come back new: this is part of their tag. */
	private static final String LOOK = "v2";

	private static String key(String kind, String id, Games.Pos at) {
		return TAG + "_" + Integer.toHexString((LOOK + "|" + kind + "|" + id + "|" + at.dim + "|" + Math.round(at.x * 2) + "|" + Math.round(at.y * 2) + "|" + Math.round(at.z * 2)).hashCode());
	}

	/** An arena pool: its name, its arenas, how many are free and who's waiting. */
	private static Component poolText(String pool, List<Arena> in) {
		Arena first = in.get(0);
		MutableComponent t = Words.lit(cap(pool)).withStyle(st -> st.withColor(first.rgb()).withBold(true));
		String names = String.join(", ", in.stream().map(Arena::shown).toList());
		t.append(Words.lit("\n" + (names.length() > 60 ? in.size() + " arenas" : names)).withStyle(st -> st.withBold(false).withColor(ChatFormatting.GRAY)));
		Map<String, Object> live = WaveRuntime.poolLive(pool);
		long free = live.get("free") instanceof Number n ? n.longValue() : 0;
		long queued = live.get("queued") instanceof Number n ? n.longValue() : 0;
		int least = in.stream().mapToInt(a -> a.minParty).min().orElse(1), most = in.stream().mapToInt(a -> a.maxParty).max().orElse(4);
		String line = (least == most ? least + "" : least + "–" + most) + " players · " + (free > 0 ? free + " free" : "all busy") + (queued > 0 ? " · " + queued + (queued == 1 ? " party" : " parties") + " in queue" : "");
		t.append(Words.lit("\n" + line).withStyle(st -> st.withBold(false).withColor(ChatFormatting.AQUA)));
		t.append(Words.lit("\nParty leader steps on").withStyle(st -> st.withBold(false).withColor(ChatFormatting.DARK_GRAY)));
		return t;
	}

	/** The game's name, what it is, who plays now, and the best three. */
	private static Component enterText(Arena a) {
		MutableComponent t = Words.lit(a.shown()).withStyle(st -> st.withColor(a.rgb()).withBold(true));
		// The game's own line under its name (panel), when it has one.
		if (!a.subtitle.isBlank()) t.append(Words.lit("\n" + a.subtitle.strip()).withStyle(st -> st.withBold(false).withColor(ChatFormatting.GRAY)));
		String who = whoLine(a);
		if (!who.isEmpty()) t.append(Words.lit("\n" + who).withStyle(st -> st.withBold(false).withColor(ChatFormatting.AQUA)));
		List<Games.Score> top = GameRuntime.top(a, 3);
		String[] medal = { "1st", "2nd", "3rd" };
		for (int i = 0; i < top.size(); i++) {
			Games.Score sc = top.get(i);
			int rank = i;
			t.append(Words.lit("\n" + medal[i] + " " + cap(sc.name) + " · " + capWords(Games.scoreText(a.type, sc.best, GameRuntime::clock)))
					.withStyle(st -> st.withBold(false).withColor(rank == 0 ? ChatFormatting.YELLOW : ChatFormatting.WHITE)));
		}
		if (top.isEmpty()) t.append(Words.lit("\nNo best yet: be the first!").withStyle(st -> st.withBold(false).withColor(ChatFormatting.DARK_GRAY)));
		return t;
	}

	/** How many play it and who's waiting: "1–8 players · round on · 3 in queue", "1–4 players · 1 party in queue", "1+ players · 2 running". */
	private static String whoLine(Arena a) {
		if (a.type.equals("heads")) return "";
		if (Games.quick(a.type)) {
			Map<String, Object> live = QuickRuntime.live(a.id);
			long playing = live.get("playing") instanceof Number n ? n.longValue() : 0;
			long queued = live.get("queued") instanceof Number n ? n.longValue() : 0;
			boolean joining = Boolean.TRUE.equals(live.get("waiting"));
			String line = "1–" + a.maxPlayers + " players";
			if (joining && playing > 0) line += " · " + playing + " joining";
			else if (playing > 0) line += " · round on";
			if (queued > 0) line += " · " + queued + " in queue";
			return line;
		}
		if (a.type.equals("waves")) {
			Map<String, Object> live = WaveRuntime.live(a.id);
			long playing = live.get("playing") instanceof Number n ? n.longValue() : 0;
			long queued = live.get("queued") instanceof Number n ? n.longValue() : 0;
			String line = "1–" + a.maxParty + " players";
			if (playing > 0) line += " · fight on";
			if (queued > 0) line += " · " + queued + (queued == 1 ? " party" : " parties") + " in queue";
			return line;
		}
		int running = GameRuntime.running(a.id);
		// Parkour: a lane each; races and mazes take any number.
		return (a.type.equals("parkour") ? "1–" + a.lanes + " players" : "1+ players") + (running > 0 ? " · " + running + " running" : "");
	}

	private static Component watchText(Arena a) {
		Map<String, Object> live = Games.quick(a.type) ? QuickRuntime.live(a.id) : a.type.equals("waves") ? WaveRuntime.live(a.id) : Map.of();
		Object playing = live.get("playing");
		String now = playing instanceof Number n && n.longValue() > 0 ? n + " playing now" : "nobody playing right now";
		return Words.lit("Watch " + a.shown()).withStyle(st -> st.withColor(lighter(a.rgb())).withBold(true))
				.append(Words.lit("\n" + now).withStyle(st -> st.withBold(false).withColor(ChatFormatting.GRAY)));
	}

	/**
	 * Every two seconds: labels show what they should (text changed in place); every ten, missing ones (in loaded
	 * chunks) are made and ones nothing wants any more go.
	 */
	private static void labels(MinecraftServer server, boolean full) {
		Map<String, Label> want = wanted();
		if (full) {
			Map<String, Entity> seen = new HashMap<>();
			for (ServerLevel level : server.getAllLevels()) {
				for (Entity e : level.getAllEntities()) {
					if (e == null || e.isRemoved() || !e.entityTags().contains(TAG)) continue;
					String k = e.entityTags().stream().filter(x -> x.startsWith(TAG + "_")).findFirst().orElse("");
					if (!want.containsKey(k) || seen.containsKey(k)) {
						e.discard();
						continue;
					}
					seen.put(k, e);
				}
			}
			labels.clear();
			labels.putAll(seen);
			for (Map.Entry<String, Label> w : want.entrySet()) {
				if (labels.containsKey(w.getKey())) continue;
				Games.Pos at = w.getValue().at();
				ServerLevel level = GameRuntime.levelOf(server, at.dim);
				if (level == null || !level.isPositionEntityTicking(BlockPos.containing(at.x, at.y, at.z))) continue;
				String text = Json.GSON.toJson(net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(server.registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE), w.getValue().text()).getOrThrow());
				ConsoleRunner.quiet(server, String.format(Locale.ROOT, "execute in %s run summon minecraft:text_display %.2f %.2f %.2f {Tags:[\"%s\",\"%s\"],billboard:\"center\",alignment:\"center\",line_width:220,background:0,shadow:1b,transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[0.75f,0.75f,0.75f]},text:%s}",
						at.dim, at.x, at.y + w.getValue().height(), at.z, TAG, w.getKey(), text));
			}
			return;
		}
		for (Map.Entry<String, Entity> e : labels.entrySet()) {
			Label l = want.get(e.getKey());
			if (l == null || e.getValue().isRemoved() || !(e.getValue() instanceof Display.TextDisplay d)) continue;
			TextDisplayAccessor acc = (TextDisplayAccessor) d;
			if (!l.text().equals(acc.nicecontrolcenter$getText())) acc.nicecontrolcenter$setText(l.text());
		}
	}

	/** Halfway to white. */
	private static int lighter(int rgb) {
		int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
		return ((r + 255) / 2 << 16) | ((g + 255) / 2 << 8) | (b + 255) / 2;
	}

	private static String dim(ServerPlayer p) {
		return p.level().dimension().identifier().toString();
	}
}
