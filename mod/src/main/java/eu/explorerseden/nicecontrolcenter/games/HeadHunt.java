package eu.explorerseden.nicecontrolcenter.games;

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

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

import eu.explorerseden.nicecontrolcenter.Games;
import eu.explorerseden.nicecontrolcenter.Games.Arena;
import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.Pets;
import eu.explorerseden.nicecontrolcenter.log.ConsoleRunner;
import eu.explorerseden.nicecontrolcenter.rewards.RewardsControl;

/**
 * Head hunts (Games type "heads"): player heads hidden around the world, all with the hunt's own skin. Players
 * right-click one to find it; every few heads there's a reward (the hunt's commands, Rewards trigger "headsFound"),
 * and finding them all finishes the hunt. Admins place the hunt's heads from /game edit … headitem (they register
 * themselves), or add a head they look at; the panel lists where they are. Heads of a hunt can't be broken except by
 * an admin in creative.
 * What each player found is kept in the world folder (nicecontrolcenter-games-heads.json).
 */
public final class HeadHunt {
	private static final String TAG = "ncc_hunt";

	/** One player's finds in one hunt. */
	static final class Found {
		String name;
		List<String> heads = new ArrayList<>();
	}

	/** hunt id → player uuid → what they found. */
	private static Map<String, Map<String, Found>> found = new LinkedHashMap<>();
	private static Path file;
	private static boolean dirty;
	/** Heads already given the hunt's skin (head key → texture), so it's only set once per restart. */
	private static final Map<String, String> skinned = new HashMap<>();

	private HeadHunt() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			file = server.getWorldPath(LevelResource.ROOT).resolve("nicecontrolcenter-games-heads.json");
			found = read(file);
			skinned.clear();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> save());
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			int t = server.getTickCount();
			if (t % 100 == 0) skin(server);
			if (dirty && t % 600 == 0) save();
		});
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!(player instanceof ServerPlayer p) || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
			BlockPos pos = hit.getBlockPos();
			Arena a = huntAt(level, pos);
			if (a != null && isHead(level.getBlockState(pos))) {
				find(p, a, key(level, pos));
				return InteractionResult.SUCCESS;
			}
			// An admin placing a hunt's head: it joins the hunt once it's down.
			String hunt = huntOf(p.getItemInHand(hand));
			if (hunt != null) {
				BlockPos at = level.getBlockState(pos).canBeReplaced() ? pos : pos.relative(hit.getDirection());
				p.level().getServer().execute(() -> placed(p, hunt, at));
			}
			return InteractionResult.PASS;
		});
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
			if (!(player instanceof ServerPlayer p)) return true;
			Arena a = huntAt(level, pos);
			if (a == null) return true;
			if (admin(p) && p.isCreative()) {
				a.heads.removeIf(h -> h.dim.equals(dim(level)) && (int) h.x == pos.getX() && (int) h.y == pos.getY() && (int) h.z == pos.getZ());
				GameRuntime.saveSettings();
				p.sendSystemMessage(Component.literal(a.shown() + ": head removed (" + a.heads.size() + " left).").withStyle(ChatFormatting.YELLOW));
				return true;
			}
			p.sendOverlayMessage(Component.literal("This head is part of the " + a.shown() + " hunt: right-click it").withStyle(ChatFormatting.GOLD));
			return false;
		});
	}

	// ---------------------------------------------------------------- for others

	/** A head of an enabled hunt is at pos (the claims let anyone click it). */
	public static boolean isHuntHead(Level level, BlockPos pos) {
		return huntAt(level, pos) != null;
	}

	/** /game join on a hunt: how far they are. */
	static void progress(ServerPlayer p, Arena a) {
		int total = a.heads.size();
		int n = count(a, p.getStringUUID());
		p.sendSystemMessage(Component.literal(a.shown() + ": you found " + n + " of " + total + " hidden heads" + (total == 0 ? " (none hidden yet)." : n >= total ? ". All of them!" : ". Right-click the heads to find them.")
				+ (a.rewardEvery > 0 && n < total ? " A reward every " + a.rewardEvery + "." : "")).withStyle(ChatFormatting.GOLD));
	}

	/** For the panel: how many heads are hidden and how many players found them all. */
	public static Map<String, Object> live(Arena a) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("heads", a.heads.size());
		long all = found.getOrDefault(a.id, Map.of()).keySet().stream().filter(id -> a.heads.size() > 0 && count(a, id) >= a.heads.size()).count();
		m.put("finished", all);
		return m;
	}

	/** Scores reset in the panel: their finds go too. */
	public static void reset(String huntId) {
		if (found.remove(huntId) != null) {
			dirty = true;
			save();
		}
	}

	// ---------------------------------------------------------------- finding

	private static void find(ServerPlayer p, Arena a, String key) {
		Found f = found.computeIfAbsent(a.id, k -> new LinkedHashMap<>()).computeIfAbsent(p.getStringUUID(), k -> new Found());
		f.name = p.getGameProfile().name();
		int total = a.heads.size();
		ServerLevel level = (ServerLevel) p.level();
		if (f.heads.contains(key)) {
			p.sendOverlayMessage(Component.literal("You found this one already · " + count(a, p.getStringUUID()) + "/" + total).withStyle(ChatFormatting.GRAY));
			return;
		}
		f.heads.add(key);
		dirty = true;
		int n = count(a, p.getStringUUID());
		BlockPos pos = p.blockPosition();
		String[] k = key.split("\\|");
		double x = Double.parseDouble(k[1]) + 0.5, y = Double.parseDouble(k[2]) + 0.5, z = Double.parseDouble(k[3]) + 0.5;
		level.sendParticles(p, ParticleTypes.TOTEM_OF_UNDYING, true, false, x, y, z, 30, 0.3, 0.4, 0.3, 0.3);
		level.playSound(null, pos, SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 1f, 1.2f + Math.min(0.8f, n / (float) Math.max(1, total)));
		p.sendOverlayMessage(Component.literal("Head " + n + " / " + total + " found!").withStyle(ChatFormatting.GOLD));
		GameRuntime.setBest(a, p, n);
		MinecraftServer server = level.getServer();
		RewardsControl.fireEvery(server, "headsFound", p, a.name, n);
		// The hunt's own points: so many per head (the fixed amount comes with the last one).
		if (a.pointsPerScore > 0) GameRuntime.points(p, perHead(a), 1, false);
		if (a.rewardEvery > 0 && n % a.rewardEvery == 0 && n < total) {
			p.sendSystemMessage(Component.literal("★ " + n + " heads found in " + a.shown() + ": reward!").withStyle(ChatFormatting.GOLD));
			commands(server, a.everyCommands, a, p, n, total);
			level.playSound(null, pos, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 1.2f);
		}
		if (n >= total) {
			String line = a.finishMessage.isBlank() ? "■ You found all " + total + " heads of " + a.shown() + "!" : a.finishMessage.replace("{player}", p.getGameProfile().name()).replace("{game}", a.shown()).replace("{score}", String.valueOf(n));
			p.sendSystemMessage(Component.literal(line).withStyle(ChatFormatting.GOLD));
			commands(server, a.finishCommands, a, p, n, total);
			// The every-few-heads reward too, when the last one is also such a head.
			if (a.rewardEvery > 0 && n % a.rewardEvery == 0) commands(server, a.everyCommands, a, p, n, total);
			RewardsControl.fireGame(server, "gameFinished", p, a.name, n, false);
			if (a.pointsFixed > 0) GameRuntime.points(p, allFound(a), n, true);
			level.playSound(null, pos, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1f, 1f);
			NiceControlCenter.LOGGER.info("Games: {} found all {} heads of {}", p.getGameProfile().name(), total, a.name);
		}
	}

	/** The hunt with only its per-head points (fixed ones come for finding them all). */
	private static Arena perHead(Arena a) {
		Arena x = new Arena();
		x.name = a.name;
		x.title = a.title;
		x.type = a.type;
		x.pointsPerScore = a.pointsPerScore;
		return x;
	}

	private static Arena allFound(Arena a) {
		Arena x = perHead(a);
		x.pointsPerScore = 0;
		x.pointsFixed = a.pointsFixed;
		return x;
	}

	/** Heads they found that are still part of the hunt. */
	private static int count(Arena a, String uuid) {
		Found f = found.getOrDefault(a.id, Map.of()).get(uuid);
		if (f == null) return 0;
		int n = 0;
		for (Games.Pos h : a.heads) if (f.heads.contains(key(h))) n++;
		return n;
	}

	private static void commands(MinecraftServer server, List<String> lines, Arena a, ServerPlayer p, int found, int total) {
		for (String line : lines) {
			String cmd = Games.math(line.strip().replaceFirst("^/", ""), Map.of("found", String.valueOf(found), "total", String.valueOf(total), "score", String.valueOf(found)))
					.replace("{player}", p.getGameProfile().name()).replace("{game}", a.shown())
					.replace("{found}", String.valueOf(found)).replace("{total}", String.valueOf(total)).replace("{score}", String.valueOf(found));
			if (!cmd.isEmpty()) ConsoleRunner.run(server, "execute as " + p.getStringUUID() + " at @s run " + cmd, "Game");
		}
	}

	// ---------------------------------------------------------------- setting up

	/** A head with the hunt's skin, tagged so placing it adds it to the hunt; null without a usable skin. */
	static ItemStack headItem(ServerPlayer admin, Arena a, int count) {
		String texture = Pets.texture(a.headTexture);
		if (texture == null) return null;
		ItemStack st = eu.explorerseden.nicecontrolcenter.pets.PetDialogs.head(admin, texture);
		if (st.isEmpty()) return null;
		st.setCount(Math.max(1, Math.min(64, count)));
		CustomData.update(DataComponents.CUSTOM_DATA, st, t -> t.putString(TAG, a.id));
		st.set(DataComponents.CUSTOM_NAME, Component.literal(a.shown() + " head (hidden head)").withStyle(ChatFormatting.GOLD));
		return st;
	}

	private static String huntOf(ItemStack st) {
		if (st.isEmpty() || !st.is(Items.PLAYER_HEAD)) return null;
		CustomData d = st.get(DataComponents.CUSTOM_DATA);
		return d == null ? null : d.copyTag().getStringOr(TAG, null);
	}

	private static void placed(ServerPlayer p, String huntId, BlockPos at) {
		Arena a = byId(huntId);
		if (a == null || !admin(p) || !isHead(p.level().getBlockState(at))) return;
		if (add(a, p.level(), at)) {
			skinned.put(key(p.level(), at), Pets.texture(a.headTexture));
			p.sendOverlayMessage(Component.literal(a.shown() + ": head " + a.heads.size() + " hidden").withStyle(ChatFormatting.GREEN));
		}
	}

	/** Adds a head at pos to the hunt (false when it's in already). */
	static boolean add(Arena a, Level level, BlockPos pos) {
		String k = key(level, pos);
		for (Games.Pos h : a.heads) if (key(h).equals(k)) return false;
		a.heads.add(new Games.Pos(dim(level), pos.getX(), pos.getY(), pos.getZ(), 0));
		GameRuntime.saveSettings();
		return true;
	}

	static boolean remove(Arena a, Level level, BlockPos pos) {
		String k = key(level, pos);
		boolean had = a.heads.removeIf(h -> key(h).equals(k));
		if (had) GameRuntime.saveSettings();
		return had;
	}

	static boolean isHead(BlockState state) {
		return state.is(Blocks.PLAYER_HEAD) || state.is(Blocks.PLAYER_WALL_HEAD);
	}

	/** Placed heads take the hunt's skin (also after it changes), once their chunk is loaded. */
	private static void skin(MinecraftServer server) {
		for (Arena a : GameRuntime.settings().arenas) {
			if (!a.type.equals("heads")) continue;
			String texture = Pets.texture(a.headTexture);
			if (texture == null) continue;
			for (Games.Pos h : a.heads) {
				String k = key(h);
				if (texture.equals(skinned.get(k))) continue;
				ServerLevel level = GameRuntime.levelOf(server, h.dim);
				BlockPos pos = BlockPos.containing(h.x, h.y, h.z);
				if (level == null || !level.isLoaded(pos) || !isHead(level.getBlockState(pos))) continue;
				ConsoleRunner.quiet(server, "execute in " + h.dim + " run data merge block " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
						+ " {profile:{properties:[{name:\"textures\",value:\"" + texture + "\"}]}}");
				skinned.put(k, texture);
			}
		}
	}

	// ---------------------------------------------------------------- helpers

	private static Arena huntAt(Level level, BlockPos pos) {
		String k = key(level, pos);
		for (Arena a : GameRuntime.settings().arenas) {
			if (!a.enabled || !a.type.equals("heads")) continue;
			for (Games.Pos h : a.heads) if (key(h).equals(k)) return a;
		}
		return null;
	}

	private static Arena byId(String id) {
		for (Arena a : GameRuntime.settings().arenas) if (a.id.equals(id) && a.type.equals("heads")) return a;
		return null;
	}

	private static boolean admin(ServerPlayer p) {
		return Permissions.check(p, GameRuntime.PERMISSION, PermissionLevel.GAMEMASTERS);
	}

	private static String key(Level level, BlockPos pos) {
		return dim(level) + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
	}

	private static String key(Games.Pos h) {
		return h.dim + "|" + (int) Math.floor(h.x) + "|" + (int) Math.floor(h.y) + "|" + (int) Math.floor(h.z);
	}

	private static String dim(Level level) {
		return level.dimension().identifier().toString();
	}

	private static Map<String, Map<String, Found>> read(Path f) {
		try {
			if (Files.exists(f)) {
				Map<String, Map<String, Found>> m = Json.GSON.fromJson(Files.readString(f), new com.google.gson.reflect.TypeToken<Map<String, Map<String, Found>>>() { }.getType());
				if (m != null) return m;
			}
		} catch (IOException | RuntimeException e) {
			NiceControlCenter.LOGGER.warn("Games: could not read the head hunts' finds: {}", e.getMessage());
		}
		return new LinkedHashMap<>();
	}

	private static void save() {
		dirty = false;
		if (file == null) return;
		try {
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, Json.GSON.toJson(found), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			NiceControlCenter.LOGGER.warn("Games: could not save the head hunts' finds: {}", e.getMessage());
		}
	}
}
