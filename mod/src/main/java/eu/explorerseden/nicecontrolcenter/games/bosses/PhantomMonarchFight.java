package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * The Phantom Monarch (NCC): a huge phantom. Its shadow on the ground shows where it dives; after a dive that hits it
 * is dazed and drops to the ground for three seconds (the chance for swords). Every 25 seconds its night court of four
 * phantoms comes, and whoever it hits is weakened and slowed. The sun doesn't burn it.
 */
final class PhantomMonarchFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Health:160f,CustomName:{text:\"The Phantom Monarch\",color:\"#6C8EBF\",bold:true},"
			+ "active_effects:[{id:\"minecraft:fire_resistance\",amplifier:0,duration:-1,show_particles:0b}],attributes:[{id:\"minecraft:max_health\",base:160},{id:\"minecraft:scale\",base:4},"
			+ "{id:\"minecraft:knockback_resistance\",base:0.5},{id:\"minecraft:follow_range\",base:64},{id:\"minecraft:attack_damage\",base:8},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final String COURT = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,CustomName:\"Night Courtier\",active_effects:[{id:\"minecraft:fire_resistance\",amplifier:0,duration:-1,show_particles:0b}]}";

	private final LivingEntity monarch;
	private final List<Entity> court = new ArrayList<>();
	private long dazedUntil = -1;

	PhantomMonarchFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		monarch = (LivingEntity) spawn("minecraft:phantom", x, y + 8, z, BODY, true, false);
	}

	@Override
	String name() {
		return "The Phantom Monarch";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.BLUE;
	}

	@Override
	LivingEntity body() {
		return monarch;
	}

	@Override
	boolean alive() {
		return !dead(monarch);
	}

	/** It hit someone: slowed and weakened; the monarch itself is dazed and falls. */
	@Override
	void hit(ServerPlayer victim, Entity attacker) {
		effect(victim, MobEffects.SLOWNESS, 100, 1);
		effect(victim, MobEffects.WEAKNESS, 100, 0);
		if (attacker != monarch || ticks < dazedUntil) return;
		dazedUntil = ticks + 60;
		if (monarch instanceof Mob m) m.setNoAi(true);
		monarch.setNoGravity(false);
		sound(monarch.position(), SoundEvents.PHANTOM_HURT, 2, 0.5f);
	}

	@Override
	void tick() {
		court.removeIf(BossFight::dead);
		Vec3 at = monarch.position();
		if (dazedUntil > 0) {
			if (ticks % 5 == 0) level.sendParticles(ParticleTypes.CRIT, at.x, at.y + 1, at.z, 6, 1, 0.5, 1, 0);
			if (ticks >= dazedUntil) {
				dazedUntil = -1;
				if (monarch instanceof Mob m) m.setNoAi(false);
				monarch.setNoGravity(true);
				monarch.setDeltaMovement(0, 0.6, 0);
			}
			return;
		}
		// Its shadow while it dives.
		if (monarch.getDeltaMovement().y < -0.15 && ticks % 2 == 0) {
			Vec3 ground = Vec3.atBottomCenterOf(groundAt(at));
			telegraph(ground, 2, ParticleTypes.SQUID_INK);
		}
		if (ticks % 20 == 0 && monarch instanceof Mob m && (m.getTarget() == null || !m.getTarget().isAlive())) m.setTarget(nearest(at, 64));
		// The night court every 25 s.
		if (ticks % 500 == 200 && court.size() < 8) {
			say(at, 40, styled("The Phantom Monarch ", 0x6C8EBF, true, false).copy().append(plain("calls its night court.")));
			sound(at, SoundEvents.PHANTOM_AMBIENT, 3, 0.5f);
			for (int i = 0; i < 4; i++) {
				Entity c = spawn("minecraft:phantom", at.x + random.nextGaussian() * 3, at.y, at.z + random.nextGaussian() * 3, COURT, false, false);
				if (c != null) court.add(c);
			}
		}
	}
}
