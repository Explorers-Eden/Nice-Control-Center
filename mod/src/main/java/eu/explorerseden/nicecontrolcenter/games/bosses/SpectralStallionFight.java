package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The Spectral Stallion (NCC): a great skeleton horse. It gallops laps round the fight trampling whoever is in the
 * way, sets lightning traps under players, and calls a ghost herd in its last third.
 */
final class SpectralStallionFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Tame:0b,Health:180f,CustomName:{text:\"The Spectral Stallion\",color:\"#A9D6E5\",bold:true},"
			+ "attributes:[{id:\"minecraft:max_health\",base:180},{id:\"minecraft:scale\",base:2.2},{id:\"minecraft:knockback_resistance\",base:0.9},{id:\"minecraft:step_height\",base:2},"
			+ "{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final String GHOST = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Tame:0b,Glowing:1b,CustomName:\"Ghost Horse\",attributes:[{id:\"minecraft:max_health\",base:16}]}";

	private final LivingEntity stallion;
	private final Vec3 home;
	private long lapUntil = -1;
	private double lapAngle;
	private final Set<ServerPlayer> trampled = new HashSet<>();
	private long trampleReset;
	private boolean herd;

	SpectralStallionFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		stallion = (LivingEntity) spawn("minecraft:skeleton_horse", x, y, z, BODY, true, false);
		home = bounds != null ? bounds.getCenter() : new Vec3(x, y, z);
	}

	@Override
	String name() {
		return "The Spectral Stallion";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.BLUE;
	}

	@Override
	LivingEntity body() {
		return stallion;
	}

	@Override
	boolean alive() {
		return !dead(stallion);
	}

	/** The lap's radius: inside the box, else 9 blocks. */
	private double radius() {
		if (bounds == null) return 9;
		return Math.max(4, Math.min(bounds.getXsize(), bounds.getZsize()) / 2 - 2.5);
	}

	@Override
	void tick() {
		Vec3 at = stallion.position();
		if (ticks % 10 == 0) level.sendParticles(ParticleTypes.SOUL, at.x, at.y + 1.5, at.z, 2, 0.5, 0.5, 0.5, 0.01);
		if (!herd && share(stallion) < 0.33f) {
			herd = true;
			for (int i = 0; i < 3; i++) minion("minecraft:skeleton_horse", at.x + random.nextGaussian() * 2, at.y, at.z + random.nextGaussian() * 2, GHOST, 4);
			sound(at, snd("entity.skeleton_horse.ambient"), 2, 0.5f);
			say(at, 40, styled("The Spectral Stallion ", 0xA9D6E5, true, false).copy().append(plain("calls its ghost herd!")));
		}
		// A lap: round the middle, trampling.
		if (ticks <= lapUntil) {
			lapAngle += 0.09;
			Vec3 to = new Vec3(home.x + Math.cos(lapAngle) * radius(), at.y, home.z + Math.sin(lapAngle) * radius());
			Vec3 d = to.subtract(at).multiply(1, 0, 1);
			if (d.lengthSqr() > 0.01) stallion.setDeltaMovement(d.normalize().scale(0.9).add(0, stallion.getDeltaMovement().y, 0));
			face(stallion, to);
			if (ticks > trampleReset) {
				trampled.clear();
				trampleReset = ticks + 20;
			}
			for (ServerPlayer p : near(at, 2.2)) {
				if (!trampled.add(p)) continue;
				p.hurtServer(level, level.damageSources().mobAttack(stallion), 5);
				push(p, p.position().subtract(at).multiply(1, 0, 1).normalize().scale(1.4).add(0, 0.6, 0));
			}
			if (ticks % 4 == 0) level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, at.x, at.y + 0.2, at.z, 3, 0.4, 0.1, 0.4, 0.01);
			return;
		}
		ServerPlayer p = nearest(at, 32);
		if (p == null) return;
		stride(stallion, p, 0.26, 2, 5);
		// A lap every 15 s, 6 s long.
		if (ticks % 300 == 100) {
			lapAngle = Math.atan2(at.z - home.z, at.x - home.x);
			lapUntil = ticks + 120;
			sound(at, snd("entity.horse.gallop"), 2, 0.5f);
			say(at, 40, styled("The Spectral Stallion ", 0xA9D6E5, true, false).copy().append(plain("breaks into a gallop!")));
		}
		// Lightning traps every 10 s under up to three players: 1.5 s warning, then 6 damage.
		if (ticks % 200 == 40) {
			int n = 0;
			for (ServerPlayer q : near(at, 28)) {
				if (n++ >= 3) break;
				Vec3 spot = Vec3.atBottomCenterOf(groundAt(q.position()));
				for (int t = 0; t < 30; t += 5) later(t, () -> telegraph(spot, 1.5, ParticleTypes.ELECTRIC_SPARK));
				later(30, () -> {
					Entity bolt = spawn("minecraft:lightning_bolt", spot.x, spot.y, spot.z, "{}", false, true);
					if (bolt instanceof net.minecraft.world.entity.LightningBolt lb) lb.setVisualOnly(true);
					for (ServerPlayer hit : near(spot, 1.8)) hit.hurtServer(level, level.damageSources().mobAttack(stallion), 6);
				});
			}
		}
	}
}
