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
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.phys.Vec3;

/**
 * The Dire Alpha (NCC): a big wolf in armour leading a pack. It pounces on players, howls for more wolves, and its
 * bites make them bleed (wither). Below a quarter of its health the pack runs off and it fights on alone, faster.
 */
final class DireAlphaFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Health:160f,CustomName:{text:\"The Dire Alpha\",color:\"#8B8B8B\",bold:true},"
			+ "equipment:{body:{id:\"minecraft:wolf_armor\",count:1}},drop_chances:{body:0.0},attributes:[{id:\"minecraft:max_health\",base:160},{id:\"minecraft:scale\",base:2},"
			+ "{id:\"minecraft:attack_damage\",base:6},{id:\"minecraft:knockback_resistance\",base:0.6},{id:\"minecraft:follow_range\",base:40},{id:\"minecraft:step_height\",base:1.5},"
			+ "{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final String PACK = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,CustomName:\"Pack Wolf\",attributes:[{id:\"minecraft:attack_damage\",base:3}]}";

	/** (Wolf sounds come in variants now: the classic ones by id.) */
	private static final net.minecraft.sounds.SoundEvent HOWL = net.minecraft.sounds.SoundEvent.createVariableRangeEvent(net.minecraft.resources.Identifier.withDefaultNamespace("entity.wolf.howl"));
	private static final net.minecraft.sounds.SoundEvent GROWL = net.minecraft.sounds.SoundEvent.createVariableRangeEvent(net.minecraft.resources.Identifier.withDefaultNamespace("entity.wolf.growl"));

	private final LivingEntity alpha;
	private final List<Entity> pack = new ArrayList<>();
	private boolean alone;
	private ServerPlayer pounce;
	private long pounceUntil = -1;

	DireAlphaFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		alpha = (LivingEntity) spawn("minecraft:wolf", x, y, z, BODY, true, false);
		howl(2);
	}

	@Override
	String name() {
		return "The Dire Alpha";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.WHITE;
	}

	@Override
	LivingEntity body() {
		return alpha;
	}

	@Override
	boolean alive() {
		return !dead(alpha);
	}

	/** Its bites (and the pack's) make players bleed. */
	@Override
	void hit(ServerPlayer victim, Entity attacker) {
		if (attacker == alpha) effect(victim, MobEffects.WITHER, 60, 0);
	}

	private void howl(int n) {
		if (alpha == null) return;
		sound(alpha.position(), HOWL, 3, 0.7f);
		for (int i = 0; i < n && pack.size() < 6; i++) {
			Entity w = spawn("minecraft:wolf", alpha.getX() + random.nextGaussian() * 2, alpha.getY(), alpha.getZ() + random.nextGaussian() * 2, PACK, false, false);
			if (w != null) pack.add(w);
		}
	}

	/** Angry at the nearest player. */
	private void hunt(Entity wolf) {
		ServerPlayer p = nearest(wolf.position(), 40);
		if (p == null) return;
		if (wolf instanceof NeutralMob n) n.startPersistentAngerTimer();
		if (wolf instanceof Mob m) m.setTarget(p);
	}

	@Override
	void tick() {
		pack.removeIf(BossFight::dead);
		Vec3 at = alpha.position();
		if (ticks % 20 == 0) {
			hunt(alpha);
			for (Entity w : pack) hunt(w);
		}
		// The pounce: in the air, then whoever it lands next to.
		if (pounce != null) {
			if (ticks >= pounceUntil || alpha.onGround() && ticks > pounceUntil - 14) {
				for (ServerPlayer p : near(alpha.position(), 2.5)) {
					p.hurtServer(level, level.damageSources().mobAttack(alpha), 6);
					effect(p, MobEffects.WITHER, 60, 0);
				}
				pounce = null;
			}
			return;
		}
		// A pounce every 7 s (every 4 when alone).
		if (ticks % (alone ? 80 : 140) == 40) {
			ServerPlayer p = nearest(at, 16);
			if (p != null && p.distanceToSqr(alpha) > 9) {
				Vec3 d = p.position().subtract(at);
				alpha.setDeltaMovement(d.x / 14, 0.6, d.z / 14);
				pounce = p;
				pounceUntil = ticks + 20;
				sound(at, GROWL, 2, 0.6f);
			}
		}
		// A howl every 18 s while the pack is with it.
		if (!alone && ticks % 360 == 180) howl(3);
		if (!alone && share(alpha) <= 0.25f) {
			alone = true;
			for (Entity w : pack) {
				level.sendParticles(ParticleTypes.POOF, w.getX(), w.getY() + 0.5, w.getZ(), 10, 0.3, 0.3, 0.3, 0.02);
				w.discard();
			}
			pack.clear();
			effect(alpha, MobEffects.SPEED, -1, 1);
			effect(alpha, MobEffects.STRENGTH, -1, 0);
			sound(at, HOWL, 3, 0.5f);
			say(at, 40, styled("The Dire Alpha's ", 0x8B8B8B, true, false).copy().append(plain("pack scatters. It fights on alone, desperate.")));
		}
	}
}
