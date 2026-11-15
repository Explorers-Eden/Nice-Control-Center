package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The Caravan Camel (NCC): a big camel with two husk riders. Dashes along a marked line, kicks up a sandstorm, and
 * at half health its riders jump off and fight on foot.
 */
final class CaravanCamelFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Tame:0b,Health:200f,CustomName:{text:\"The Caravan Camel\",color:\"#D8B36A\",bold:true},"
			+ "attributes:[{id:\"minecraft:max_health\",base:200},{id:\"minecraft:scale\",base:2.2},{id:\"minecraft:knockback_resistance\",base:0.9},{id:\"minecraft:step_height\",base:2},"
			+ "{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final String RIDER = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,CustomName:\"Caravan Rider\",equipment:{mainhand:{id:\"minecraft:golden_sword\",count:1}},"
			+ "drop_chances:{mainhand:0.0},attributes:[{id:\"minecraft:max_health\",base:24}]}";

	private final LivingEntity camel;
	private final List<LivingEntity> riders = new ArrayList<>();
	private boolean dismounted;
	private Vec3 dash;
	private long dashUntil = -1;
	private final Set<ServerPlayer> hitByDash = new HashSet<>();

	CaravanCamelFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		camel = (LivingEntity) spawn("minecraft:camel", x, y, z, BODY, true, false);
		for (int i = 0; i < 2 && camel != null; i++) {
			LivingEntity h = minion("minecraft:husk", x, y, z, RIDER, 4);
			if (h != null) {
				h.startRiding(camel);
				riders.add(h);
			}
		}
	}

	@Override
	String name() {
		return "The Caravan Camel";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.YELLOW;
	}

	@Override
	LivingEntity body() {
		return camel;
	}

	@Override
	boolean alive() {
		return !dead(camel);
	}

	@Override
	void ended() {
		for (LivingEntity r : riders) if (!dead(r) && r.isPassenger()) r.stopRiding();
	}

	@Override
	void tick() {
		riders.removeIf(BossFight::dead);
		Vec3 at = camel.position();
		if (!dismounted && share(camel) < 0.5f) {
			dismounted = true;
			for (LivingEntity r : riders) {
				r.stopRiding();
				r.setDeltaMovement(random.nextGaussian() * 0.4, 0.5, random.nextGaussian() * 0.4);
			}
			say(at, 40, styled("Caravan Rider: ", 0xD8B36A, true, false).copy().append(plain("Off the beast, after them!")));
		}
		if (ticks <= dashUntil && dash != null) {
			Vec3 dir = dash.subtract(at).multiply(1, 0, 1);
			if (dir.lengthSqr() > 1) camel.setDeltaMovement(dir.normalize().scale(1.1).add(0, camel.getDeltaMovement().y, 0));
			for (ServerPlayer p : near(at, 2.5)) {
				if (!hitByDash.add(p)) continue;
				p.hurtServer(level, level.damageSources().mobAttack(camel), 6);
				push(p, dir.normalize().scale(2).add(0, 0.6, 0));
			}
			return;
		}
		ServerPlayer p = nearest(at, 32);
		if (p == null) return;
		stride(camel, p, 0.22, 2, 5);
		// A dash every 9 s along a marked line.
		if (ticks % 180 == 60) {
			Vec3 d = p.position().subtract(at).multiply(1, 0, 1);
			Vec3 end = d.lengthSqr() < 0.01 ? p.position() : at.add(d.normalize().scale(Math.sqrt(d.lengthSqr()) + 6));
			BlockParticleOption sand = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SAND.defaultBlockState());
			for (int t = 0; t < 20; t += 4) later(t, () -> line(at.add(0, 0.1, 0), end.add(0, 0.1, 0), sand));
			sound(at, snd("entity.camel.dash_ready"), 2, 0.7f);
			later(20, () -> {
				dash = end;
				dashUntil = ticks + 22;
				hitByDash.clear();
				sound(camel.position(), snd("entity.camel.dash"), 2, 0.7f);
			});
		}
		// A sandstorm every 20 s: blind and slow for 4 s.
		if (ticks % 400 == 200) {
			say(at, 40, styled("The Caravan Camel ", 0xD8B36A, true, false).copy().append(plain("kicks up a sandstorm!")));
			BlockParticleOption dust = new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.SAND.defaultBlockState());
			for (ServerPlayer q : near(at, 24)) {
				effect(q, MobEffects.BLINDNESS, 80, 0);
				effect(q, MobEffects.SLOWNESS, 80, 1);
				level.sendParticles(dust, q.getX(), q.getY() + 1.5, q.getZ(), 60, 2, 1, 2, 0.02);
			}
			sound(at, snd("block.sand.break"), 2, 0.5f);
		}
	}
}
