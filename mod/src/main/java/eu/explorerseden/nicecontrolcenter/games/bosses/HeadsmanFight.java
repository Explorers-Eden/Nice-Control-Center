package eu.explorerseden.nicecontrolcenter.games.bosses;

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
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * The Headsman (NCC): a giant vindicator with an axe. He spins (a warned ring), leaps onto marked spots, knocks
 * shields out of use, and marks the weakest player for execution: 5 seconds of a stronger axe after them, unless the
 * others hit him hard enough to break it. Below 30 % he's in a frenzy.
 */
final class HeadsmanFight extends BossFight {
	private static final int RGB = 0x5A6B6B;
	/** Attack -2 + the iron axe's 8: hits of 6 (vanilla's would be 13). */
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Health:160f,CustomName:{text:\"The Headsman\",color:\"#5A6B6B\",bold:true},"
			+ "equipment:{mainhand:{id:\"minecraft:iron_axe\",count:1}},drop_chances:{mainhand:0f},"
			+ "attributes:[{id:\"minecraft:max_health\",base:160},{id:\"minecraft:scale\",base:2.2},{id:\"minecraft:attack_damage\",base:-2},{id:\"minecraft:follow_range\",base:40},"
			+ "{id:\"minecraft:knockback_resistance\",base:0.7},{id:\"minecraft:step_height\",base:1.5},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";

	private final LivingEntity headsman;
	private ServerPlayer marked;
	private long markUntil = -1;
	/** Damage the others did him since the mark (12 breaks it). */
	private float rescue;
	private boolean frenzy;
	private long nextSpin = 60, nextLeap = 160;

	HeadsmanFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		headsman = (LivingEntity) spawn("minecraft:vindicator", x, y, z, BODY, true, false);
		if (headsman instanceof Raider r) r.setCanJoinRaid(false);
	}

	@Override
	String name() {
		return "The Headsman";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.WHITE;
	}

	@Override
	LivingEntity body() {
		return headsman;
	}

	@Override
	boolean alive() {
		return !dead(headsman);
	}

	/** His blows knock shields out of use for 5 s. */
	@Override
	void hit(ServerPlayer victim, Entity attacker) {
		if (attacker != headsman) return;
		if (victim.isBlocking() || victim.getUseItem().is(Items.SHIELD)) {
			victim.getCooldowns().addCooldown(victim.getUseItem(), 100);
			victim.stopUsingItem();
			sound(victim.position(), SoundEvents.SHIELD_BREAK.value(), 1.5f, 0.8f);
		}
	}

	@Override
	void damaged(LivingEntity piece, DamageSource source, float amount) {
		if (piece != headsman || marked == null || !(source.getEntity() instanceof ServerPlayer p) || p == marked) return;
		rescue += amount;
		if (rescue >= 12) {
			say(headsman.position(), 40, styled("The Headsman ", RGB, true, false).copy().append(plain("is driven off " + marked.getName().getString() + "!")));
			unmark();
		}
	}

	private void unmark() {
		if (marked != null) marked.setGlowingTag(false);
		marked = null;
		headsman.removeEffect(MobEffects.STRENGTH);
		if (frenzy) effect(headsman, MobEffects.STRENGTH, -1, 0);
	}

	@Override
	void tick() {
		Vec3 at = headsman.position();
		ServerPlayer p = nearest(at, 40);
		if (marked != null) {
			if (ticks >= markUntil || dead(marked) || marked.level() != level || viewers != null && !viewers.test(marked)) unmark();
			else {
				if (headsman instanceof Mob m) m.setTarget(marked);
				if (ticks % 5 == 0) level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, marked.getX(), marked.getY() + 2.3, marked.getZ(), 1, 0, 0, 0, 0);
			}
		} else if (ticks % 20 == 0 && p != null && headsman instanceof Mob m && m.getTarget() == null) m.setTarget(p);
		// The axe spin: a ring warns, then everyone within 4.5 blocks takes 6 and is thrown out.
		if (ticks >= nextSpin && !near(at, 5).isEmpty()) {
			nextSpin = ticks + (frenzy ? 100 : 160);
			for (int t = 0; t < 20; t += 4) later(t, () -> telegraph(headsman.position(), 4.5, ParticleTypes.SWEEP_ATTACK));
			effect(headsman, MobEffects.SLOWNESS, 20, 3);
			later(20, () -> {
				if (dead(headsman)) return;
				float base = headsman.getYRot();
				for (int i = 0; i < 8; i++) {
					float yaw = base + 45 * (i + 1);
					later(i, () -> {
						headsman.setYRot(yaw);
						headsman.setYBodyRot(yaw);
					});
				}
				sound(headsman.position(), SoundEvents.PLAYER_ATTACK_SWEEP, 2.5f, 0.5f);
				shockwave(headsman, headsman.position(), 4.5, 6, 1.1, 0.4);
			});
		}
		// The leap every 9 s at someone 6–16 blocks off.
		if (ticks >= nextLeap && p != null) {
			ServerPlayer far = null;
			for (ServerPlayer q : near(at, 16)) if (q.distanceToSqr(headsman) > 36) far = q;
			if (far != null) {
				nextLeap = ticks + (frenzy ? 120 : 180);
				Vec3 spot = Vec3.atBottomCenterOf(groundAt(far.position()));
				for (int t = 0; t < 20; t += 4) later(t, () -> telegraph(spot, 3, ParticleTypes.CRIT));
				later(20, () -> {
					if (dead(headsman)) return;
					Vec3 d = spot.subtract(headsman.position());
					headsman.setDeltaMovement(d.x / 14, 0.9, d.z / 14);
					sound(headsman.position(), SoundEvents.VINDICATOR_CELEBRATE, 2, 0.6f);
				});
				later(36, () -> {
					if (dead(headsman)) return;
					sound(headsman.position(), SoundEvents.ANVIL_LAND, 2, 0.5f);
					shockwave(headsman, headsman.position(), 3.5, 7, 0.9, 0.5);
				});
			}
		}
		// Execution every 15 s: the weakest player in the fight.
		if (marked == null && ticks % 300 == 200) {
			ServerPlayer weakest = null;
			for (ServerPlayer q : players(at)) if (q.distanceToSqr(headsman) < 32 * 32 && (weakest == null || q.getHealth() < weakest.getHealth())) weakest = q;
			if (weakest != null) {
				marked = weakest;
				markUntil = ticks + 100;
				rescue = 0;
				marked.setGlowingTag(true);
				effect(headsman, MobEffects.STRENGTH, 100, frenzy ? 2 : 1);
				sound(at, SoundEvents.VINDICATOR_AMBIENT, 2, 0.5f);
				say(at, 40, styled("The Headsman ", RGB, true, false).copy().append(plain("marks " + weakest.getName().getString() + " for execution! Hit him to drive him off.")));
			}
		}
		if (!frenzy && share(headsman) <= 0.3f) {
			frenzy = true;
			effect(headsman, MobEffects.SPEED, -1, 1);
			if (marked == null) effect(headsman, MobEffects.STRENGTH, -1, 0);
			say(at, 40, styled("The Headsman ", RGB, true, false).copy().append(plain("flies into a frenzy!")));
		}
	}

	@Override
	void ended() {
		if (marked != null) marked.setGlowingTag(false);
	}
}
