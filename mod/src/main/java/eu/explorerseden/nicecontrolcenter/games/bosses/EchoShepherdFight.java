package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * The Echo Shepherd (NCC): a husk wrapped in sculk. Darkness pulses out from it, a sonic beam is marked along the
 * ground a moment before it fires (step aside), and it hunts by sound: players who sneak are only found close by.
 * Sculk spreads where it walks (gone after the fight).
 */
final class EchoShepherdFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Health:200f,CustomName:{text:\"The Echo Shepherd\",color:\"#0F6E73\",bold:true},"
			+ "equipment:{head:{id:\"minecraft:sculk_shrieker\",count:1}},drop_chances:{head:0.0},attributes:[{id:\"minecraft:max_health\",base:200},{id:\"minecraft:scale\",base:2},"
			+ "{id:\"minecraft:knockback_resistance\",base:0.8},{id:\"minecraft:follow_range\",base:40},{id:\"minecraft:step_height\",base:2},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";

	private final LivingEntity shepherd;

	EchoShepherdFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		shepherd = (LivingEntity) spawn("minecraft:husk", x, y, z, BODY, true, false);
	}

	@Override
	String name() {
		return "The Echo Shepherd";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.BLUE;
	}

	@Override
	LivingEntity body() {
		return shepherd;
	}

	@Override
	boolean alive() {
		return !dead(shepherd);
	}

	/** Who it can hear: players not sneaking within 30 blocks, sneaking ones within 6. */
	private ServerPlayer heard() {
		ServerPlayer best = null;
		for (ServerPlayer p : near(shepherd.position(), 30)) {
			if (p.isShiftKeyDown() && p.distanceToSqr(shepherd) > 6 * 6) continue;
			if (best == null || p.distanceToSqr(shepherd) < best.distanceToSqr(shepherd)) best = p;
		}
		return best;
	}

	@Override
	void tick() {
		Vec3 at = shepherd.position();
		if (ticks % 10 == 0 && shepherd instanceof Mob m) {
			ServerPlayer p = heard();
			m.setTarget(p);
			if (p == null) m.getNavigation().stop();
		}
		// Sculk where it walks.
		if (ticks % 10 == 0) {
			BlockPos b = shepherd.blockPosition();
			if (level.getBlockState(b.below()).isFaceSturdy(level, b.below(), net.minecraft.core.Direction.UP))
				tempBlock(b, Blocks.SCULK_VEIN.defaultBlockState().setValue(BlockStateProperties.DOWN, true), 6000);
		}
		// Darkness every 12 s.
		if (ticks % 240 == 0) {
			sound(at, SoundEvents.SCULK_SHRIEKER_SHRIEK, 2, 0.7f);
			for (ServerPlayer p : near(at, 30)) effect(p, MobEffects.DARKNESS, 120, 0);
		}
		// The sonic beam every 9 s at whoever it hears.
		if (ticks % 180 == 90) {
			ServerPlayer p = heard();
			if (p != null) beam(p);
		}
	}

	/** Marked along the ground for a second, then it fires down that line. */
	private void beam(ServerPlayer p) {
		Vec3 from = shepherd.position().add(0, 1.5, 0);
		Vec3 to = p.position().add(0, 1, 0);
		Vec3 dir = to.subtract(from).normalize();
		double len = Math.min(24, to.distanceTo(from) + 4);
		sound(from, SoundEvents.WARDEN_SONIC_CHARGE, 2, 1);
		for (int t = 0; t < 20; t += 4) later(t, () -> {
			for (double d = 1; d <= len; d += 0.7) {
				Vec3 at = from.add(dir.scale(d));
				level.sendParticles(ParticleTypes.SCULK_SOUL, at.x, at.y - 1.2, at.z, 1, 0.05, 0, 0.05, 0);
			}
		});
		later(20, () -> {
			if (dead(shepherd)) return;
			sound(from, SoundEvents.WARDEN_SONIC_BOOM, 3, 1);
			for (double d = 1; d <= len; d += 1) {
				Vec3 at = from.add(dir.scale(d));
				level.sendParticles(ParticleTypes.SONIC_BOOM, at.x, at.y, at.z, 1, 0, 0, 0, 0);
			}
			for (ServerPlayer q : near(from, len + 1)) {
				Vec3 rel = q.position().add(0, 1, 0).subtract(from);
				double along = rel.dot(dir);
				if (along < 0 || along > len) continue;
				if (rel.subtract(dir.scale(along)).lengthSqr() > 1.4 * 1.4) continue;
				q.hurtServer(level, level.damageSources().sonicBoom(shepherd), 10);
			}
		});
	}
}
