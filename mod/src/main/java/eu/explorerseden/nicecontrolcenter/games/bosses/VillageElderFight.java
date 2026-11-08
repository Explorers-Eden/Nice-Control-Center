package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.ArrayList;
import java.util.List;

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
import net.minecraft.world.entity.animal.golem.AbstractGolem;
import net.minecraft.world.phys.Vec3;

import eu.explorerseden.nicecontrolcenter.games.Aggro;

/**
 * The Village Elder (NCC): an old cleric who rings the alarm bell and the whole village fights for him. While a golem
 * stands close by he takes half damage, he heals his golems with a prayer (hitting him breaks it), throws weakness and
 * slowness, and at half health two Village Guards join.
 */
final class VillageElderFight extends BossFight {
	private static final int RGB = 0x9C5BB5;
	private static final String GUARD = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,PlayerCreated:0b,CustomName:\"Village Guard\","
			+ "attributes:[{id:\"minecraft:max_health\",base:60},{id:\"minecraft:attack_damage\",base:6}]}";

	private final LivingEntity elder;
	private final List<Entity> guards = new ArrayList<>();
	private LivingEntity praying;
	private long prayUntil = -1;
	private boolean called;
	private int potion;

	VillageElderFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		elder = (LivingEntity) spawn("minecraft:villager", x, y, z, villager("The Village Elder", "#9C5BB5", "cleric", 140, 2), true, false);
		later(10, this::bell);
	}

	@Override
	String name() {
		return "The Village Elder";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.PURPLE;
	}

	@Override
	LivingEntity body() {
		return elder;
	}

	@Override
	boolean alive() {
		return !dead(elder);
	}

	/** The golems standing close to him (his guards and the village's). */
	private boolean guarded() {
		for (Mob m : rallied()) if (m instanceof AbstractGolem && m.distanceToSqr(elder) < 4.5 * 4.5) return true;
		for (Entity g : guards) if (!dead(g) && g.distanceToSqr(elder) < 4.5 * 4.5) return true;
		return false;
	}

	@Override
	boolean allowDamage(LivingEntity piece, DamageSource source, float amount) {
		if (piece != elder || amount <= 0) return true;
		if (praying != null && source.getEntity() instanceof ServerPlayer) {
			praying = null;
			say(elder.position(), 40, styled("The Village Elder", RGB, true, false).copy().append(plain("'s prayer breaks off.")));
		}
		if (guarded()) {
			level.sendParticles(ParticleTypes.ENCHANTED_HIT, elder.getX(), elder.getY() + 2, elder.getZ(), 8, 0.5, 0.6, 0.5, 0.1);
			hurtAs(elder, amount * 0.5f, source.getEntity());
			return false;
		}
		return true;
	}

	/** The alarm bell: the village rallies (new ones join), faster and stronger for 8 s. */
	private void bell() {
		if (dead(elder)) return;
		Vec3 at = elder.position();
		int n = rally(at, 32);
		sound(at, SoundEvents.BELL_BLOCK, 3, 1);
		later(10, () -> sound(at, SoundEvents.BELL_BLOCK, 3, 1));
		later(20, () -> sound(at, SoundEvents.BELL_BLOCK, 3, 1));
		buffRallied(MobEffects.SPEED, 160, 0);
		buffRallied(MobEffects.STRENGTH, 160, 0);
		level.sendParticles(ParticleTypes.NOTE, at.x, at.y + 4, at.z, 12, 1, 0.5, 1, 1);
		say(at, 40, styled("The Village Elder ", RGB, true, false).copy().append(plain(n > 0 ? "rings the alarm bell: the village rises!" : "rings the alarm bell.")));
	}

	@Override
	void tick() {
		guards.removeIf(BossFight::dead);
		Vec3 at = elder.position();
		ServerPlayer p = nearest(at, 40);
		// Praying over a golem: he stands still and heals it.
		if (praying != null) {
			if (dead(praying) || knocked.contains(praying) || ticks >= prayUntil) {
				praying = null;
			} else {
				face(elder, praying.position());
				if (ticks % 4 == 0) {
					Vec3 from = elder.getEyePosition(), to = praying.position().add(0, praying.getBbHeight() / 2, 0);
					for (double f = 0; f <= 1; f += 0.1) {
						Vec3 q = from.add(to.subtract(from).scale(f));
						level.sendParticles(ParticleTypes.HAPPY_VILLAGER, q.x, q.y, q.z, 1, 0, 0, 0, 0);
					}
				}
				if (ticks % 10 == 0) praying.heal(3);
				return;
			}
		}
		// He keeps a few steps back, behind his golems.
		if (p != null) {
			double d = stride(elder, p, 0.05, 1.5, 4);
			if (d < 5 * 5) elder.setDeltaMovement(at.subtract(p.position()).multiply(1, 0, 1).normalize().scale(0.08).add(0, elder.getDeltaMovement().y, 0));
		}
		if (ticks % 600 == 590) bell();
		// The prayer every 12 s, for the most hurt golem near him.
		if (ticks % 240 == 120) {
			LivingEntity worst = null;
			List<LivingEntity> golems = new ArrayList<>();
			for (Mob m : rallied()) if (m instanceof AbstractGolem) golems.add(m);
			for (Entity g : guards) if (g instanceof LivingEntity l) golems.add(l);
			for (LivingEntity g : golems) if (g.distanceToSqr(elder) < 16 * 16 && share(g) < 0.9f && (worst == null || share(g) < share(worst))) worst = g;
			if (worst != null) {
				praying = worst;
				prayUntil = ticks + 60;
				elder.setDeltaMovement(Vec3.ZERO);
				sound(at, SoundEvents.VILLAGER_WORK_CLERIC, 2, 0.8f);
				say(at, 40, styled("The Village Elder ", RGB, true, false).copy().append(plain("prays over a golem. Stop him!")));
			}
		}
		// A splash potion every 7 s at a player: weakness, then slowness.
		List<ServerPlayer> ps = ticks % 140 == 70 ? near(at, 24) : List.of();
		if (!ps.isEmpty()) {
			ServerPlayer t = ps.get(random.nextInt(ps.size()));
			Vec3 spot = t.position();
			telegraph(spot, 2, ParticleTypes.WITCH);
			later(10, () -> telegraph(spot, 2, ParticleTypes.WITCH));
			dropFromAbove(spot, splash(potion++ % 2 == 0 ? "minecraft:weakness" : "minecraft:slowness"), 20);
			sound(at, SoundEvents.WITCH_THROW, 1.5f, 0.8f);
		}
		if (!called && share(elder) <= 0.5f) {
			called = true;
			for (int i = 0; i < 2; i++) {
				double a = random.nextDouble() * Math.PI * 2;
				Entity g = spawn("minecraft:iron_golem", at.x + Math.cos(a) * 3, at.y, at.z + Math.sin(a) * 3, GUARD, false, false);
				if (g instanceof Mob m) {
					Aggro.make(m, 6);
					guards.add(g);
					if (p != null) m.setTarget(p);
				}
			}
			sound(at, SoundEvents.IRON_GOLEM_REPAIR, 2, 0.7f);
			say(at, 40, styled("The Village Elder ", RGB, true, false).copy().append(plain("calls the Village Guard!")));
		}
	}
}
