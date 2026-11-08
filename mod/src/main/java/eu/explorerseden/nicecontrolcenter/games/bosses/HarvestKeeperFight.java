package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import eu.explorerseden.nicecontrolcenter.games.Aggro;

/**
 * The Harvest Keeper (NCC): the village farmer with a scythe. He sweeps everything in front of him, tosses hay bales
 * where rings warned, mends while his pumpkin scarecrows stand, keeps snow golem helpers, and every 30 seconds sits
 * down to eat: he heals, but takes double damage while he eats.
 */
final class HarvestKeeperFight extends BossFight {
	private static final int RGB = 0xC98A2E;
	private static final String HELPER = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Pumpkin:1b,CustomName:\"Field Helper\","
			+ "attributes:[{id:\"minecraft:max_health\",base:12}]}";

	private final LivingEntity keeper;
	private final List<Target> scarecrows = new ArrayList<>();
	private final List<Entity> helpers = new ArrayList<>();
	private long feastUntil = -1;

	HarvestKeeperFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		keeper = (LivingEntity) spawn("minecraft:villager", x, y, z, villager("The Harvest Keeper", "#C98A2E", "farmer", 150, 2.5), true, false);
		Vec3 c = new Vec3(x, y, z);
		for (int i = 0; i < 3; i++) {
			double a = Math.PI * 2 * i / 3 + random.nextDouble();
			scarecrows.add(target("minecraft:carved_pumpkin", Vec3.atBottomCenterOf(groundAt(c.add(Math.cos(a) * 7, 1, Math.sin(a) * 7))), 25, "Scarecrow", true));
		}
		for (int i = 0; i < 2; i++) helper(c);
		later(10, () -> {
			if (!dead(keeper) && rally(keeper.position(), 32) > 0)
				say(keeper.position(), 40, styled("The Harvest Keeper ", RGB, true, false).copy().append(plain("whistles: the whole village leaves the fields to fight.")));
		});
	}

	private void helper(Vec3 c) {
		Entity g = spawn("minecraft:snow_golem", c.x + random.nextGaussian() * 2, c.y, c.z + random.nextGaussian() * 2, HELPER, false, false);
		if (g instanceof Mob m) {
			Aggro.make(m, 2);
			helpers.add(g);
		}
	}

	@Override
	String name() {
		return "The Harvest Keeper";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.YELLOW;
	}

	@Override
	LivingEntity body() {
		return keeper;
	}

	@Override
	boolean alive() {
		return !dead(keeper);
	}

	private boolean feasting() {
		return ticks < feastUntil;
	}

	/** Double damage while he eats. */
	@Override
	boolean allowDamage(LivingEntity piece, DamageSource source, float amount) {
		if (piece == keeper && feasting() && amount > 0) {
			hurtAs(keeper, amount * 2, source.getEntity());
			return false;
		}
		return true;
	}

	@Override
	void tick() {
		helpers.removeIf(BossFight::dead);
		scarecrows.removeIf(Target::broken);
		Vec3 at = keeper.position();
		// The snow his helpers leave melts again.
		if (ticks % 10 == 0) for (Entity h : helpers) if (level.getBlockState(h.blockPosition()).is(Blocks.SNOW)) litter(h.blockPosition(), 60);
		// Mending while a scarecrow stands (a green line from each).
		if (!scarecrows.isEmpty() && ticks % 40 == 0 && keeper.getHealth() < keeper.getMaxHealth()) {
			keeper.heal(scarecrows.size());
			for (Target s : scarecrows) {
				Vec3 from = s.hitbox.position().add(0, 0.5, 0), to = at.add(0, 2, 0);
				for (double f = 0; f <= 1; f += 0.1) {
					Vec3 q = from.add(to.subtract(from).scale(f));
					level.sendParticles(ParticleTypes.HAPPY_VILLAGER, q.x, q.y, q.z, 1, 0, 0, 0, 0);
				}
			}
		}
		if (feasting()) {
			keeper.setDeltaMovement(Vec3.ZERO.add(0, keeper.getDeltaMovement().y, 0));
			if (ticks % 5 == 0) level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, Items.BREAD), at.x, at.y + 3, at.z, 4, 0.3, 0.2, 0.3, 0.05);
			if (ticks % 10 == 0) {
				keeper.heal(1);
				sound(at, SoundEvents.GENERIC_EAT.value(), 1.5f, 0.7f);
			}
			return;
		}
		ServerPlayer p = nearest(at, 40);
		if (p != null) stride(keeper, p, 0.07, 2, 5);
		// The scythe every 7 s: a wind-up, then everything in front of him.
		if (ticks % 140 == 50 && p != null && p.distanceToSqr(keeper) < 7 * 7) {
			face(keeper, p.position());
			Vec3 look = p.position().subtract(at).multiply(1, 0, 1).normalize();
			for (int t = 0; t < 15; t += 3) later(t, () -> {
				for (int r = 2; r <= 6; r += 2) for (int k = -2; k <= 2; k++) {
					double a = Math.atan2(look.z, look.x) + k * 0.3;
					level.sendParticles(ParticleTypes.SWEEP_ATTACK, at.x + Math.cos(a) * r, at.y + 0.5, at.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
				}
			});
			later(15, () -> {
				if (dead(keeper)) return;
				sound(keeper.position(), SoundEvents.PLAYER_ATTACK_SWEEP, 2, 0.6f);
				for (ServerPlayer q : near(keeper.position(), 7)) {
					Vec3 to = q.position().subtract(keeper.position()).multiply(1, 0, 1);
					if (to.lengthSqr() > 0.01 && to.normalize().dot(look) < 0.5) continue;
					q.hurtServer(level, level.damageSources().mobAttack(keeper), 6);
					push(q, look.scale(1.1).add(0, 0.4, 0));
				}
			});
		}
		// Hay bales every 9 s at the players.
		if (ticks % 180 == 90) {
			for (ServerPlayer q : near(at, 28)) {
				dropBlock(q.position(), "minecraft:hay_block", 25, keeper, 1.8, 4, v -> {
					effect(v, MobEffects.SLOWNESS, 60, 1);
					push(v, new Vec3(random.nextGaussian() * 0.4, 0.4, random.nextGaussian() * 0.4));
				});
			}
			sound(at, SoundEvents.GRASS_BREAK, 2, 0.6f);
		}
		// Another helper every 20 s (four at most).
		if (ticks % 400 == 300 && helpers.size() < 4) {
			helper(at);
			sound(at, SoundEvents.SNOW_GOLEM_AMBIENT, 1.5f, 0.8f);
		}
		// Supper every 30 s: 3 s of eating.
		if (ticks % 600 == 500) {
			feastUntil = ticks + 60;
			say(at, 40, styled("The Harvest Keeper ", RGB, true, false).copy().append(plain("sits down to eat… hit him while he chews!")));
		}
	}

	@Override
	void ended() {
		// (Snow left under the helpers that just died.)
		for (Entity h : helpers) {
			BlockPos b = h.blockPosition();
			if (level.getBlockState(b).is(Blocks.SNOW)) level.removeBlock(b, false);
		}
	}
}
