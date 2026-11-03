package eu.explorerseden.nicecontrolcenter.games;

import java.util.List;
import java.util.Random;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

/**
 * Rising lava (quick round "lava", a survival round): the play box fills with lava from its floor up, a layer at a
 * time and faster each layer; touching it is out. The score is how long they lasted; the last one standing wins.
 * Lava only goes where there was air and doesn't flow; it all goes when the round ends (also after a crash: the box
 * is kept with the placed blocks).
 */
final class Lava {
	/** Flags: no neighbour updates (nothing around reacts, the lava doesn't start flowing). */
	private static final int QUIET = 2 | 16;

	private Lava() {
	}

	static void start(ServerLevel level, QuickRuntime.Round r) {
		AABB box = QuickRuntime.box(r.arena);
		r.lavaY = (int) Math.floor(box.minY);
		r.lavaGap = r.arena.lavaSeconds;
		// A little longer before the first one: time to start climbing.
		r.nextLava = System.currentTimeMillis() + Math.round(r.lavaGap * 1000) + 2000;
		r.lavaKey = GameRuntime.trackLava(level, box);
	}

	/** True when the lava has reached the top of the box (the round is over: everyone still up made it). */
	static boolean tick(ServerLevel level, QuickRuntime.Round r, List<ServerPlayer> alive) {
		AABB box = QuickRuntime.box(r.arena);
		int top = (int) Math.ceil(box.maxY) - 1;
		if (r.lavaY > top) return true;
		long now = System.currentTimeMillis();
		int x0 = (int) Math.floor(box.minX), z0 = (int) Math.floor(box.minZ), x1 = (int) Math.ceil(box.maxX) - 1, z1 = (int) Math.ceil(box.maxZ) - 1;
		// The last two seconds before a layer: it bubbles where it will come.
		if (now >= r.nextLava - 2000 && level.getGameTime() % 4 == 0) {
			Random random = QuickRuntime.random();
			for (int i = 0; i < 6; i++) {
				double x = x0 + random.nextDouble() * (x1 - x0 + 1), z = z0 + random.nextDouble() * (z1 - z0 + 1);
				level.sendParticles(ParticleTypes.LAVA, x, r.lavaY + 0.2, z, 1, 0, 0, 0, 0);
			}
			if (level.getGameTime() % 20 == 0) for (ServerPlayer p : alive) QuickRuntime.ding(p, SoundEvents.LAVA_POP, 0.6f, 1.0f);
		}
		if (now < r.nextLava) return false;
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				BlockPos at = new BlockPos(x, r.lavaY, z);
				if (level.getBlockState(at).isAir()) level.setBlock(at, Blocks.LAVA.defaultBlockState(), QUIET);
			}
		}
		level.getFluidTicks().clearArea(new BoundingBox(x0, r.lavaY, z0, x1, r.lavaY, z1));
		for (ServerPlayer p : alive) QuickRuntime.ding(p, SoundEvents.BUCKET_EMPTY_LAVA, 0.8f, 0.8f);
		r.lavaY++;
		r.lavaGap = Math.max(r.arena.lavaFastest, r.lavaGap - r.arena.lavaSpeedUp);
		r.nextLava = now + Math.round(r.lavaGap * 1000);
		return false;
	}

	/** Round over: the lava (and any fire it lit) gone. */
	static void end(ServerLevel level, QuickRuntime.Round r) {
		clear(level, QuickRuntime.box(r.arena));
		GameRuntime.untrackLava(r.lavaKey);
		r.lavaKey = null;
	}

	private static void clear(ServerLevel level, AABB box) {
		clear(level, BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX - 1, box.maxY - 1, box.maxZ - 1));
	}

	/** Lava and fire in the box (and 3 blocks around it) become air. */
	static void clear(ServerLevel level, BlockPos a, BlockPos b) {
		BlockPos from = new BlockPos(Math.min(a.getX(), b.getX()) - 3, Math.min(a.getY(), b.getY()) - 1, Math.min(a.getZ(), b.getZ()) - 3);
		BlockPos to = new BlockPos(Math.max(a.getX(), b.getX()) + 3, Math.max(a.getY(), b.getY()) + 3, Math.max(a.getZ(), b.getZ()) + 3);
		for (BlockPos at : BlockPos.betweenClosed(from, to)) {
			BlockState st = level.getBlockState(at);
			if (st.getFluidState().is(FluidTags.LAVA) && st.is(Blocks.LAVA) || st.getBlock() instanceof BaseFireBlock) level.setBlock(at, Blocks.AIR.defaultBlockState(), QUIET);
		}
		level.getFluidTicks().clearArea(new BoundingBox(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ()));
	}
}
