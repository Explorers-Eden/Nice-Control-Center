package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The Creeper King (NCC): a giant charged creeper that never blows itself up. It drops little creepers (their blasts
 * hurt but break nothing) and stomps; at a quarter of its health it starts a ten-second countdown: deal 30 damage in
 * that time and it fizzles, or it goes off with a huge blast (nothing broken) and is gone.
 */
final class CreeperKingFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Health:160f,powered:1b,Fuse:32767,ExplosionRadius:0b,"
			+ "CustomName:{text:\"The Creeper King\",color:\"#4CC94C\",bold:true},attributes:[{id:\"minecraft:max_health\",base:160},{id:\"minecraft:scale\",base:3},"
			+ "{id:\"minecraft:knockback_resistance\",base:0.8},{id:\"minecraft:follow_range\",base:40},{id:\"minecraft:step_height\",base:2},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final String LITTLE = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Fuse:32767,ExplosionRadius:0b,CustomName:\"Creeperling\","
			+ "attributes:[{id:\"minecraft:scale\",base:0.5},{id:\"minecraft:max_health\",base:6},{id:\"minecraft:movement_speed\",base:0.32}]}";

	private final LivingEntity king;
	/** Little creepers and how long a player has been close to each (they go off after a moment, like real ones). */
	private final Map<Entity, Integer> little = new HashMap<>();
	private boolean countdownDone;
	private long countdownEnd = -1;
	private float hitDuring;

	CreeperKingFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		king = (LivingEntity) spawn("minecraft:creeper", x, y, z, BODY, true, false);
	}

	@Override
	String name() {
		return "The Creeper King";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.GREEN;
	}

	@Override
	LivingEntity body() {
		return king;
	}

	@Override
	boolean alive() {
		return !dead(king);
	}

	@Override
	void damaged(LivingEntity piece, DamageSource source, float amount) {
		if (piece == king) took(amount);
	}

	@Override
	void tick() {
		Vec3 at = king.position();
		// Little creepers: a player within 2 blocks for 1.5 s and it goes off (hurts, breaks nothing).
		for (Entity c : new ArrayList<>(little.keySet())) {
			if (dead(c)) {
				little.remove(c);
				continue;
			}
			boolean close = !near(c.position(), 2.2).isEmpty();
			int t = close ? little.get(c) + 1 : 0;
			little.put(c, t);
			if (t == 1) sound(c.position(), SoundEvents.CREEPER_PRIMED, 1, 1.4f);
			if (t >= 30) {
				Vec3 p = c.position();
				c.discard();
				little.remove(c);
				blast(king, p, 2.2f);
			}
		}
		if (countdownEnd > 0) {
			countdown(at);
			return;
		}
		// Creeperlings every 8 s, three at a time (up to nine).
		if (ticks % 160 == 40 && little.size() < 9) {
			sound(at, SoundEvents.CREEPER_HURT, 2, 0.5f);
			for (int i = 0; i < 3; i++) {
				Entity c = spawn("minecraft:creeper", at.x + random.nextGaussian(), at.y + 1, at.z + random.nextGaussian(), LITTLE, false, false);
				if (c != null) little.put(c, 0);
			}
		}
		// A stomp every 10 s.
		if (ticks % 200 == 100 && !near(at, 6).isEmpty()) {
			for (int t = 0; t < 20; t += 5) later(t, () -> telegraph(king.position(), 5, ParticleTypes.SMOKE));
			later(20, () -> {
				sound(king.position(), SoundEvents.GENERIC_EXPLODE.value(), 1.5f, 0.6f);
				shockwave(king, king.position(), 6, 5, 1.4, 0.5);
			});
		}
		if (!countdownDone && share(king) <= 0.25f) {
			countdownDone = true;
			countdownEnd = ticks + 200;
			hitDuring = 0;
			say(at, 40, styled("The Creeper King ", 0x4CC94C, true, false).copy().append(plain("is swelling up! Hit it hard (30 damage) within 10 seconds!")));
		}
	}

	/** The countdown: interrupted by 30 damage, else the big blast. */
	private void countdown(Vec3 at) {
		long left = countdownEnd - ticks;
		if (left % 20 == 0) {
			sound(at, SoundEvents.CREEPER_PRIMED, 3, 0.5f + (200 - left) / 400f);
			level.sendParticles(ParticleTypes.SMOKE, at.x, at.y + 2, at.z, 30, 1.5, 1.5, 1.5, 0.02);
			for (ServerPlayer p : near(at, 40)) p.sendOverlayMessage(net.minecraft.network.chat.Component.literal("The Creeper King goes off in " + (left / 20) + "…")
					.withStyle(net.minecraft.ChatFormatting.GREEN));
		}
		if (left > 0) return;
		countdownEnd = -1;
		blast(king, at, 6f);
		king.discard();
	}

	/** Damage taken while swelling: 30 interrupts it. */
	void took(float amount) {
		if (countdownEnd < 0) return;
		hitDuring += amount;
		if (hitDuring >= 30 * healthScale) {
			countdownEnd = -1;
			Vec3 at = king.position();
			sound(at, SoundEvents.FIRE_EXTINGUISH, 2, 0.6f);
			level.sendParticles(ParticleTypes.CLOUD, at.x, at.y + 2, at.z, 40, 1.5, 1.5, 1.5, 0.05);
			say(at, 40, styled("The Creeper King ", 0x4CC94C, true, false).copy().append(plain("fizzles out!")));
		}
	}
}
