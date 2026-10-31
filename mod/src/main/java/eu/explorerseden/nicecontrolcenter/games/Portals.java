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
	enum Kind { ENTER, WATCH, BACK }

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
			if (hit == null || p.isSpectator()) {
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
					default -> "Back…";
				};
				p.sendOverlayMessage(Component.literal(what).withStyle(kind == Kind.BACK ? ChatFormatting.GRAY : ChatFormatting.GOLD));
				p.level().playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8f, 1.4f);
			}
			if (ticks < HOLD_TICKS) continue;
			holding.remove(id);
			stepOffFirst.add(id);
			switch (kind) {
				case ENTER -> enter(server, p, hit);
				case WATCH -> watch(server, p, hit);
				default -> back(server, p, hit);
			}
		}
	}

	/** Into the game: solo games start the run, wave games and quick rounds bring them to the start point (party along). */
	private static void enter(MinecraftServer server, ServerPlayer p, Arena a) {
		if (GameRuntime.inRun(p) || WaveRuntime.inGame(p) || QuickRuntime.inGame(p) || DungeonRuntime.inRun(p)) {
			p.sendSystemMessage(Component.literal("You're in a game already: /game leave first.").withStyle(ChatFormatting.RED));
			return;
		}
		Games.Pos here = new Games.Pos(dim(p), p.getX(), p.getY(), p.getZ(), p.getYRot());
		if (a.type.equals("heads")) {
			HeadHunt.progress(p, a);
			return;
		}
		if (a.start == null) {
			p.sendSystemMessage(Component.literal(a.shown() + " isn't set up yet.").withStyle(ChatFormatting.RED));
			return;
		}
		cameFrom.put(p.getUUID(), here);
		switch (a.type) {
			case "parkour", "dropper", "maze" -> {
				String err = GameRuntime.start(server, p, a);
				if (err != null) p.sendSystemMessage(Component.literal(err).withStyle(ChatFormatting.RED));
			}
			case "race" -> {
				to(server, p, a.start);
				String err = GameRuntime.start(server, p, a);
				if (err != null) p.sendSystemMessage(Component.literal(err).withStyle(ChatFormatting.RED));
			}
			default -> {
				// Wave games and quick rounds start from their start point: the party (within 8 blocks) comes along.
				List<ServerPlayer> going = new ArrayList<>(List.of(p));
				if (a.type.equals("waves") && Parties.isLeader(p.getUUID())) {
					for (UUID m : Parties.members(p.getUUID())) {
						ServerPlayer mp = server.getPlayerList().getPlayer(m);
						if (mp != null && mp != p && mp.level() == p.level() && mp.distanceToSqr(p) < 64 && !WaveRuntime.inGame(mp)) going.add(mp);
					}
				}
				for (ServerPlayer g : going) {
					cameFrom.put(g.getUUID(), here);
					to(server, g, a.start);
				}
				p.sendSystemMessage(Component.literal(a.type.equals("waves") ? "▶ " + a.shown() + ": the party leader stays on the start point to begin." : "▶ " + a.shown() + ": you're in the next round.").withStyle(ChatFormatting.GREEN));
			}
		}
	}

	private static void watch(MinecraftServer server, ServerPlayer p, Arena a) {
		if (a.spectate == null) {
			p.sendSystemMessage(Component.literal(a.shown() + " has no spectator area yet.").withStyle(ChatFormatting.RED));
			return;
		}
		cameFrom.put(p.getUUID(), new Games.Pos(dim(p), p.getX(), p.getY(), p.getZ(), p.getYRot()));
		to(server, p, a.spectate);
		p.sendSystemMessage(Component.literal("Watching " + a.shown() + ". A back point takes you out again.").withStyle(ChatFormatting.AQUA));
	}

	/** A back point: out of a solo run, then back where they came from. */
	private static void back(MinecraftServer server, ServerPlayer p, Arena a) {
		if (GameRuntime.inRun(p)) GameRuntime.leaveRun(server, p);
		if (!returnTo(server, p, a)) p.sendSystemMessage(Component.literal("There's nowhere to go back to (no entrance or hub spawn set).").withStyle(ChatFormatting.GRAY));
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
		p.teleportTo(level, at.x, at.y, at.z, Set.of(), at.yaw, 0, true);
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
		}
	}

	private static void ring(MinecraftServer server, Games.Pos pad, DustParticleOptions dust, double turn, boolean rise) {
		ServerLevel level = GameRuntime.levelOf(server, pad.dim);
		if (level == null || level.getNearestPlayer(pad.x, pad.y, pad.z, 32, false) == null) return;
		for (int i = 0; i < 10; i++) {
			double ang = i * Math.PI / 5 + turn;
			level.sendParticles(dust, pad.x + Math.cos(ang) * 0.9, pad.y + 0.1, pad.z + Math.sin(ang) * 0.9, 1, 0, 0, 0, 0);
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
			for (Games.Pos b : a.exitPads) out.put(key("back", a.id, b), new Label(b, 1.6, Component.literal("Back").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
					.append(Component.literal("\nstand here to go back").withStyle(st -> st.withBold(false).withColor(ChatFormatting.GRAY)))));
			if (a.spectate != null) out.put(key("spectate", a.id, a.spectate), new Label(a.spectate, 2.4,
					Component.literal("Spectators").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD).append(Component.literal("\n" + a.shown()).withStyle(st -> st.withBold(false).withColor(ChatFormatting.WHITE)))));
		}
		Dungeons.Settings d = DungeonRuntime.settings();
		if (d.enabled) {
			for (Dungeons.Pad pad : d.pads) {
				if (pad.pos == null) continue;
				out.put(key("dungeon", pad.pool, pad.pos), new Label(pad.pos, 2.2, Component.literal("Dungeon").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD)
						.append(Component.literal("\n" + pad.pool + " · party leader steps on").withStyle(st -> st.withBold(false).withColor(ChatFormatting.GRAY)))));
			}
			for (Map.Entry<String, Games.Pos> w : d.waiting.entrySet()) {
				out.put(key("dqueue", w.getKey(), w.getValue()), new Label(w.getValue(), 2.4, Component.literal("Dungeon queue").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD)
						.append(Component.literal("\n" + w.getKey()).withStyle(st -> st.withBold(false).withColor(ChatFormatting.GRAY)))));
			}
		}
		return out;
	}

	private static String key(String kind, String id, Games.Pos at) {
		return TAG + "_" + Integer.toHexString((kind + "|" + id + "|" + at.dim + "|" + Math.round(at.x * 2) + "|" + Math.round(at.y * 2) + "|" + Math.round(at.z * 2)).hashCode());
	}

	/** The game's name, what it is, who plays now, and the best three. */
	private static Component enterText(Arena a) {
		MutableComponent t = Component.literal(a.shown()).withStyle(st -> st.withColor(a.rgb()).withBold(true));
		// The game's own line under its name (panel), when it has one.
		if (!a.subtitle.isBlank()) t.append(Component.literal("\n" + a.subtitle.strip()).withStyle(st -> st.withBold(false).withColor(ChatFormatting.GRAY)));
		List<Games.Score> top = GameRuntime.top(a, 3);
		String[] medal = { "1st", "2nd", "3rd" };
		for (int i = 0; i < top.size(); i++) {
			Games.Score sc = top.get(i);
			int rank = i;
			t.append(Component.literal("\n" + medal[i] + " " + sc.name + " · " + Games.scoreText(a.type, sc.best, GameRuntime::clock))
					.withStyle(st -> st.withBold(false).withColor(rank == 0 ? ChatFormatting.YELLOW : ChatFormatting.WHITE)));
		}
		if (top.isEmpty()) t.append(Component.literal("\nno best yet: be the first!").withStyle(st -> st.withBold(false).withColor(ChatFormatting.DARK_GRAY)));
		return t;
	}

	private static Component watchText(Arena a) {
		Map<String, Object> live = Games.quick(a.type) ? QuickRuntime.live(a.id) : a.type.equals("waves") ? WaveRuntime.live(a.id) : Map.of();
		Object playing = live.get("playing");
		String now = playing instanceof Number n && n.longValue() > 0 ? n + " playing now" : "nobody playing right now";
		return Component.literal("Watch " + a.shown()).withStyle(st -> st.withColor(lighter(a.rgb())).withBold(true))
				.append(Component.literal("\n" + now).withStyle(st -> st.withBold(false).withColor(ChatFormatting.GRAY)));
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
				ConsoleRunner.quiet(server, String.format(Locale.ROOT, "execute in %s run summon minecraft:text_display %.2f %.2f %.2f {Tags:[\"%s\",\"%s\"],billboard:\"center\",alignment:\"center\",line_width:220,background:1073741824,text:%s}",
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
