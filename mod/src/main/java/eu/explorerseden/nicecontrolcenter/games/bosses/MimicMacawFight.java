package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.List;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The Mimic Macaw (NCC): a big parrot flying over the fight. It answers with the kind of attack players used on it
 * last (arrows, a peck dive, fire, a blast), imitates a mob and calls one of it, and drops feather volleys.
 */
final class MimicMacawFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,NoGravity:1b,Variant:0,Health:140f,CustomName:{text:\"The Mimic Macaw\",color:\"#E8473A\",bold:true},"
			+ "attributes:[{id:\"minecraft:max_health\",base:140},{id:\"minecraft:scale\",base:2.5},{id:\"minecraft:knockback_resistance\",base:0.8},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	/** Mobs it imitates and calls (none that break blocks). */
	private static final List<String> MIMICS = List.of("zombie", "skeleton", "spider", "husk", "stray", "witch");

	private final LivingEntity macaw;
	/** What hurt it last: arrow, melee, fire, blast. */
	private String last = "melee";
	private Vec3 dive;
	private long diveUntil = -1;

	MimicMacawFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		macaw = (LivingEntity) spawn("minecraft:parrot", x, y + 3, z, BODY, true, false);
	}

	@Override
	String name() {
		return "The Mimic Macaw";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.RED;
	}

	@Override
	LivingEntity body() {
		return macaw;
	}

	@Override
	boolean alive() {
		return !dead(macaw);
	}

	@Override
	void damaged(LivingEntity piece, DamageSource source, float amount) {
		if (piece != macaw || !(source.getEntity() instanceof ServerPlayer)) return;
		last = source.is(DamageTypeTags.IS_EXPLOSION) ? "blast" : source.is(DamageTypeTags.IS_FIRE) ? "fire" : source.is(DamageTypeTags.IS_PROJECTILE) ? "arrow" : "melee";
	}

	@Override
	void tick() {
		Vec3 at = macaw.position();
		// Diving at someone.
		if (ticks <= diveUntil && dive != null) {
			Vec3 d = dive.subtract(at);
			if (d.lengthSqr() > 0.5) macaw.setDeltaMovement(d.normalize().scale(1.0));
			for (ServerPlayer p : near(at, 2)) {
				p.hurtServer(level, level.damageSources().mobAttack(macaw), 6);
				push(p, d.normalize().scale(1.2).add(0, 0.4, 0));
				diveUntil = ticks;
			}
			return;
		}
		ServerPlayer p = nearest(at, 36);
		if (p == null) return;
		// Hovers 5 blocks above the nearest player, circling a little.
		Vec3 want = p.position().add(Math.cos(ticks / 20.0) * 3, 5, Math.sin(ticks / 20.0) * 3);
		Vec3 d = want.subtract(at);
		macaw.setDeltaMovement(d.lengthSqr() > 0.25 ? d.normalize().scale(0.3) : Vec3.ZERO);
		face(macaw, p.position());
		// Its answer every 6 s: the kind of attack it took last.
		if (ticks % 120 == 60) {
			switch (last) {
				case "arrow" -> {
					sound(at, snd("entity.parrot.imitate.skeleton"), 2, 1);
					for (ServerPlayer q : near(at, 30)) for (int i = 0; i < 2; i++) later(i * 6, () -> arrow(q));
				}
				case "fire" -> {
					sound(at, snd("entity.parrot.imitate.blaze"), 2, 1);
					for (ServerPlayer q : near(at, 30)) {
						Vec3 spot = q.position();
						for (int t = 0; t < 20; t += 5) later(t, () -> telegraph(spot, 2, ParticleTypes.FLAME));
						later(20, () -> {
							for (ServerPlayer hit : near(spot, 2.2)) {
								hit.hurtServer(level, level.damageSources().mobAttack(macaw), 4);
								hit.igniteForSeconds(4);
							}
						});
					}
				}
				case "blast" -> {
					sound(at, snd("entity.parrot.imitate.creeper"), 2, 1);
					Vec3 spot = p.position();
					for (int t = 0; t < 30; t += 5) later(t, () -> telegraph(spot, 3, ParticleTypes.SMOKE));
					later(30, () -> blast(macaw, spot, 2.5f));
				}
				default -> {
					dive = p.position().add(0, 1, 0);
					diveUntil = ticks + 30;
					sound(at, snd("entity.parrot.hurt"), 2, 0.6f);
				}
			}
		}
		// Imitates a mob every 15 s, and one of it comes.
		if (ticks % 300 == 200) {
			String m = MIMICS.get(random.nextInt(MIMICS.size()));
			sound(at, snd("entity.parrot.imitate." + m), 2, 1);
			minion("minecraft:" + m, p.getX() + random.nextGaussian() * 3, p.getY(), p.getZ() + random.nextGaussian() * 3,
					"{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,CustomName:\"Mimicked " + Character.toUpperCase(m.charAt(0)) + m.substring(1) + "\"}", 3);
			say(at, 40, styled("The Mimic Macaw ", 0xE8473A, true, false).copy().append(plain("squawks like a " + m + "…")));
		}
		// Feather volley every 9 s: three marked spots, then 3 damage and slowness there.
		if (ticks % 180 == 20) {
			BlockParticleOption feather = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SNOW_BLOCK.defaultBlockState());
			int n = 0;
			for (ServerPlayer q : near(at, 28)) {
				if (n++ >= 3) break;
				Vec3 spot = Vec3.atBottomCenterOf(groundAt(q.position()));
				for (int t = 0; t < 25; t += 5) later(t, () -> telegraph(spot, 1.6, feather));
				later(25, () -> {
					level.sendParticles(feather, spot.x, spot.y + 1, spot.z, 30, 0.8, 0.8, 0.8, 0.05);
					for (ServerPlayer hit : near(spot, 1.8)) {
						hit.hurtServer(level, level.damageSources().mobAttack(macaw), 3);
						effect(hit, net.minecraft.world.effect.MobEffects.SLOWNESS, 40, 1);
					}
				});
			}
		}
	}

	/** An arrow shot at the player (can't be picked up). */
	private void arrow(ServerPlayer q) {
		if (dead(macaw)) return;
		Vec3 from = macaw.position().add(0, 1, 0);
		Entity a = spawn("minecraft:arrow", from.x, from.y, from.z, "{pickup:0b}", false, true);
		if (a == null) return;
		Vec3 d = q.getEyePosition().subtract(from).normalize().scale(1.6);
		a.setDeltaMovement(d);
	}
}
