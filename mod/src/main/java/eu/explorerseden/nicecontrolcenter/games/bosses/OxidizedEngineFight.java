package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.List;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.phys.Vec3;

/**
 * The Oxidized Engine (NCC): a towering copper golem that rusts as it fights: every 30 seconds it oxidizes a stage,
 * slower each time but hitting harder. Three lightning rods stand around it; every 40 seconds lightning strikes one
 * and polishes it fresh again (with some healing), so break the rods. It slams the ground and lets off sparks.
 */
final class OxidizedEngineFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Health:180f,CustomName:{text:\"The Oxidized Engine\",color:\"#4FA88A\",bold:true},"
			+ "attributes:[{id:\"minecraft:max_health\",base:180},{id:\"minecraft:scale\",base:3},{id:\"minecraft:knockback_resistance\",base:1},{id:\"minecraft:step_height\",base:2},"
			+ "{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final WeatheringCopper.WeatherState[] STAGES = WeatheringCopper.WeatherState.values();

	private final LivingEntity engine;
	private final Vec3 home;
	private int stage;

	OxidizedEngineFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		home = new Vec3(x, y, z);
		engine = (LivingEntity) spawn("minecraft:copper_golem", x, y, z, BODY, true, false);
		for (int i = 0; i < 3; i++) {
			double a = Math.PI * 2 * i / 3 + Math.PI / 6;
			Vec3 spot = Vec3.atBottomCenterOf(groundAt(home.add(Math.cos(a) * 8, 2, Math.sin(a) * 8)));
			if (bounds != null) spot = new Vec3(Math.max(bounds.minX + 1, Math.min(bounds.maxX - 1, spot.x)), spot.y, Math.max(bounds.minZ + 1, Math.min(bounds.maxZ - 1, spot.z)));
			target("minecraft:lightning_rod", spot, 20 * healthScale, "Lightning Rod", true);
		}
		setStage(0);
	}

	@Override
	String name() {
		return "The Oxidized Engine";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.GREEN;
	}

	@Override
	LivingEntity body() {
		return engine;
	}

	@Override
	boolean alive() {
		return !dead(engine);
	}

	private void setStage(int s) {
		stage = Math.max(0, Math.min(STAGES.length - 1, s));
		if (engine instanceof CopperGolem g) g.setWeatherState(STAGES[stage]);
	}

	/** Slower with every stage of rust. */
	private double speed() {
		return 0.13 - stage * 0.02;
	}

	/** Harder hits with every stage. */
	private float hitDamage() {
		return 5 + stage * 1.5f;
	}

	@Override
	void tick() {
		Vec3 at = engine.position();
		ServerPlayer p = nearest(at, 40);
		if (p != null) {
			Vec3 d = p.position().subtract(at).multiply(1, 0, 1);
			float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90);
			engine.setYRot(yaw);
			engine.setYHeadRot(yaw);
			if (d.lengthSqr() > 6) engine.setDeltaMovement(d.normalize().scale(speed()).add(0, engine.getDeltaMovement().y, 0));
			if (ticks % 30 == 0 && d.lengthSqr() < 3.5 * 3.5) {
				p.hurtServer(level, level.damageSources().mobAttack(engine), hitDamage());
				push(p, d.normalize().scale(0.9).add(0, 0.4, 0));
				sound(at, SoundEvents.IRON_GOLEM_ATTACK, 1.5f, 1.3f);
			}
		}
		// Rusting every 30 s.
		// (Never fully oxidized: the game turns an oxidized copper golem into a statue block.)
		if (ticks % 600 == 0 && stage < STAGES.length - 2) {
			setStage(stage + 1);
			sound(at, net.minecraft.sounds.SoundEvent.createVariableRangeEvent(net.minecraft.resources.Identifier.withDefaultNamespace("item.axe.wax_off")), 2, 0.5f);
			level.sendParticles(ParticleTypes.WAX_OFF, at.x, at.y + 2, at.z, 30, 1, 1.5, 1, 0.1);
		}
		// Lightning on a standing rod every 40 s: polished fresh, and some healing.
		if (ticks % 800 == 400) {
			List<Target> rods = targets.stream().filter(t -> !t.broken()).toList();
			if (!rods.isEmpty()) {
				Target rod = rods.get(random.nextInt(rods.size()));
				Vec3 r = rod.hitbox.position();
				Entity bolt = spawn("minecraft:lightning_bolt", r.x, r.y + 1, r.z, "{}", false, true);
				// Only the flash: no fire, no damage (its own rods would break).
				if (bolt instanceof net.minecraft.world.entity.LightningBolt lb) lb.setVisualOnly(true);
				setStage(0);
				effect(engine, MobEffects.REGENERATION, 100, 1);
				level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y + 2, at.z, 60, 1, 1.5, 1, 0.3);
				say(at, 40, styled("The Oxidized Engine ", 0x4FA88A, true, false).copy().append(plain("is polished by the lightning. Break the rods!")));
			}
		}
		// A slam every 9 s when someone is close.
		if (ticks % 180 == 90 && !near(at, 7).isEmpty()) {
			for (int t = 0; t < 20; t += 5) later(t, () -> telegraph(engine.position(), 6, ParticleTypes.WAX_ON));
			later(20, () -> {
				sound(engine.position(), SoundEvents.ANVIL_LAND, 2, 0.6f);
				shockwave(engine, engine.position(), 6, 4 + stage, 0.9, 0.6);
			});
		}
		// Sparks every 12 s: a ring of them, then a jolt.
		if (ticks % 240 == 150) {
			for (int t = 0; t < 25; t += 5) later(t, () -> telegraph(engine.position(), 4.5, ParticleTypes.ELECTRIC_SPARK));
			later(25, () -> {
				sound(engine.position(), SoundEvents.LIGHTNING_BOLT_IMPACT, 1.5f, 1.4f);
				for (ServerPlayer q : near(engine.position(), 4.8)) q.hurtServer(level, level.damageSources().lightningBolt(), 5);
			});
		}
	}
}
