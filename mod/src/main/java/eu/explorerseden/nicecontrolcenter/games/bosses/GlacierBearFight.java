package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The Glacier Bear (NCC): a huge polar bear with two cubs. Hurting a cub enrages her (faster, stronger); she slides
 * along a marked line leaving snow, roars to stun those close by, and the cubs throw snowballs.
 */
final class GlacierBearFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Health:180f,CustomName:{text:\"The Glacier Bear\",color:\"#BFE6FF\",bold:true},"
			+ "attributes:[{id:\"minecraft:max_health\",base:180},{id:\"minecraft:scale\",base:2.5},{id:\"minecraft:attack_damage\",base:6},{id:\"minecraft:knockback_resistance\",base:0.8},"
			+ "{id:\"minecraft:follow_range\",base:40},{id:\"minecraft:step_height\",base:1.5},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final String CUB = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Age:-2000000,CustomName:\"Glacier Cub\",attributes:[{id:\"minecraft:max_health\",base:20}]}";

	private final LivingEntity bear;
	private final List<Entity> cubs = new ArrayList<>();
	private long enragedUntil = -1;
	private Vec3 slide;
	private long slideUntil = -1;
	private final Set<ServerPlayer> slid = new HashSet<>();

	GlacierBearFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		bear = (LivingEntity) spawn("minecraft:polar_bear", x, y, z, BODY, true, false);
		for (int i = 0; i < 2 && bear != null; i++) {
			Entity c = spawn("minecraft:polar_bear", x + (i == 0 ? 2 : -2), y, z, CUB, false, false);
			if (c != null) cubs.add(c);
		}
	}

	@Override
	String name() {
		return "The Glacier Bear";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.BLUE;
	}

	@Override
	LivingEntity body() {
		return bear;
	}

	@Override
	boolean alive() {
		return !dead(bear);
	}

	/** A cub hurt: she's enraged for 10 s. */
	@Override
	void damaged(LivingEntity piece, DamageSource source, float amount) {
		if (!cubs.contains(piece) || dead(bear)) return;
		if (ticks >= enragedUntil) {
			sound(bear.position(), SoundEvents.POLAR_BEAR_WARNING, 3, 0.6f);
			say(bear.position(), 40, styled("The Glacier Bear ", 0xBFE6FF, true, false).copy().append(plain("is enraged: you hurt her cub!")));
		}
		enragedUntil = ticks + 200;
		effect(bear, MobEffects.SPEED, 200, 1);
		effect(bear, MobEffects.STRENGTH, 200, 1);
	}

	private void hunt(Entity e) {
		ServerPlayer p = nearest(e.position(), 40);
		if (p == null) return;
		if (e instanceof NeutralMob n) n.startPersistentAngerTimer();
		if (e instanceof Mob m) m.setTarget(p);
	}

	@Override
	void tick() {
		cubs.removeIf(BossFight::dead);
		Vec3 at = bear.position();
		if (ticks % 20 == 0) {
			hunt(bear);
			for (Entity c : cubs) hunt(c);
		}
		if (ticks < enragedUntil && ticks % 10 == 0) level.sendParticles(ParticleTypes.ANGRY_VILLAGER, at.x, at.y + 3, at.z, 1, 0.5, 0.3, 0.5, 0);
		// Sliding along the line, leaving snow.
		if (ticks <= slideUntil && slide != null) {
			Vec3 dir = slide.subtract(at).multiply(1, 0, 1);
			if (dir.lengthSqr() > 1) bear.setDeltaMovement(dir.normalize().scale(0.9).add(0, bear.getDeltaMovement().y, 0));
			BlockPos b = bear.blockPosition();
			if (level.getBlockState(b.below()).isFaceSturdy(level, b.below(), net.minecraft.core.Direction.UP)) tempBlock(b, Blocks.SNOW.defaultBlockState(), 160);
			for (ServerPlayer p : near(at, 2.4)) {
				if (!slid.add(p)) continue;
				p.hurtServer(level, level.damageSources().mobAttack(bear), 6);
				push(p, dir.normalize().scale(1.6).add(0, 0.5, 0));
			}
			return;
		}
		ServerPlayer p = nearest(at, 32);
		if (p == null) return;
		// The ice slide every 10 s along a marked line.
		if (ticks % 200 == 60) {
			Vec3 d = p.position().subtract(at).multiply(1, 0, 1);
			Vec3 end = d.lengthSqr() < 0.01 ? p.position() : at.add(d.normalize().scale(Math.sqrt(d.lengthSqr()) + 5));
			for (int t = 0; t < 20; t += 4) later(t, () -> {
				for (double f = 0; f <= 1; f += 0.05) {
					Vec3 q = at.add(end.subtract(at).scale(f));
					level.sendParticles(ParticleTypes.SNOWFLAKE, q.x, q.y + 0.1, q.z, 1, 0, 0, 0, 0);
				}
			});
			later(20, () -> {
				slide = end;
				slideUntil = ticks + 24;
				slid.clear();
			});
		}
		// The roar every 12 s: stuns those close by.
		if (ticks % 240 == 150 && !near(at, 6).isEmpty()) {
			sound(at, SoundEvents.POLAR_BEAR_WARNING, 3, 0.5f);
			level.sendParticles(ParticleTypes.CLOUD, at.x, at.y + 2, at.z, 30, 2, 1, 2, 0.05);
			for (ServerPlayer q : near(at, 6)) {
				effect(q, MobEffects.SLOWNESS, 40, 3);
				effect(q, MobEffects.WEAKNESS, 40, 0);
			}
		}
		// The cubs throw snowballs every 3 s.
		if (ticks % 60 == 30) {
			for (Entity c : cubs) {
				ServerPlayer t = nearest(c.position(), 20);
				if (t == null) continue;
				Vec3 from = c.position().add(0, 1, 0);
				Vec3 d = t.getEyePosition().subtract(from).normalize().scale(1.4);
				spawn("minecraft:snowball", from.x, from.y, from.z, "{Motion:[" + d.x + "d," + (d.y + 0.1) + "d," + d.z + "d]}", false, true);
			}
		}
	}
}
