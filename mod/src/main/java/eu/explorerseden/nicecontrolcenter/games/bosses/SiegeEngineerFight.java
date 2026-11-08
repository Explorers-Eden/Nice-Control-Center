package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The Siege Engineer (NCC): a pillager with siege gear. His ballista (a dispenser to break) shoots arrow volleys,
 * mortar shells drop on marked spots, cobweb caltrops slow players, and when someone gets close he fences himself in
 * for a few seconds. At half health he builds the ballista once more.
 */
final class SiegeEngineerFight extends BossFight {
	private static final int RGB = 0x7A5C3A;
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Health:130f,CustomName:{text:\"The Siege Engineer\",color:\"#7A5C3A\",bold:true},"
			+ "equipment:{mainhand:{id:\"minecraft:crossbow\",count:1},head:{id:\"minecraft:tnt\",count:1}},drop_chances:{mainhand:0f,head:0f},"
			+ "attributes:[{id:\"minecraft:max_health\",base:130},{id:\"minecraft:scale\",base:2},{id:\"minecraft:follow_range\",base:40},{id:\"minecraft:knockback_resistance\",base:0.6},"
			+ "{id:\"minecraft:step_height\",base:1.5},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";

	private final LivingEntity engineer;
	private Target ballista;
	private boolean rebuilt;
	private long fencedUntil = -1;

	SiegeEngineerFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		engineer = (LivingEntity) spawn("minecraft:pillager", x, y, z, BODY, true, false);
		if (engineer instanceof Raider r) r.setCanJoinRaid(false);
		build(new Vec3(x, y, z));
	}

	private void build(Vec3 near) {
		double a = random.nextDouble() * Math.PI * 2;
		ballista = target("minecraft:dispenser", Vec3.atBottomCenterOf(groundAt(near.add(Math.cos(a) * 5, 1, Math.sin(a) * 5))), 30, "Ballista", true);
		sound(near, SoundEvents.ANVIL_USE, 1.5f, 0.6f);
	}

	@Override
	String name() {
		return "The Siege Engineer";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.RED;
	}

	@Override
	LivingEntity body() {
		return engineer;
	}

	@Override
	boolean alive() {
		return !dead(engineer);
	}

	@Override
	void tick() {
		Vec3 at = engineer.position();
		ServerPlayer p = nearest(at, 40);
		if (ticks % 20 == 0 && p != null && engineer instanceof Mob m && m.getTarget() == null) m.setTarget(p);
		if (ballista != null && ballista.broken()) {
			ballista = null;
			say(at, 40, styled("The Siege Engineer", RGB, true, false).copy().append(plain("'s ballista is wrecked!")));
		}
		// The ballista every 3 s: five arrows at the nearest player.
		if (ballista != null && ticks % 60 == 30) {
			Vec3 from = ballista.hitbox.position().add(0, 1.1, 0);
			ServerPlayer t = nearest(from, 30);
			if (t != null) {
				for (int i = 0; i < 5; i++) {
					Vec3 d = t.getEyePosition().subtract(from).normalize().add(random.nextGaussian() * 0.06, random.nextGaussian() * 0.04 + 0.04, random.nextGaussian() * 0.06).scale(2.2);
					spawn("minecraft:arrow", from.x, from.y, from.z, String.format(java.util.Locale.ROOT, "{pickup:0b,damage:1.2d,Motion:[%.3fd,%.3fd,%.3fd]}", d.x, d.y, d.z), false, true);
				}
				sound(from, SoundEvents.DISPENSER_LAUNCH, 2, 0.7f);
			}
		}
		// Mortar shells every 9 s on two players: a warned blast that breaks nothing.
		if (ticks % 180 == 90) {
			List<ServerPlayer> ps = near(at, 32);
			for (int i = 0; i < Math.min(2, ps.size()); i++) {
				Vec3 spot = Vec3.atBottomCenterOf(groundAt(ps.get(random.nextInt(ps.size())).position()));
				dropBlock(spot, "minecraft:tnt", 30, engineer, 0, 0, null);
				later(44, () -> blast(engineer, spot.add(0, 0.5, 0), 1.6f));
			}
			if (!ps.isEmpty()) sound(at, SoundEvents.TNT_PRIMED, 2, 0.8f);
		}
		// Caltrops every 12 s: cobwebs around a player for 5 s.
		if (ticks % 240 == 150 && p != null) {
			List<ServerPlayer> ps = near(at, 28);
			if (!ps.isEmpty()) {
				BlockPos c = groundAt(ps.get(random.nextInt(ps.size())).position());
				for (int i = 0; i < 6; i++) tempBlock(c.offset(random.nextInt(5) - 2, 0, random.nextInt(5) - 2), Blocks.COBWEB.defaultBlockState(), 100);
				sound(Vec3.atCenterOf(c), SoundEvents.TRIPWIRE_ATTACH, 2, 0.8f);
			}
		}
		// The barricade: someone came close, a fence ring for 6 s (every 15 s at most).
		if (ticks >= fencedUntil + 300 && !near(at, 4).isEmpty()) {
			fencedUntil = ticks + 120;
			BlockPos c = engineer.blockPosition();
			for (int dx = -3; dx <= 3; dx++) {
				for (int dz = -3; dz <= 3; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != 3) continue;
					tempBlock(groundAt(Vec3.atBottomCenterOf(c.offset(dx, 0, dz))), Blocks.DARK_OAK_FENCE.defaultBlockState(), 120);
				}
			}
			level.sendParticles(ParticleTypes.CLOUD, at.x, at.y + 0.5, at.z, 20, 2, 0.3, 2, 0.02);
			sound(at, SoundEvents.WOOD_PLACE, 2, 0.6f);
		}
		if (!rebuilt && share(engineer) <= 0.5f) {
			rebuilt = true;
			if (ballista == null) {
				build(at);
				say(at, 40, styled("The Siege Engineer ", RGB, true, false).copy().append(plain("builds another ballista!")));
			}
		}
	}
}
