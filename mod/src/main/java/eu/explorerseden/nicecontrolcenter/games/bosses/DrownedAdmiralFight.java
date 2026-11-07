package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.List;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The Drowned Admiral (NCC): a drowned captain with a trident. Volleys of three tridents, a tidal push that throws
 * everyone close back, a drowned crew at two thirds and one third of its health; standing in water it heals and
 * gets faster.
 */
final class DrownedAdmiralFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Health:200f,CustomName:{text:\"The Drowned Admiral\",color:\"#3FA7D6\",bold:true},"
			+ "equipment:{mainhand:{id:\"minecraft:trident\",count:1,components:{\"minecraft:enchantments\":{\"minecraft:loyalty\":3,\"minecraft:impaling\":2}}},"
			+ "head:{id:\"minecraft:white_banner\",count:1,components:{\"minecraft:banner_patterns\":[{pattern:\"minecraft:stripe_bottom\",color:\"blue\"},{pattern:\"minecraft:border\",color:\"black\"}]}}},"
			+ "drop_chances:{mainhand:0.0,head:0.0},attributes:[{id:\"minecraft:max_health\",base:200},{id:\"minecraft:scale\",base:1.8},{id:\"minecraft:knockback_resistance\",base:0.7},"
			+ "{id:\"minecraft:follow_range\",base:40},{id:\"minecraft:step_height\",base:1.5},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final String CREW = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,CustomName:\"Drowned Crew\",equipment:{mainhand:{id:\"minecraft:iron_sword\",count:1}},drop_chances:{mainhand:0.0}}";

	private final LivingEntity admiral;
	private int crews;

	DrownedAdmiralFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		admiral = (LivingEntity) spawn("minecraft:drowned", x, y, z, BODY, true, false);
	}

	@Override
	String name() {
		return "The Drowned Admiral";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.BLUE;
	}

	@Override
	LivingEntity body() {
		return admiral;
	}

	@Override
	boolean alive() {
		return !dead(admiral);
	}

	@Override
	void tick() {
		Vec3 at = admiral.position();
		if (ticks % 20 == 0 && admiral.isInWater()) {
			effect(admiral, MobEffects.REGENERATION, 40, 1);
			effect(admiral, MobEffects.SPEED, 40, 1);
		}
		// Three tridents every 7 s at players up to 28 blocks away.
		if (ticks % 140 == 0) {
			List<ServerPlayer> ps = near(at, 28);
			for (int i = 0; i < 3 && !ps.isEmpty(); i++) {
				ServerPlayer p = ps.get(random.nextInt(ps.size()));
				later(i * 8, () -> trident(p));
			}
		}
		// The tide every 12 s: a splash, then everyone close is thrown back.
		if (ticks % 240 == 120 && !near(at, 8).isEmpty()) {
			for (int t = 0; t < 20; t += 5) later(t, () -> {
				telegraph(admiral.position(), 4, ParticleTypes.SPLASH);
				telegraph(admiral.position(), 6, ParticleTypes.SPLASH);
			});
			later(20, () -> {
				Vec3 c = admiral.position();
				level.sendParticles(ParticleTypes.SPLASH, c.x, c.y + 0.5, c.z, 120, 4, 0.5, 4, 0.3);
				sound(c, SoundEvents.GENERIC_SPLASH, 3, 0.6f);
				shockwave(admiral, c, 7, 5, 1.8, 0.6);
			});
		}
		// The crew at two thirds and one third.
		if (crews == 0 && share(admiral) <= 0.66f || crews == 1 && share(admiral) <= 0.33f) {
			crews++;
			say(at, 40, styled("The Drowned Admiral: ", 0x3FA7D6, true, false).copy().append(plain(crews == 1 ? "All hands on deck!" : "Drag them under!")));
			for (int i = 0; i < 3; i++) {
				double a = Math.PI * 2 * i / 3;
				spawn("minecraft:drowned", at.x + Math.cos(a) * 3, at.y, at.z + Math.sin(a) * 3, CREW, false, false);
			}
		}
	}

	/** A thrown trident at the player (no pickup). */
	private void trident(ServerPlayer p) {
		if (dead(admiral) || p.isRemoved()) return;
		Vec3 from = admiral.getEyePosition();
		Vec3 dir = p.getEyePosition().subtract(from).normalize().scale(2.2);
		spawn("minecraft:trident", from.x, from.y, from.z, "{pickup:0b,Motion:[" + dir.x + "d," + (dir.y + 0.1) + "d," + dir.z + "d],item:{id:\"minecraft:trident\",count:1}}", false, true);
		sound(from, SoundEvents.DROWNED_SHOOT, 1.5f, 0.8f);
	}
}
