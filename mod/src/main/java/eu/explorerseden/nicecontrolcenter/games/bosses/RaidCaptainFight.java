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
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.phys.Vec3;

/**
 * The Raid Captain (NCC): a big pillager under the ominous banner. His horn calls raid waves, his crossbow fires
 * rockets at three players at once, and while his war banner stands every illager is stronger. At 30 % the last
 * wave comes, with vexes.
 */
final class RaidCaptainFight extends BossFight {
	private static final int RGB = 0x8F9FA0;
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Health:140f,CustomName:{text:\"The Raid Captain\",color:\"#8F9FA0\",bold:true},"
			+ "equipment:{mainhand:{id:\"minecraft:crossbow\",count:1}},drop_chances:{mainhand:0f,head:0f},"
			+ "attributes:[{id:\"minecraft:max_health\",base:140},{id:\"minecraft:scale\",base:2},{id:\"minecraft:follow_range\",base:40},{id:\"minecraft:knockback_resistance\",base:0.6},"
			+ "{id:\"minecraft:step_height\",base:1.5},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final String RAIDER = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,CustomName:\"Raider\",drop_chances:{mainhand:0f},"
			+ "attributes:[{id:\"minecraft:follow_range\",base:40}]}";
	private static final String VEX = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,CustomName:\"Raid Vex\",attributes:[{id:\"minecraft:max_health\",base:10}]}";

	private final LivingEntity captain;
	private final List<Entity> raiders = new ArrayList<>();
	private Target banner;
	private boolean lastWave;

	RaidCaptainFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		captain = (LivingEntity) spawn("minecraft:pillager", x, y, z, BODY, true, false);
		if (captain instanceof Raider r) r.setCanJoinRaid(false);
		// The ominous banner on his head, like a raid captain.
		if (captain != null) captain.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
				net.minecraft.world.entity.raid.Raid.getOminousBannerInstance(level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BANNER_PATTERN)));
		double a = random.nextDouble() * Math.PI * 2;
		banner = target("minecraft:white_banner", Vec3.atBottomCenterOf(groundAt(new Vec3(x + Math.cos(a) * 5, y + 1, z + Math.sin(a) * 5))), 40, "War Banner", true);
	}

	@Override
	String name() {
		return "The Raid Captain";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.RED;
	}

	@Override
	LivingEntity body() {
		return captain;
	}

	@Override
	boolean alive() {
		return !dead(captain);
	}

	private void wave(int pillagers, int vindicators, int vexes) {
		Vec3 at = captain.position();
		ServerPlayer p = nearest(at, 40);
		for (int i = 0; i < pillagers + vindicators + vexes && raiders.size() < 8 + vexes; i++) {
			String id = i < pillagers ? "minecraft:pillager" : i < pillagers + vindicators ? "minecraft:vindicator" : "minecraft:vex";
			double a = random.nextDouble() * Math.PI * 2;
			String data = id.equals("minecraft:vex") ? VEX : RAIDER.replace("drop_chances", "equipment:{mainhand:{id:\"" + (id.equals("minecraft:pillager") ? "minecraft:crossbow" : "minecraft:iron_axe") + "\",count:1}},drop_chances");
			Entity e = spawn(id, at.x + Math.cos(a) * 4, at.y + (id.equals("minecraft:vex") ? 2 : 0), at.z + Math.sin(a) * 4, data, false, false);
			if (e == null) continue;
			raiders.add(e);
			if (e instanceof Raider r) r.setCanJoinRaid(false);
			if (e instanceof Mob m && p != null) m.setTarget(p);
			level.sendParticles(ParticleTypes.CLOUD, e.getX(), e.getY() + 0.5, e.getZ(), 8, 0.3, 0.4, 0.3, 0.02);
		}
	}

	@Override
	void tick() {
		raiders.removeIf(BossFight::dead);
		Vec3 at = captain.position();
		ServerPlayer p = nearest(at, 40);
		if (ticks % 20 == 0 && p != null && captain instanceof Mob m && m.getTarget() == null) m.setTarget(p);
		// The war banner: every illager close by is stronger while it stands.
		if (banner != null && banner.broken()) {
			banner = null;
			say(at, 40, styled("The Raid Captain", RGB, true, false).copy().append(plain("'s war banner falls!")));
		}
		if (banner != null && ticks % 40 == 0) {
			Vec3 b = banner.hitbox.position();
			List<LivingEntity> ours = new ArrayList<>();
			ours.add(captain);
			for (Entity e : raiders) if (e instanceof LivingEntity l) ours.add(l);
			for (LivingEntity l : ours) {
				if (l.distanceToSqr(b) > 24 * 24) continue;
				effect(l, MobEffects.STRENGTH, 50, 0);
				level.sendParticles(ParticleTypes.ANGRY_VILLAGER, l.getX(), l.getY() + l.getBbHeight() + 0.2, l.getZ(), 1, 0.2, 0.1, 0.2, 0);
			}
		}
		// The raid horn every 25 s: three pillagers and a vindicator (eight helpers at most).
		if (ticks % 500 == 60) {
			sound(at, SoundEvents.RAID_HORN.value(), 4, 1);
			wave(3, 1, 0);
			say(at, 40, styled("The Raid Captain ", RGB, true, false).copy().append(plain("sounds the horn: raiders!")));
		}
		// Rockets every 9 s at up to three players: a ring warns, then the blast.
		if (ticks % 180 == 120) {
			List<ServerPlayer> ps = new ArrayList<>(near(at, 32));
			java.util.Collections.shuffle(ps, new java.util.Random(random.nextLong()));
			for (int i = 0; i < Math.min(3, ps.size()); i++) rocket(ps.get(i).position());
			if (!ps.isEmpty()) sound(at, SoundEvents.CROSSBOW_SHOOT, 2, 0.6f);
		}
		if (!lastWave && share(captain) <= 0.3f) {
			lastWave = true;
			sound(at, SoundEvents.RAID_HORN.value(), 4, 0.8f);
			wave(0, 2, 2);
			say(at, 40, styled("The Raid Captain ", RGB, true, false).copy().append(plain("calls his last raiders!")));
		}
	}

	/** A firework arcing to a marked spot: 6 damage there. */
	private void rocket(Vec3 target) {
		Vec3 spot = Vec3.atBottomCenterOf(groundAt(target));
		Vec3 from = captain.getEyePosition();
		for (int t = 0; t < 25; t += 5) later(t, () -> telegraph(spot, 2.2, ParticleTypes.FIREWORK));
		for (int t = 0; t <= 20; t += 2) {
			double f = t / 20.0;
			later(5 + t, () -> {
				Vec3 q = from.add(spot.subtract(from).scale(f)).add(0, Math.sin(f * Math.PI) * 4, 0);
				level.sendParticles(ParticleTypes.FIREWORK, q.x, q.y, q.z, 2, 0.05, 0.05, 0.05, 0);
			});
		}
		later(25, () -> {
			level.sendParticles(ParticleTypes.EXPLOSION, spot.x, spot.y + 0.5, spot.z, 1, 0, 0, 0, 0);
			level.sendParticles(ParticleTypes.FIREWORK, spot.x, spot.y + 0.8, spot.z, 40, 0.4, 0.4, 0.4, 0.25);
			sound(spot, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 3, 1);
			for (ServerPlayer q : near(spot, 2.2)) q.hurtServer(level, level.damageSources().mobAttack(captain), 6);
		});
	}
}
