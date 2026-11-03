package eu.explorerseden.nicecontrolcenter.games;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import eu.explorerseden.nicecontrolcenter.Games.Arena;
import eu.explorerseden.nicecontrolcenter.claims.GameClaims;

/**
 * Target practice (quick round "targets"): target blocks appear in the play box (on its walls, or floating anywhere
 * in it) for a few seconds; an arrow (or any shot) in one scores 1 + a point per 10 blocks from the spawn, twice that
 * for a bullseye. Targets only go where there was air, and back to air.
 */
final class Targets {
	private Targets() {
	}

	static void tick(ServerLevel level, QuickRuntime.Round r, List<ServerPlayer> alive) {
		Arena a = r.arena;
		long now = System.currentTimeMillis();
		for (Iterator<Map.Entry<BlockPos, Long>> it = r.targets.entrySet().iterator(); it.hasNext();) {
			Map.Entry<BlockPos, Long> e = it.next();
			if (now < e.getValue()) continue;
			BlockPos at = e.getKey();
			GameRuntime.untrack(level, at);
			level.sendParticles(ParticleTypes.SMOKE, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.01);
			it.remove();
		}
		// Kit rounds keep the arrows coming.
		if (QuickRuntime.kit(a) && level.getGameTime() % 20 == 0) {
			for (ServerPlayer p : alive) {
				int have = p.getInventory().countItem(Items.ARROW);
				if (have < 8) GameClaims.giveKitItem(p, new ItemStack(Items.ARROW, 16 - have), "quick");
			}
		}
		if (now < r.nextTarget || r.targets.size() >= a.maxAlive) return;
		Random random = QuickRuntime.random();
		r.nextTarget = now + 400 + random.nextInt(800);
		BlockPos at = spot(level, r);
		if (at == null) return;
		level.setBlock(at, Blocks.TARGET.defaultBlockState(), 3);
		GameRuntime.track(level, at);
		r.targets.put(at, now + Math.round(a.targetSeconds * 1000));
		level.sendParticles(ParticleTypes.END_ROD, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 4, 0.3, 0.3, 0.3, 0.02);
		level.playSound(null, at, SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.BLOCKS, 0.7f, 1.6f);
	}

	/** An air spot in the box, at least 6 blocks from the spawn: against a wall (walls) or anywhere. */
	private static BlockPos spot(ServerLevel level, QuickRuntime.Round r) {
		Arena a = r.arena;
		AABB box = QuickRuntime.box(a);
		Random random = QuickRuntime.random();
		Vec3 stand = a.spawn == null ? box.getCenter() : new Vec3(a.spawn.x, a.spawn.y, a.spawn.z);
		for (int tries = 0; tries < 24; tries++) {
			BlockPos at = BlockPos.containing(box.minX + random.nextDouble() * (box.maxX - box.minX), box.minY + random.nextDouble() * (box.maxY - box.minY),
					box.minZ + random.nextDouble() * (box.maxZ - box.minZ));
			if (!level.getBlockState(at).isAir()) continue;
			if (a.targetMode.equals("walls")) {
				// Along one way to the first block: the target goes in front of it.
				Direction d = Direction.from2DDataValue(random.nextInt(4));
				BlockPos last = null;
				for (int i = 0; i < 64; i++) {
					BlockPos next = at.relative(d, i + 1);
					if (!box.inflate(1).contains(Vec3.atCenterOf(next))) break;
					if (!level.getBlockState(next).isAir()) {
						if (level.getBlockState(next).isSolidRender()) last = at.relative(d, i);
						break;
					}
				}
				if (last == null) continue;
				at = last;
			}
			if (Vec3.atCenterOf(at).distanceTo(stand) < 6 || r.targets.containsKey(at) || !box.contains(Vec3.atCenterOf(at))) continue;
			boolean crowded = false;
			for (BlockPos o : r.targets.keySet()) crowded |= o.distSqr(at) < 4;
			if (!crowded) return at;
		}
		return null;
	}

	/** A shot by a player in the round, from → to this tick: true when it hit a target (the shot is used up). */
	static boolean shot(ServerLevel level, QuickRuntime.Round r, ServerPlayer p, Projectile shot, Vec3 from, Vec3 to) {
		for (Iterator<Map.Entry<BlockPos, Long>> it = r.targets.entrySet().iterator(); it.hasNext();) {
			BlockPos at = it.next().getKey();
			AABB block = new AABB(at);
			Optional<Vec3> hit = block.inflate(0.05).contains(from) ? Optional.of(from) : block.clip(from, to);
			if (hit.isEmpty()) continue;
			it.remove();
			GameRuntime.untrack(level, at);
			shot.discard();
			// The middle of the face it went into: within a sixth of a block is a bullseye.
			Vec3 h = hit.get(), c = Vec3.atCenterOf(at);
			Vec3 off = h.subtract(c);
			double ax = Math.abs(off.x), ay = Math.abs(off.y), az = Math.abs(off.z);
			double rest = ax >= ay && ax >= az ? Math.hypot(off.y, off.z) : ay >= az ? Math.hypot(off.x, off.z) : Math.hypot(off.x, off.y);
			boolean bull = rest < 0.17;
			Arena a = r.arena;
			double far = a.spawn == null ? 0 : c.distanceTo(new Vec3(a.spawn.x, a.spawn.y, a.spawn.z));
			long points = (1 + Math.round(far / 10)) * (bull ? 2 : 1);
			QuickRuntime.add(r, p, points, bull ? "bullseye!" : null);
			level.sendParticles(bull ? ParticleTypes.FIREWORK : ParticleTypes.CRIT, c.x, c.y, c.z, bull ? 16 : 10, 0.3, 0.3, 0.3, 0.15);
			QuickRuntime.ding(p, SoundEvents.ARROW_HIT_PLAYER, 0.8f, bull ? 1.6f : 1.0f);
			return true;
		}
		return false;
	}

	/** Every target still up gone (round over). */
	static void clear(ServerLevel level, QuickRuntime.Round r) {
		for (BlockPos at : r.targets.keySet()) GameRuntime.untrack(level, at);
		r.targets.clear();
	}

	static boolean isTarget(ServerLevel level, BlockPos pos) {
		for (QuickRuntime.Round r : QuickRuntime.allRounds()) if (r.targets.containsKey(pos)) return true;
		return false;
	}
}
