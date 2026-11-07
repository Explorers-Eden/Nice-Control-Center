package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.List;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The Rotting Giant (NCC): the giant zombie, walked by the mod. It plods towards the players, stomps (the ground
 * warns first), throws zombies where it marked, lifts a player up and lets them fall, and every 30 seconds sinks to
 * its knees to rest: four seconds of double damage.
 */
final class RottingGiantFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Health:180f,CustomName:{text:\"The Rotting Giant\",color:\"#5E8C3A\",bold:true},"
			+ "attributes:[{id:\"minecraft:max_health\",base:180},{id:\"minecraft:scale\",base:0.6},{id:\"minecraft:knockback_resistance\",base:1},{id:\"minecraft:step_height\",base:2},"
			+ "{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final String ZOMBIE = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,CustomName:\"Zombie Spawn\"}";
	/** Its size standing and kneeling. */
	private static final double TALL = 0.6, KNEEL = 0.35;

	private final LivingEntity giant;
	private long restUntil = -1;

	RottingGiantFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		giant = (LivingEntity) spawn("minecraft:giant", x, y, z, BODY, true, false);
	}

	@Override
	String name() {
		return "The Rotting Giant";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.GREEN;
	}

	@Override
	LivingEntity body() {
		return giant;
	}

	@Override
	boolean alive() {
		return !dead(giant);
	}

	private boolean resting() {
		return ticks < restUntil;
	}

	/** Double damage while it rests on its knees. */
	@Override
	boolean allowDamage(LivingEntity piece, DamageSource source, float amount) {
		if (piece == giant && resting() && amount > 0) {
			hurtAs(giant, amount * 2, source.getEntity());
			return false;
		}
		return true;
	}

	private void size(double scale) {
		var a = giant.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.SCALE);
		if (a != null) a.setBaseValue(scale);
	}

	@Override
	void tick() {
		Vec3 at = giant.position();
		if (resting()) {
			if (ticks % 10 == 0) level.sendParticles(ParticleTypes.SNEEZE, at.x, at.y + 3, at.z, 6, 1, 0.5, 1, 0);
			if (ticks == restUntil - 1) size(TALL);
			return;
		}
		ServerPlayer p = nearest(at, 40);
		// Plodding on, facing whoever is closest.
		if (p != null) {
			Vec3 d = p.position().subtract(at).multiply(1, 0, 1);
			float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90);
			giant.setYRot(yaw);
			giant.setYHeadRot(yaw);
			if (d.lengthSqr() > 9) giant.setDeltaMovement(d.normalize().scale(0.09).add(0, giant.getDeltaMovement().y, 0));
			// A swipe at whoever is right at its feet.
			if (ticks % 20 == 0 && d.lengthSqr() < 3.5 * 3.5) {
				p.hurtServer(level, level.damageSources().mobAttack(giant), 6);
				push(p, d.normalize().scale(0.8).add(0, 0.4, 0));
			}
			if (ticks % 20 == 0) sound(at, SoundEvents.ZOMBIE_AMBIENT, 1.5f, 0.4f);
		}
		// A stomp every 8 s when someone is close.
		if (ticks % 160 == 40 && !near(at, 8).isEmpty()) {
			for (int t = 0; t < 20; t += 5) later(t, () -> telegraph(giant.position(), 6, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DIRT.defaultBlockState())));
			later(20, () -> {
				sound(giant.position(), SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, 3, 0.4f);
				shockwave(giant, giant.position(), 7, 6, 1.0, 0.6);
			});
		}
		// A thrown zombie every 10 s at a marked spot.
		if (ticks % 200 == 100 && p != null) {
			Vec3 spot = Vec3.atBottomCenterOf(groundAt(p.position()));
			for (int t = 0; t < 25; t += 5) later(t, () -> telegraph(spot, 1.5, ParticleTypes.SMOKE));
			later(25, () -> {
				Vec3 from = giant.position().add(0, 6, 0);
				Entity z = spawn("minecraft:zombie", from.x, from.y, from.z, ZOMBIE, false, false);
				if (z != null) {
					Vec3 d = spot.subtract(from);
					z.setDeltaMovement(d.x / 20, 0.5, d.z / 20);
				}
				sound(from, SoundEvents.ZOMBIE_INFECT, 2, 0.5f);
			});
		}
		// The grab every 15 s: the nearest player is lifted and let go.
		if (ticks % 300 == 200) {
			ServerPlayer g = nearest(at, 6);
			if (g != null) {
				effect(g, MobEffects.LEVITATION, 30, 3);
				sound(g.position(), SoundEvents.ZOMBIE_ATTACK_IRON_DOOR, 1.5f, 0.6f);
			}
		}
		// Resting every 30 s: on its knees for 4 s.
		if (ticks % 600 == 500) {
			restUntil = ticks + 80;
			size(KNEEL);
			giant.setDeltaMovement(Vec3.ZERO);
			say(at, 40, styled("The Rotting Giant ", 0x5E8C3A, true, false).copy().append(plain("sinks to its knees, panting. Now!")));
		}
	}
}
