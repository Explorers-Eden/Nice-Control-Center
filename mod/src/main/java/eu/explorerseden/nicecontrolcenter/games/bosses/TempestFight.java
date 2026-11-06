package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.List;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The Tempest (NCC): a big breeze. Wind bursts land where gusts warned of them, it lifts a player into the air now
 * and then, and every 20 seconds a whirlwind pulls everyone to it before a shockwave throws them out again. Like any
 * breeze it turns arrows away: this one is fought up close.
 */
final class TempestFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Health:160f,CustomName:{text:\"The Tempest\",color:\"#9FE2BF\",bold:true},"
			+ "attributes:[{id:\"minecraft:max_health\",base:160},{id:\"minecraft:scale\",base:2.5},{id:\"minecraft:knockback_resistance\",base:0.8},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";

	private final LivingEntity breeze;
	/** The whirlwind's end (pulling until then, then the shockwave). */
	private long whirlUntil = -1;

	TempestFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		breeze = (LivingEntity) spawn("minecraft:breeze", x, y, z, BODY, true, false);
	}

	@Override
	String name() {
		return "The Tempest";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.GREEN;
	}

	@Override
	LivingEntity body() {
		return breeze;
	}

	@Override
	boolean alive() {
		return !dead(breeze);
	}

	@Override
	void tick() {
		Vec3 at = breeze.position();
		if (ticks <= whirlUntil) {
			// Pulled in, round and round.
			for (ServerPlayer p : near(at, 18)) {
				Vec3 in = at.subtract(p.position()).multiply(1, 0, 1);
				if (in.lengthSqr() < 1) continue;
				Vec3 side = new Vec3(-in.z, 0, in.x).normalize().scale(0.15);
				push(p, in.normalize().scale(0.35).add(side).add(0, 0.05, 0));
			}
			if (ticks % 2 == 0) level.sendParticles(ParticleTypes.GUST, at.x, at.y + 1, at.z, 6, 3, 1, 3, 0);
			if (ticks == whirlUntil) {
				sound(at, SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), 3, 0.6f);
				shockwave(breeze, at, 8, 4, 2.2, 0.9);
			}
			return;
		}
		// A volley of three gusts every 5 s, at players up to 24 blocks away.
		if (ticks % 100 == 0) {
			List<ServerPlayer> ps = near(at, 24);
			for (int i = 0; i < 3 && !ps.isEmpty(); i++) {
				ServerPlayer p = ps.get(random.nextInt(ps.size()));
				Vec3 spot = p.position().add(random.nextGaussian(), 0, random.nextGaussian());
				for (int t = 0; t < 20; t += 5) later(i * 6 + t, () -> telegraph(spot, 1.5, ParticleTypes.GUST_EMITTER_SMALL));
				later(i * 6 + 20, () -> windBurst(spot));
			}
		}
		// Lifted: one player floats up for 2 s.
		if (ticks % 160 == 80) {
			ServerPlayer p = nearest(at, 20);
			if (p != null) {
				effect(p, MobEffects.LEVITATION, 40, 1);
				sound(p.position(), SoundEvents.BREEZE_JUMP, 1.5f, 0.8f);
			}
		}
		// The whirlwind every 20 s.
		if (ticks % 400 == 200 && !near(at, 18).isEmpty()) {
			whirlUntil = ticks + 60;
			say(at, 40, styled("The Tempest ", 0x9FE2BF, true, false).copy().append(plain("draws in the wind…")));
			sound(at, SoundEvents.BREEZE_INHALE, 3, 0.5f);
		}
	}
}
