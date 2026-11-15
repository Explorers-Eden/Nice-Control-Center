package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.List;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The Cluckening (NCC): a giant chicken. Egg barrages (some hatch angry chicks, some pop), a flutter up and slam down
 * on a player, and a feather storm that weakens everyone close and lets them float.
 */
final class CluckeningFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,EggLayTime:999999999,Health:150f,CustomName:{text:\"The Cluckening\",color:\"#F2E6C9\",bold:true},"
			+ "attributes:[{id:\"minecraft:max_health\",base:150},{id:\"minecraft:scale\",base:4},{id:\"minecraft:knockback_resistance\",base:0.9},{id:\"minecraft:step_height\",base:2},"
			+ "{id:\"minecraft:safe_fall_distance\",base:64},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final String CHICK = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,EggLayTime:999999999,Age:-24000,CustomName:\"Angry Chick\",attributes:[{id:\"minecraft:max_health\",base:6}]}";
	private static final String EGG = "{teleport_duration:3,item:{id:\"minecraft:egg\",count:1},transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[1.2f,1.2f,1.2f]}}";

	private final LivingEntity hen;
	private Vec3 slam;
	private long slamFrom = -1;

	CluckeningFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		hen = (LivingEntity) spawn("minecraft:chicken", x, y, z, BODY, true, false);
	}

	@Override
	String name() {
		return "The Cluckening";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.WHITE;
	}

	@Override
	LivingEntity body() {
		return hen;
	}

	@Override
	boolean alive() {
		return !dead(hen);
	}

	@Override
	void tick() {
		Vec3 at = hen.position();
		if (slam != null) {
			if (ticks >= slamFrom && (hen.onGround() || ticks > slamFrom + 60)) {
				slam = null;
				sound(hen.position(), snd("entity.chicken.hurt"), 2, 0.4f);
				shockwave(hen, hen.position(), 5, 6, 1.2, 0.6);
			}
			return;
		}
		ServerPlayer p = nearest(at, 32);
		if (p == null) return;
		stride(hen, p, 0.24, 2, 4);
		// An egg barrage every 8 s: eggs land on marked spots; 7 in 10 hatch an angry chick, the rest pop.
		if (ticks % 160 == 40) {
			sound(at, snd("entity.chicken.egg"), 2, 0.5f);
			List<ServerPlayer> ps = near(at, 26);
			for (int i = 0; i < 6 && !ps.isEmpty(); i++) {
				ServerPlayer q = ps.get(random.nextInt(ps.size()));
				Vec3 spot = Vec3.atBottomCenterOf(groundAt(q.position().add(random.nextGaussian() * 2, 0, random.nextGaussian() * 2)));
				later(i * 3, () -> egg(spot));
			}
		}
		// A flutter and slam every 12 s.
		if (ticks % 240 == 140) {
			Vec3 spot = Vec3.atBottomCenterOf(groundAt(p.position()));
			slam = spot;
			slamFrom = Long.MAX_VALUE;
			BlockParticleOption feather = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SNOW_BLOCK.defaultBlockState());
			for (int t = 0; t < 25; t += 5) later(t, () -> telegraph(spot, 3, feather));
			sound(at, snd("entity.chicken.ambient"), 2, 0.4f);
			later(25, () -> {
				if (dead(hen)) {
					slam = null;
					return;
				}
				Vec3 d = spot.subtract(hen.position());
				hen.setDeltaMovement(d.x / 22, 1.3, d.z / 22);
				slamFrom = ticks + 8;
			});
		}
		// A feather storm every 20 s: everyone within 7 blocks weakened, floating, pushed away.
		if (ticks % 400 == 300) {
			say(at, 40, styled("The Cluckening ", 0xF2E6C9, true, false).copy().append(plain("whips up a feather storm!")));
			BlockParticleOption feather = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SNOW_BLOCK.defaultBlockState());
			level.sendParticles(feather, at.x, at.y + 2, at.z, 120, 4, 2, 4, 0.1);
			for (ServerPlayer q : near(at, 7)) {
				effect(q, MobEffects.WEAKNESS, 100, 0);
				effect(q, MobEffects.SLOW_FALLING, 100, 0);
				push(q, q.position().subtract(at).multiply(1, 0, 1).normalize().scale(1.2).add(0, 0.9, 0));
			}
		}
	}

	/** An egg arcs onto the spot over a second, then hatches a chick or pops. */
	private void egg(Vec3 spot) {
		if (dead(hen)) return;
		Vec3 from = hen.position().add(0, 3, 0);
		for (int t = 0; t < 20; t += 5) later(t, () -> telegraph(spot, 1, ParticleTypes.CLOUD));
		Entity e = spawn("minecraft:item_display", from.x, from.y, from.z, EGG, false, true);
		for (int i = 1; i <= 7; i++) {
			int step = i;
			later(i * 3, () -> {
				if (e == null || e.isRemoved()) return;
				double f = step / 7.0;
				Vec3 q = from.add(spot.subtract(from).scale(f)).add(0, Math.sin(f * Math.PI) * 4, 0);
				e.teleportTo(q.x, q.y, q.z);
				if (step < 7) return;
				e.discard();
				sound(spot, snd("entity.egg.throw"), 1.5f, 0.7f);
				if (random.nextFloat() < 0.7f) {
					minion("minecraft:chicken", spot.x, spot.y, spot.z, CHICK, 2);
				} else {
					level.sendParticles(ParticleTypes.POOF, spot.x, spot.y + 0.3, spot.z, 12, 0.4, 0.2, 0.4, 0.02);
					for (ServerPlayer hit : near(spot, 1.8)) hit.hurtServer(level, level.damageSources().mobAttack(hen), 4);
				}
			});
		}
	}
}
