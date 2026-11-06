package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The Hive Queen (NCC): a huge bee that keeps her distance and sends her swarm. While any of her bees live she heals,
 * so the swarm has to go first; now and then she dives at a player with a poison sting, and honey puddles slow those
 * she marks. Below 40 % a second swarm comes and she gets faster.
 */
final class HiveQueenFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Health:180f,CustomName:{text:\"The Hive Queen\",color:\"gold\",bold:true},"
			+ "attributes:[{id:\"minecraft:max_health\",base:180},{id:\"minecraft:scale\",base:3},{id:\"minecraft:knockback_resistance\",base:0.6},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final String WORKER = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,CustomName:\"Royal Guard\",attributes:[{id:\"minecraft:max_health\",base:6},"
			+ "{id:\"minecraft:flying_speed\",base:0.8}]}";

	private final LivingEntity queen;
	private final List<Entity> swarm = new ArrayList<>();
	private boolean second;
	private Vec3 dive;

	HiveQueenFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		queen = (LivingEntity) spawn("minecraft:bee", x, y + 2, z, BODY, true, false);
		if (queen instanceof Mob m) m.setNoAi(true);
		if (queen != null) queen.setNoGravity(true);
		swarm(6);
	}

	@Override
	String name() {
		return "The Hive Queen";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.YELLOW;
	}

	@Override
	LivingEntity body() {
		return queen;
	}

	@Override
	boolean alive() {
		return !dead(queen);
	}

	private void swarm(int n) {
		if (queen == null) return;
		for (int i = 0; i < n; i++) {
			Entity b = spawn("minecraft:bee", queen.getX() + random.nextGaussian(), queen.getY(), queen.getZ() + random.nextGaussian(), WORKER, false, false);
			if (b != null) swarm.add(b);
		}
		sound(queen.position(), SoundEvents.BEEHIVE_EXIT, 2, 0.7f);
	}

	@Override
	void tick() {
		swarm.removeIf(BossFight::dead);
		Vec3 at = queen.position();
		ServerPlayer target = nearest(at, 40);
		// Her bees keep at it: angry at the nearest player.
		if (ticks % 20 == 0) {
			for (Entity b : swarm) {
				ServerPlayer p = nearest(b.position(), 40);
				if (p == null) continue;
				if (b instanceof NeutralMob n) n.startPersistentAngerTimer();
				if (b instanceof Mob m) m.setTarget(p);
			}
			// Healing while any bee lives.
			if (!swarm.isEmpty() && queen.getHealth() < queen.getMaxHealth()) {
				queen.heal(4);
				level.sendParticles(ParticleTypes.HEART, at.x, at.y + 2, at.z, 2, 0.6, 0.4, 0.6, 0);
			}
		}
		// Flying: a dive at a player, else hovering some 6 blocks from the nearest one.
		double speed = second ? 0.35 : 0.25;
		if (dive != null) {
			Vec3 to = dive.subtract(at);
			if (to.lengthSqr() < 2) {
				for (ServerPlayer p : near(dive, 2.5)) {
					p.hurtServer(level, level.damageSources().mobAttack(queen), 6);
					effect(p, MobEffects.POISON, 120, 1);
				}
				sound(dive, SoundEvents.BEE_STING, 1.5f, 0.6f);
				dive = null;
			} else {
				queen.setDeltaMovement(to.normalize().scale(speed * 2.2));
			}
		} else if (target != null) {
			Vec3 want = target.position().add(0, 4, 0);
			Vec3 away = at.subtract(target.position()).multiply(1, 0, 1);
			if (away.lengthSqr() > 0.01) want = want.add(away.normalize().scale(6));
			Vec3 to = want.subtract(at);
			queen.setDeltaMovement(to.lengthSqr() > 1 ? to.normalize().scale(speed) : Vec3.ZERO);
			Vec3 look = target.position().subtract(at);
			queen.setYRot((float) (Math.toDegrees(Math.atan2(look.z, look.x)) - 90));
			queen.setYHeadRot(queen.getYRot());
		}
		if (target != null && ticks % 160 == 80) {
			dive = target.position().add(0, 1, 0);
			sound(at, SoundEvents.BEE_LOOP_AGGRESSIVE, 2, 0.6f);
		}
		// Honey puddles every 8 s.
		if (ticks % 160 == 0) {
			ServerPlayer p = nearest(at, 24);
			if (p != null) {
				// Stuck in honey: everyone near that spot is slowed hard for a while.
				Vec3 spot = p.position();
				for (int i = 0; i < 6; i++) later(i * 20, () -> level.sendParticles(ParticleTypes.DRIPPING_HONEY, spot.x, spot.y + 0.2, spot.z, 25, 1.4, 0.1, 1.4, 0));
				for (ServerPlayer q : near(spot, 2.5)) effect(q, MobEffects.SLOWNESS, 120, 2);
				sound(spot, SoundEvents.HONEY_BLOCK_PLACE, 1, 0.8f);
			}
		}
		// New bees every 20 s if the swarm is down to two.
		if (ticks % 400 == 0 && swarm.size() <= 2) swarm(4);
		if (!second && share(queen) <= 0.4f) {
			second = true;
			say(at, 40, styled("The Hive Queen: ", 0xFFAA00, true, false).copy().append(plain("Protect your queen!")));
			swarm(6);
		}
	}
}
