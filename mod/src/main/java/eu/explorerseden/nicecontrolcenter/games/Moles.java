package eu.explorerseden.nicecontrolcenter.games;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.phys.AABB;

import eu.explorerseden.nicecontrolcenter.Games.Arena;
import eu.explorerseden.nicecontrolcenter.Pets;
import eu.explorerseden.nicecontrolcenter.log.ConsoleRunner;

/**
 * Whack-a-mole (quick round "moles"): a grid of holes (moleSize × moleSize, moleGap apart, from the corner set with
 * /game edit … grid). Heads pop up out of them for a moment (shorter as the round goes on); hitting or right-clicking one
 * scores its points (bad heads cost some). The first to hit it gets it.
 */
final class Moles {
	/** A head: a mob head block, or a player head with a skin (texture value), and what it's worth. */
	record Kind(Block block, String texture, int points) {
	}

	private Moles() {
	}

	/** "minecraft:zombie_head=1", "<skin url, hash or base64>=-3". */
	static List<Kind> kinds(Arena a) {
		List<Kind> out = new ArrayList<>();
		for (String line : a.moleHeads) {
			int eq = line.lastIndexOf('=');
			String what = (eq < 0 ? line : line.substring(0, eq)).strip();
			int points = 1;
			try {
				if (eq >= 0) points = Integer.parseInt(line.substring(eq + 1).strip());
			} catch (NumberFormatException e) {
				points = 1;
			}
			if (what.isEmpty() || points == 0) continue;
			Identifier id = what.length() < 64 ? Identifier.tryParse(what.contains(":") ? what : "minecraft:" + what) : null;
			Block b = id == null ? null : BuiltInRegistries.BLOCK.getValue(id);
			if (b != null && b.defaultBlockState().getBlock() instanceof AbstractSkullBlock && b != Blocks.PLAYER_WALL_HEAD) {
				out.add(new Kind(b, null, points));
				continue;
			}
			String texture = Pets.texture(what);
			if (texture != null) out.add(new Kind(Blocks.PLAYER_HEAD, texture, points));
		}
		return out;
	}

	/** Hole i, j: the hole block (the head pops up on top of it). */
	static BlockPos hole(Arena a, int i, int j) {
		int step = 1 + a.moleGap;
		return BlockPos.containing(a.corner.x + i * step, a.corner.y, a.corner.z + j * step);
	}

	/** The grid's blocks, plus room around it: the play box when no corners are set. */
	static AABB area(Arena a) {
		int span = (a.moleSize - 1) * (1 + a.moleGap) + 1;
		AABB grid = new AABB(a.corner.x - 6, a.corner.y - 2, a.corner.z - 6, a.corner.x + span + 6, a.corner.y + 6, a.corner.z + span + 6);
		// The spawn too (players stand there to hit), when it's in the same dimension.
		if (a.spawn != null && a.spawn.dim.equals(a.corner.dim)) grid = grid.minmax(new AABB(a.spawn.x - 3, a.spawn.y - 2, a.spawn.z - 3, a.spawn.x + 3, a.spawn.y + 4, a.spawn.z + 3));
		return grid;
	}

	/** Lays out the holes (and clears the spot above each one). */
	static void build(ServerLevel level, Arena a) {
		Identifier id = Identifier.tryParse(a.moleHole);
		Block b = id == null ? null : BuiltInRegistries.BLOCK.getValue(id);
		if (b == null || b == Blocks.AIR) b = Blocks.COARSE_DIRT;
		for (int i = 0; i < a.moleSize; i++) {
			for (int j = 0; j < a.moleSize; j++) {
				BlockPos h = hole(a, i, j);
				level.setBlock(h, b.defaultBlockState(), 3);
				if (!level.getBlockState(h.above()).isAir()) level.setBlock(h.above(), Blocks.AIR.defaultBlockState(), 3);
			}
		}
	}

	static void tick(MinecraftServer server, ServerLevel level, QuickRuntime.Round r, List<ServerPlayer> alive) {
		Arena a = r.arena;
		long now = System.currentTimeMillis();
		for (Iterator<Map.Entry<BlockPos, long[]>> it = r.moles.entrySet().iterator(); it.hasNext();) {
			Map.Entry<BlockPos, long[]> e = it.next();
			if (now < e.getValue()[0]) continue;
			down(level, e.getKey(), false);
			it.remove();
		}
		List<Kind> kinds = kinds(a);
		if (kinds.isEmpty() || now < r.nextMole || r.moles.size() >= a.maxAlive) return;
		// Quicker as the round goes on: a head stays up a third as long at the end (or after two minutes).
		double done = a.timeLimit > 0 ? Math.min(1, r.elapsed() / (a.timeLimit * 1000.0)) : Math.min(1, r.elapsed() / 120_000.0);
		long stay = Math.round(a.moleSeconds * 1000 * (1 - done * 2 / 3));
		java.util.Random random = QuickRuntime.random();
		r.nextMole = now + Math.round(stay * (0.3 + random.nextDouble() * 0.5) / (1 + 0.25 * Math.max(0, alive.size() - 1)));
		List<BlockPos> free = new ArrayList<>();
		for (int i = 0; i < a.moleSize; i++) {
			for (int j = 0; j < a.moleSize; j++) {
				BlockPos up = hole(a, i, j).above();
				if (!r.moles.containsKey(up) && level.getBlockState(up).isAir()) free.add(up);
			}
		}
		if (free.isEmpty()) return;
		BlockPos at = free.get(random.nextInt(free.size()));
		Kind k = kinds.get(random.nextInt(kinds.size()));
		BlockState state = k.block().defaultBlockState();
		// Facing the players' spawn.
		if (state.hasProperty(SkullBlock.ROTATION) && a.spawn != null) {
			float yaw = (float) Math.toDegrees(Math.atan2(-(a.spawn.x - (at.getX() + 0.5)), a.spawn.z - (at.getZ() + 0.5)));
			state = state.setValue(SkullBlock.ROTATION, RotationSegment.convertToSegment(yaw));
		}
		level.setBlock(at, state, 3);
		GameRuntime.track(level, at);
		if (k.texture() != null) {
			ConsoleRunner.quiet(server, "execute in " + level.dimension().identifier() + " run data merge block " + at.getX() + " " + at.getY() + " " + at.getZ()
					+ " {profile:{properties:[{name:\"textures\",value:\"" + k.texture() + "\"}]}}");
		}
		r.moles.put(at, new long[] { now + stay, k.points() });
		level.sendParticles(ParticleTypes.POOF, at.getX() + 0.5, at.getY() + 0.1, at.getZ() + 0.5, 4, 0.2, 0.05, 0.2, 0.01);
		level.playSound(null, at, SoundEvents.ROOTED_DIRT_BREAK, SoundSource.BLOCKS, 0.6f, 1.4f);
	}

	/** A head goes back down (hit or not). */
	private static void down(ServerLevel level, BlockPos at, boolean hit) {
		GameRuntime.untrack(level, at);
		if (!hit) level.sendParticles(ParticleTypes.SMOKE, at.getX() + 0.5, at.getY() + 0.2, at.getZ() + 0.5, 3, 0.15, 0.05, 0.15, 0.01);
	}

	/** Every head still up gone (round over). */
	static void clear(ServerLevel level, QuickRuntime.Round r) {
		for (BlockPos at : r.moles.keySet()) GameRuntime.untrack(level, at);
		r.moles.clear();
	}

	/** A head of a round being played (nobody else may break or use it). */
	static boolean isMole(Level level, BlockPos pos) {
		for (QuickRuntime.Round r : QuickRuntime.allRounds()) {
			if (r.moles.containsKey(pos) && level instanceof ServerLevel sl && sl.dimension().identifier().toString().equals(dimOf(r.arena))) return true;
		}
		return false;
	}

	/** A player hits or right-clicks a block: true when it was a head (of their round: it scores). */
	static boolean hit(ServerPlayer p, BlockPos pos) {
		QuickRuntime.Round r = QuickRuntime.roundOf(p);
		if (r == null || !r.arena.type.equals("moles")) return isMole(p.level(), pos);
		long[] m = r.moles.remove(pos);
		if (m == null) return isMole(p.level(), pos);
		ServerLevel level = (ServerLevel) p.level();
		down(level, pos, true);
		long points = m[1];
		QuickRuntime.add(r, p, points, null);
		double x = pos.getX() + 0.5, y = pos.getY() + 0.4, z = pos.getZ() + 0.5;
		if (points > 0) {
			level.sendParticles(ParticleTypes.CRIT, x, y, z, 10, 0.25, 0.2, 0.25, 0.2);
			level.playSound(null, pos, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 0.9f, 1.2f);
			QuickRuntime.ding(p, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.6f, 1.0f + Math.min(1, points * 0.1f));
		} else {
			level.sendParticles(ParticleTypes.ANGRY_VILLAGER, x, y, z, 3, 0.25, 0.2, 0.25, 0);
			QuickRuntime.ding(p, SoundEvents.VILLAGER_NO, 0.8f, 1.0f);
		}
		return true;
	}

	private static String dimOf(Arena a) {
		return a.corner != null ? a.corner.dim : a.spawn != null ? a.spawn.dim : "";
	}

	/** Why it can't run yet (null when it can). */
	static String check(Arena a) {
		if (a.corner == null) return a.name + " has no grid yet (/game edit " + a.name + " grid, standing where its corner goes).";
		if (kinds(a).isEmpty()) return a.name + " has no heads to pop up (panel).";
		return null;
	}
}
