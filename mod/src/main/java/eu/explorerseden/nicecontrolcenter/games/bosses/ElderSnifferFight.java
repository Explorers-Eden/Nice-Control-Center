package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.phys.Vec3;

/**
 * The Elder Sniffer (NCC): an ancient sniffer that sniffs out whoever keeps farthest away and goes for them. It digs
 * up buried mines (marked spots that burst three seconds later, breaking nothing), grows thornbush walls, and plants
 * ancient seed turrets that spit at players until they're broken.
 */
final class ElderSnifferFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Health:200f,CustomName:{text:\"The Elder Sniffer\",color:\"#C7553B\",bold:true},"
			+ "attributes:[{id:\"minecraft:max_health\",base:200},{id:\"minecraft:scale\",base:2},{id:\"minecraft:knockback_resistance\",base:1},{id:\"minecraft:step_height\",base:1.5},"
			+ "{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";

	private final LivingEntity sniffer;

	ElderSnifferFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		sniffer = (LivingEntity) spawn("minecraft:sniffer", x, y, z, BODY, true, false);
	}

	@Override
	String name() {
		return "The Elder Sniffer";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.RED;
	}

	@Override
	LivingEntity body() {
		return sniffer;
	}

	@Override
	boolean alive() {
		return !dead(sniffer);
	}

	/** Whoever keeps farthest away (within 40 blocks). */
	private ServerPlayer farthest(Vec3 at) {
		ServerPlayer far = null;
		for (ServerPlayer p : near(at, 40)) if (far == null || p.position().distanceToSqr(at) > far.position().distanceToSqr(at)) far = p;
		return far;
	}

	@Override
	void tick() {
		Vec3 at = sniffer.position();
		ServerPlayer prey = farthest(at);
		if (prey != null) {
			Vec3 d = prey.position().subtract(at).multiply(1, 0, 1);
			float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90);
			sniffer.setYRot(yaw);
			sniffer.setYHeadRot(yaw);
			if (d.lengthSqr() > 4) sniffer.setDeltaMovement(d.normalize().scale(0.11).add(0, sniffer.getDeltaMovement().y, 0));
			// Its bulk: whoever it walks into is knocked.
			if (ticks % 20 == 0) {
				for (ServerPlayer q : near(at, 2.5)) {
					q.hurtServer(level, level.damageSources().mobAttack(sniffer), 5);
					push(q, q.position().subtract(at).multiply(1, 0, 1).normalize().scale(0.9).add(0, 0.4, 0));
				}
			}
			if (ticks % 60 == 0) sound(at, SoundEvents.SNIFFER_SNIFFING, 2, 0.7f);
		}
		// Buried mines every 8 s: three marked spots near players, bursting 3 s later.
		if (ticks % 160 == 40) {
			List<ServerPlayer> ps = near(at, 32);
			for (int i = 0; i < 3 && !ps.isEmpty(); i++) {
				ServerPlayer p = ps.get(random.nextInt(ps.size()));
				Vec3 spot = Vec3.atBottomCenterOf(groundAt(p.position().add(random.nextGaussian() * 2, 0, random.nextGaussian() * 2)));
				for (int t = 0; t < 60; t += 6) later(t, () -> telegraph(spot, 1.5, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SUSPICIOUS_GRAVEL.defaultBlockState())));
				later(60, () -> blast(sniffer, spot, 1.6f));
			}
			sound(at, SoundEvents.SNIFFER_DIGGING, 2, 0.7f);
		}
		// A thornbush wall every 12 s between it and its prey.
		if (ticks % 240 == 120 && prey != null) {
			Vec3 d = prey.position().subtract(at).multiply(1, 0, 1);
			if (d.lengthSqr() > 9) {
				Vec3 mid = at.add(d.scale(0.5));
				Vec3 side = new Vec3(-d.z, 0, d.x).normalize();
				for (int i = -3; i <= 3; i++) {
					BlockPos b = groundAt(mid.add(side.scale(i)).add(0, 2, 0));
					tempBlock(b, Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3), 200, true);
				}
				sound(mid, SoundEvents.SWEET_BERRY_BUSH_PLACE, 2, 0.7f);
			}
		}
		// An ancient seed turret every 20 s (up to two): it spits at players until broken.
		if (ticks % 400 == 300 && targets.stream().filter(t -> !t.broken()).count() < 2) {
			Vec3 spot = Vec3.atBottomCenterOf(groundAt(at.add(random.nextGaussian() * 5, 2, random.nextGaussian() * 5)));
			if (bounds != null) spot = new Vec3(Math.max(bounds.minX + 1, Math.min(bounds.maxX - 1, spot.x)), spot.y, Math.max(bounds.minZ + 1, Math.min(bounds.maxZ - 1, spot.z)));
			target("minecraft:torchflower", spot, 15 * healthScale, "Ancient Seed", true);
			sound(spot, SoundEvents.SNIFFER_EGG_HATCH, 2, 0.7f);
		}
		if (ticks % 40 == 20) {
			for (Target t : targets) {
				if (t.broken()) continue;
				Vec3 from = t.hitbox.position().add(0, 0.8, 0);
				ServerPlayer q = nearest(from, 18);
				if (q == null) continue;
				Vec3 to = q.getEyePosition();
				for (double f = 0; f <= 1; f += 0.08) {
					Vec3 x = from.add(to.subtract(from).scale(f));
					level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, x.x, x.y, x.z, 1, 0, 0, 0, 0);
				}
				q.hurtServer(level, level.damageSources().mobAttack(sniffer), 2);
			}
		}
	}
}
