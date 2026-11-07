package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.phys.Vec3;

/**
 * The Weeping Ghast (NCC): a huge ghast high above the arena. Fireballs rain where the ground warned, its tears
 * leave slowing clouds below, little ghasts come to help. Its own fireballs hit back at it are what really hurts it
 * (20 each); arrows and blows only a little. Its fireballs break nothing.
 */
final class WeepingGhastFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,ExplosionPower:0b,Health:120f,CustomName:{text:\"The Weeping Ghast\",color:\"#E6E6FA\",bold:true},"
			+ "attributes:[{id:\"minecraft:max_health\",base:120},{id:\"minecraft:scale\",base:2.5},{id:\"minecraft:follow_range\",base:64},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final String LITTLE = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,ExplosionPower:0b,CustomName:\"Little Ghast\",attributes:[{id:\"minecraft:scale\",base:0.6}]}";

	private final LivingEntity ghast;
	private final Vec3 home;
	private final List<Entity> little = new ArrayList<>();

	WeepingGhastFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		// High up, but inside the box when there is one.
		double up = bounds == null ? y + 10 : Math.max(y + 2, Math.min(y + 10, bounds.maxY - 6));
		home = new Vec3(x, up, z);
		ghast = (LivingEntity) spawn("minecraft:ghast", x, up, z, BODY, true, false);
	}

	@Override
	String name() {
		return "The Weeping Ghast";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.WHITE;
	}

	@Override
	LivingEntity body() {
		return ghast;
	}

	@Override
	boolean alive() {
		return !dead(ghast);
	}

	/** A fireball hit back: 20 (the game would kill any ghast outright). */
	@Override
	boolean allowDamage(LivingEntity piece, DamageSource source, float amount) {
		if (piece == ghast && source.getDirectEntity() instanceof LargeFireball) {
			hurtAs(ghast, 20, source.getEntity());
			Vec3 at = ghast.position();
			level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 2, at.z, 3, 1, 1, 1, 0);
			return false;
		}
		return true;
	}

	@Override
	void tick() {
		little.removeIf(BossFight::dead);
		Vec3 at = ghast.position();
		// Drifting too far from its spot: pulled back gently.
		Vec3 back = home.subtract(at);
		if (back.horizontalDistanceSqr() > 14 * 14 || Math.abs(back.y) > 6) ghast.setDeltaMovement(back.normalize().scale(0.15));
		// Fireball rain every 7 s: three marked spots near players.
		if (ticks % 140 == 60) {
			List<ServerPlayer> ps = near(at, 48);
			for (int i = 0; i < 3 && !ps.isEmpty(); i++) {
				ServerPlayer p = ps.get(random.nextInt(ps.size()));
				Vec3 spot = Vec3.atBottomCenterOf(groundAt(p.position().add(random.nextGaussian() * 2, 0, random.nextGaussian() * 2)));
				for (int t = 0; t < 30; t += 5) later(i * 6 + t, () -> telegraph(spot, 2, ParticleTypes.FLAME));
				later(i * 6 + 30, () -> {
					level.sendParticles(ParticleTypes.FLAME, spot.x, spot.y + 0.5, spot.z, 30, 1, 0.5, 1, 0.05);
					blast(ghast, spot, 1.8f);
				});
			}
		}
		// Tears every 5 s: a slowing cloud on the ground below it.
		if (ticks % 100 == 30) {
			Vec3 drop = at.add(random.nextGaussian() * 3, 0, random.nextGaussian() * 3);
			Vec3 ground = Vec3.atBottomCenterOf(groundAt(drop.subtract(0, Math.max(0, drop.y - home.y + 1), 0)));
			for (int i = 0; i < 6; i++) level.sendParticles(ParticleTypes.FALLING_WATER, drop.x, at.y - i, drop.z, 6, 0.4, 0.2, 0.4, 0);
			spawn("minecraft:area_effect_cloud", ground.x, ground.y, ground.z, "{custom_particle:{type:\"falling_water\"},ReapplicationDelay:10,Radius:2.5f,Duration:120,"
					+ "potion_contents:{custom_color:9418729,custom_effects:[{id:\"minecraft:slowness\",amplifier:1,duration:80}]}}", false, true);
			sound(at, SoundEvents.GHAST_AMBIENT, 2, 0.6f);
		}
		// Two little ghasts every 25 s (up to four).
		if (ticks % 500 == 250 && little.size() < 4) {
			sound(at, SoundEvents.GHAST_SCREAM, 3, 0.8f);
			for (int i = 0; i < 2; i++) {
				Entity g = spawn("minecraft:ghast", at.x + random.nextGaussian() * 3, at.y - 1, at.z + random.nextGaussian() * 3, LITTLE, false, false);
				if (g != null) little.add(g);
			}
		}
	}
}
