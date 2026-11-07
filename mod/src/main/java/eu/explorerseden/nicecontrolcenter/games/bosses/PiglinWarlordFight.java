package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * The Piglin Warlord (NCC): a big piglin brute with a golden axe. It charges in a straight line after a warning,
 * throws gold dust that blinds and sickens, calls a warband at half health, and at a quarter it turns into a
 * zombified piglin (same health): faster, hitting less hard.
 */
final class PiglinWarlordFight extends BossFight {
	private static String body(boolean zombified) {
		return "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,IsImmuneToZombification:1b,CustomName:{text:\"" + (zombified ? "The Rotting Warlord" : "The Piglin Warlord")
				+ "\",color:\"#E0A030\",bold:true},equipment:{mainhand:{id:\"minecraft:golden_axe\",count:1}},drop_chances:{mainhand:0.0},"
				+ "attributes:[{id:\"minecraft:max_health\",base:240},{id:\"minecraft:scale\",base:2},{id:\"minecraft:knockback_resistance\",base:0.8},{id:\"minecraft:follow_range\",base:40},"
				+ "{id:\"minecraft:step_height\",base:2},{id:\"minecraft:explosion_knockback_resistance\",base:1}" + (zombified
						? ",{id:\"minecraft:movement_speed\",base:0.34},{id:\"minecraft:attack_damage\",base:7}" : ",{id:\"minecraft:attack_damage\",base:12}") + "]}";
	}

	private static final String WARBAND = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,IsImmuneToZombification:1b,CustomName:\"Warband\","
			+ "equipment:{mainhand:{id:\"minecraft:golden_sword\",count:1}},drop_chances:{mainhand:0.0}}";

	private LivingEntity warlord;
	private boolean warband, zombified;
	/** The charge: where to and when it ends; who was hit by it. */
	private Vec3 charge;
	private long chargeUntil = -1;
	private final Set<ServerPlayer> charged = new HashSet<>();

	PiglinWarlordFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		warlord = (LivingEntity) spawn("minecraft:piglin_brute", x, y, z, body(false), true, false);
		if (warlord != null) warlord.setHealth(240);
	}

	@Override
	String name() {
		return "The Piglin Warlord";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.YELLOW;
	}

	@Override
	LivingEntity body() {
		return warlord;
	}

	@Override
	boolean alive() {
		return !dead(warlord);
	}

	@Override
	void tick() {
		Vec3 at = warlord.position();
		if (ticks <= chargeUntil && charge != null) {
			Vec3 dir = charge.subtract(at).multiply(1, 0, 1);
			if (dir.lengthSqr() > 1) warlord.setDeltaMovement(dir.normalize().scale(0.9).add(0, warlord.getDeltaMovement().y, 0));
			for (ServerPlayer p : near(at, 2.2)) {
				if (!charged.add(p)) continue;
				p.hurtServer(level, level.damageSources().mobAttack(warlord), 9);
				push(p, dir.normalize().scale(1.6).add(0, 0.5, 0));
			}
			if (ticks % 2 == 0) level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, at.x, at.y + 0.2, at.z, 2, 0.3, 0, 0.3, 0);
			return;
		}
		// A charge every 9 s: a line of embers marks where, then it rushes.
		if (ticks % 180 == 60) {
			ServerPlayer p = nearest(at, 24);
			if (p != null) {
				Vec3 target = p.position();
				Vec3 dir = target.subtract(at).multiply(1, 0, 1);
				Vec3 end = dir.lengthSqr() < 0.01 ? target : at.add(dir.normalize().scale(Math.sqrt(dir.lengthSqr()) + 5));
				for (int t = 0; t < 20; t += 4) later(t, () -> {
					for (double d = 0; d <= 1; d += 0.05) {
						Vec3 q = at.add(end.subtract(at).scale(d));
						level.sendParticles(ParticleTypes.SMALL_FLAME, q.x, q.y + 0.1, q.z, 1, 0, 0, 0, 0);
					}
				});
				sound(at, SoundEvents.PIGLIN_BRUTE_ANGRY, 2, 0.7f);
				later(20, () -> {
					charge = end;
					chargeUntil = ticks + 25;
					charged.clear();
					if (warlord instanceof Mob m) m.getNavigation().stop();
				});
			}
		}
		// Gold dust every 11 s at those close by.
		if (ticks % 220 == 110) {
			level.sendParticles(ParticleTypes.WAX_ON, at.x, at.y + 1.5, at.z, 60, 3, 1, 3, 0.1);
			sound(at, SoundEvents.PIGLIN_ADMIRING_ITEM, 2, 0.6f);
			for (ServerPlayer p : near(at, 6)) {
				effect(p, MobEffects.BLINDNESS, 60, 0);
				effect(p, MobEffects.NAUSEA, 120, 0);
			}
		}
		if (!warband && share(warlord) <= 0.5f) {
			warband = true;
			say(at, 40, styled("The Piglin Warlord: ", 0xE0A030, true, false).copy().append(plain("To me, my warband!")));
			for (int i = 0; i < 3; i++) {
				double a = Math.PI * 2 * i / 3;
				spawn("minecraft:piglin", at.x + Math.cos(a) * 3, at.y, at.z + Math.sin(a) * 3, WARBAND, false, false);
			}
		}
		if (!zombified && share(warlord) <= 0.25f) {
			zombified = true;
			float health = warlord.getHealth();
			warlord.discard();
			LivingEntity z = (LivingEntity) spawn("minecraft:zombified_piglin", at.x, at.y, at.z, body(true), true, false);
			if (z != null) {
				z.setHealth(health);
				warlord = z;
				if (z instanceof Mob m) m.setTarget(nearest(at, 40));
			}
			level.sendParticles(ParticleTypes.SNEEZE, at.x, at.y + 1.5, at.z, 40, 1, 1, 1, 0.05);
			sound(at, SoundEvents.PIGLIN_CONVERTED_TO_ZOMBIFIED, 3, 0.6f);
			say(at, 40, styled("The Piglin Warlord ", 0xE0A030, true, false).copy().append(plain("rots before your eyes…")));
		}
	}
}
