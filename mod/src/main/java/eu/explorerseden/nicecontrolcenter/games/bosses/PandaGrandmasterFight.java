package eu.explorerseden.nicecontrolcenter.games.bosses;

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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.panda.Panda;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The Panda Grandmaster (NCC): a big aggressive panda. It rolls through players along a marked line, sneezes them
 * away, grows bamboo around them; every 25 seconds it sits down for a snack (double damage while it eats), and below
 * 30 % it gets worried: faster and angrier.
 */
final class PandaGrandmasterFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,MainGene:\"aggressive\",HiddenGene:\"aggressive\",Health:160f,"
			+ "CustomName:{text:\"The Panda Grandmaster\",color:\"#FFFFFF\",bold:true},attributes:[{id:\"minecraft:max_health\",base:160},{id:\"minecraft:scale\",base:2.5},"
			+ "{id:\"minecraft:attack_damage\",base:6},{id:\"minecraft:knockback_resistance\",base:0.8},{id:\"minecraft:step_height\",base:1.5},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";

	private final LivingEntity panda;
	private Vec3 roll;
	private long rollUntil = -1;
	private final Set<ServerPlayer> rolled = new HashSet<>();
	private long snackUntil = -1;
	private boolean worried;
	/** Bamboo that hurts: where it stands. */
	private final Set<BlockPos> bamboo = new HashSet<>();

	PandaGrandmasterFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		panda = (LivingEntity) spawn("minecraft:panda", x, y, z, BODY, true, false);
	}

	@Override
	String name() {
		return "The Panda Grandmaster";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.WHITE;
	}

	@Override
	LivingEntity body() {
		return panda;
	}

	@Override
	boolean alive() {
		return !dead(panda);
	}

	/** Double damage while it snacks. */
	@Override
	boolean allowDamage(LivingEntity piece, DamageSource source, float amount) {
		if (piece == panda && ticks < snackUntil && amount > 0) {
			hurtAs(panda, amount * 2, source.getEntity());
			return false;
		}
		return true;
	}

	@Override
	void tick() {
		Vec3 at = panda.position();
		int pace = worried ? 2 : 3;
		// Bamboo hurts whoever brushes it.
		if (!bamboo.isEmpty() && ticks % 10 == 0) {
			bamboo.removeIf(b -> !level.getBlockState(b).is(Blocks.BAMBOO));
			for (ServerPlayer p : near(at, 40)) {
				for (BlockPos b : bamboo) {
					if (p.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(b)) < 0.9 * 0.9) {
						p.hurtServer(level, level.damageSources().cactus(), 2);
						break;
					}
				}
			}
		}
		if (ticks < snackUntil) {
			if (ticks % 10 == 0) level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y + 2.5, at.z, 3, 0.6, 0.4, 0.6, 0);
			if (ticks == snackUntil - 1 && panda instanceof Panda pa) pa.sit(false);
			return;
		}
		// Rolling along the line.
		if (ticks <= rollUntil && roll != null) {
			Vec3 dir = roll.subtract(at).multiply(1, 0, 1);
			if (dir.lengthSqr() > 1) panda.setDeltaMovement(dir.normalize().scale(0.85).add(0, panda.getDeltaMovement().y, 0));
			for (ServerPlayer p : near(at, 2.4)) {
				if (!rolled.add(p)) continue;
				p.hurtServer(level, level.damageSources().mobAttack(panda), 6);
				push(p, dir.normalize().scale(1.8).add(0, 0.5, 0));
			}
			if (ticks == rollUntil && panda instanceof Panda pa) pa.roll(false);
			return;
		}
		ServerPlayer p = nearest(at, 32);
		if (p == null) return;
		if (ticks % 20 == 0 && panda instanceof net.minecraft.world.entity.Mob m) m.setTarget(p);
		// A roll every 9 s (6 when worried).
		if (ticks % (pace * 60) == 30) {
			Vec3 d = p.position().subtract(at).multiply(1, 0, 1);
			Vec3 end = d.lengthSqr() < 0.01 ? p.position() : at.add(d.normalize().scale(Math.sqrt(d.lengthSqr()) + 5));
			for (int t = 0; t < 20; t += 4) later(t, () -> {
				for (double f = 0; f <= 1; f += 0.05) {
					Vec3 q = at.add(end.subtract(at).scale(f));
					level.sendParticles(ParticleTypes.HAPPY_VILLAGER, q.x, q.y + 0.1, q.z, 1, 0, 0, 0, 0);
				}
			});
			later(20, () -> {
				if (dead(panda)) return;
				roll = end;
				rollUntil = ticks + 24;
				rolled.clear();
				if (panda instanceof Panda pa) pa.roll(true);
			});
		}
		// A sneeze every 11 s: everyone in front of it is blown away.
		if (ticks % (pace * 73) == 50 && p.distanceToSqr(panda) < 8 * 8) {
			if (panda instanceof Panda pa) pa.sneeze(true);
			later(15, () -> {
				if (panda instanceof Panda pa) pa.sneeze(false);
				Vec3 look = panda.getLookAngle().multiply(1, 0, 1).normalize();
				Vec3 c = panda.position();
				level.sendParticles(ParticleTypes.SNEEZE, c.x + look.x * 2, c.y + 1.5, c.z + look.z * 2, 40, 1, 0.5, 1, 0.1);
				sound(c, SoundEvents.PANDA_SNEEZE, 3, 0.6f);
				for (ServerPlayer q : near(c, 7)) {
					Vec3 to = q.position().subtract(c).multiply(1, 0, 1);
					if (to.lengthSqr() < 0.01 || to.normalize().dot(look) < 0.6) continue;
					push(q, to.normalize().scale(2).add(0, 0.5, 0));
					effect(q, MobEffects.SLOWNESS, 60, 1);
				}
			});
		}
		// Bamboo around a player every 12 s.
		if (ticks % 240 == 120) {
			BlockPos c = p.blockPosition();
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != 2) continue;
					for (int dy = 0; dy < 2; dy++) {
						BlockPos b = c.offset(dx, dy, dz);
						if (tempBlock(b, Blocks.BAMBOO.defaultBlockState(), 140, true)) bamboo.add(b.immutable());
					}
				}
			}
			sound(p.position(), SoundEvents.BAMBOO_PLACE, 2, 0.8f);
		}
		// A snack every 25 s: sitting, eating, double damage.
		if (ticks % 500 == 400) {
			snackUntil = ticks + 80;
			if (panda instanceof Panda pa) pa.sit(true);
			panda.setDeltaMovement(Vec3.ZERO);
			say(at, 40, styled("The Panda Grandmaster ", 0xFFFFFF, true, false).copy().append(plain("sits down for a snack… strike now!")));
		}
		if (!worried && share(panda) <= 0.3f) {
			worried = true;
			effect(panda, MobEffects.SPEED, -1, 1);
			effect(panda, MobEffects.STRENGTH, -1, 0);
			say(at, 40, styled("The Panda Grandmaster ", 0xFFFFFF, true, false).copy().append(plain("is worried now, and angry.")));
		}
	}
}
