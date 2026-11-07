package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.List;

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
 * The Ender Stalker (NCC): a tall enderman. It blinks behind a player and strikes, makes two players swap places,
 * sends endermites, and grows stronger while someone stares at it: look away to fight it. It never takes blocks.
 */
final class EnderStalkerFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Health:200f,CustomName:{text:\"The Ender Stalker\",color:\"#B266FF\",bold:true},"
			+ "attributes:[{id:\"minecraft:max_health\",base:200},{id:\"minecraft:scale\",base:2},{id:\"minecraft:knockback_resistance\",base:0.6},{id:\"minecraft:follow_range\",base:48},"
			+ "{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final String MITE = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,CustomName:\"Stalker's Brood\"}";

	private final LivingEntity stalker;
	/** Where it started: blinks stay within 24 blocks of it (inside the arena or the dungeon room). */
	private final Vec3 home;

	EnderStalkerFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		home = new Vec3(x, y, z);
		stalker = (LivingEntity) spawn("minecraft:enderman", x, y, z, BODY, true, false);
	}

	@Override
	String name() {
		return "The Ender Stalker";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.PURPLE;
	}

	@Override
	LivingEntity body() {
		return stalker;
	}

	@Override
	boolean alive() {
		return !dead(stalker);
	}

	@Override
	void tick() {
		Vec3 at = stalker.position();
		// Gone too far (its own teleports): back.
		if (ticks % 20 == 0 && at.distanceToSqr(home) > 28 * 28) blinkTo(home);
		// Stared at: stronger.
		if (ticks % 10 == 0) {
			boolean stared = false;
			for (ServerPlayer p : near(at, 40)) {
				Vec3 to = stalker.getEyePosition().subtract(p.getEyePosition()).normalize();
				if (p.getLookAngle().dot(to) > 0.97) stared = true;
			}
			if (stared) effect(stalker, MobEffects.STRENGTH, 30, 1);
		}
		// A blink behind a player every 8 s.
		if (ticks % 160 == 40) {
			ServerPlayer p = nearest(at, 30);
			if (p != null) {
				Vec3 behind = p.position().subtract(p.getLookAngle().multiply(1, 0, 1).normalize().scale(2));
				if (behind.distanceToSqr(home) <= 24 * 24) {
					blinkTo(behind);
					if (stalker instanceof Mob m) m.setTarget(p);
					later(6, () -> {
						if (!dead(stalker) && stalker.distanceToSqr(p) < 16) p.hurtServer(level, level.damageSources().mobAttack(stalker), 7);
					});
				}
			}
		}
		// Two players swap places every 15 s.
		if (ticks % 300 == 150) {
			List<ServerPlayer> ps = near(at, 30);
			if (ps.size() >= 2) {
				ServerPlayer a = ps.get(random.nextInt(ps.size())), b = ps.get(random.nextInt(ps.size()));
				if (a != b) {
					Vec3 pa = a.position(), pb = b.position();
					a.teleportTo(pb.x, pb.y, pb.z);
					b.teleportTo(pa.x, pa.y, pa.z);
					for (Vec3 v : List.of(pa, pb)) level.sendParticles(ParticleTypes.PORTAL, v.x, v.y + 1, v.z, 40, 0.4, 1, 0.4, 0.5);
					sound(pa, SoundEvents.ENDERMAN_TELEPORT, 1.5f, 0.6f);
				}
			}
		}
		// Endermites every 12 s (up to six).
		if (ticks % 240 == 120 && level.getEntitiesOfClass(LivingEntity.class, around(stalker, 32), e -> e.getCustomName() != null && e.getCustomName().getString().equals("Stalker's Brood")).size() < 6) {
			for (int i = 0; i < 3; i++) spawn("minecraft:endermite", at.x + random.nextGaussian() * 2, at.y, at.z + random.nextGaussian() * 2, MITE, false, false);
		}
	}

	private void blinkTo(Vec3 to) {
		Vec3 from = stalker.position();
		level.sendParticles(ParticleTypes.PORTAL, from.x, from.y + 1.5, from.z, 40, 0.5, 1.5, 0.5, 0.5);
		stalker.teleportTo(to.x, to.y, to.z);
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, to.x, to.y + 1.5, to.z, 40, 0.5, 1.5, 0.5, 0.1);
		sound(to, SoundEvents.ENDERMAN_TELEPORT, 1.5f, 0.7f);
	}
}
