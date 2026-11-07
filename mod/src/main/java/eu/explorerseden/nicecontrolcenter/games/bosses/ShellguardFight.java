package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The Shellguard (NCC): a giant armadillo. Hit three times quickly and it curls up for four seconds, taking no damage
 * (a mace still gets through); rolled up it charges along a marked line. Scute shrapnel flies around it, and it
 * burrows to come up under a marked player.
 */
final class ShellguardFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Health:140f,CustomName:{text:\"The Shellguard\",color:\"#B5734F\",bold:true},"
			+ "attributes:[{id:\"minecraft:max_health\",base:140},{id:\"minecraft:scale\",base:3},{id:\"minecraft:knockback_resistance\",base:1},{id:\"minecraft:step_height\",base:1.5},"
			+ "{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";

	private final LivingEntity shell;
	/** When it was hit lately (three within 3 s curl it up). */
	private final List<Long> hits = new ArrayList<>();
	private long curledUntil = -1;
	private Vec3 roll;
	private long rollUntil = -1;
	private final Set<ServerPlayer> rolled = new HashSet<>();
	private long burrowUntil = -1;

	ShellguardFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		shell = (LivingEntity) spawn("minecraft:armadillo", x, y, z, BODY, true, false);
	}

	@Override
	String name() {
		return "The Shellguard";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.RED;
	}

	@Override
	LivingEntity body() {
		return shell;
	}

	@Override
	boolean alive() {
		return !dead(shell);
	}

	private boolean curled() {
		return ticks < curledUntil;
	}

	private void curl(boolean on) {
		if (shell instanceof Armadillo a) a.switchToState(on ? Armadillo.ArmadilloState.SCARED : Armadillo.ArmadilloState.IDLE);
	}

	/** Curled up: no damage (a mace still gets through). Three quick hits curl it up. */
	@Override
	boolean allowDamage(LivingEntity piece, DamageSource source, float amount) {
		if (piece != shell) return true;
		if (ticks < burrowUntil) return false;
		boolean mace = source.getEntity() instanceof ServerPlayer p && p.getMainHandItem().is(Items.MACE);
		if (curled() && !mace) {
			level.sendParticles(ParticleTypes.CRIT, shell.getX(), shell.getY() + 1, shell.getZ(), 8, 0.6, 0.6, 0.6, 0.1);
			sound(shell.position(), SoundEvents.ARMADILLO_HURT_REDUCED, 1.5f, 1);
			return false;
		}
		if (source.getEntity() instanceof ServerPlayer) {
			hits.add(ticks);
			hits.removeIf(t -> t < ticks - 60);
			if (hits.size() >= 3 && !curled()) {
				hits.clear();
				curledUntil = ticks + 80;
				curl(true);
				sound(shell.position(), SoundEvents.ARMADILLO_ROLL, 2, 0.7f);
			}
		}
		return true;
	}

	@Override
	void tick() {
		Vec3 at = shell.position();
		if (curled()) {
			curl(true);
			if (ticks == curledUntil - 1) curl(false);
		}
		// Burrowed (hidden, untouchable) until it comes up.
		if (ticks < burrowUntil) {
			shell.setDeltaMovement(0, shell.getDeltaMovement().y, 0);
			return;
		}
		// Rolling along the line.
		if (ticks <= rollUntil && roll != null) {
			Vec3 dir = roll.subtract(at).multiply(1, 0, 1);
			if (dir.lengthSqr() > 1) shell.setDeltaMovement(dir.normalize().scale(0.95).add(0, shell.getDeltaMovement().y, 0));
			for (ServerPlayer p : near(at, 2.3)) {
				if (!rolled.add(p)) continue;
				p.hurtServer(level, level.damageSources().mobAttack(shell), 6);
				push(p, dir.normalize().scale(1.7).add(0, 0.5, 0));
			}
			return;
		}
		ServerPlayer p = nearest(at, 32);
		if (p == null) return;
		if (!curled()) {
			Vec3 d = p.position().subtract(at).multiply(1, 0, 1);
			float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90);
			shell.setYRot(yaw);
			shell.setYHeadRot(yaw);
			if (d.lengthSqr() > 4) shell.setDeltaMovement(d.normalize().scale(0.12).add(0, shell.getDeltaMovement().y, 0));
		}
		// A rolled charge every 9 s along a marked line.
		if (ticks % 180 == 40) {
			Vec3 d = p.position().subtract(at).multiply(1, 0, 1);
			Vec3 end = d.lengthSqr() < 0.01 ? p.position() : at.add(d.normalize().scale(Math.sqrt(d.lengthSqr()) + 5));
			for (int t = 0; t < 20; t += 4) later(t, () -> {
				for (double f = 0; f <= 1; f += 0.05) {
					Vec3 q = at.add(end.subtract(at).scale(f));
					level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.COARSE_DIRT.defaultBlockState()), q.x, q.y + 0.1, q.z, 1, 0, 0, 0, 0);
				}
			});
			later(20, () -> {
				if (dead(shell)) return;
				roll = end;
				rollUntil = ticks + 24;
				rolled.clear();
				curl(true);
				later(24, () -> {
					if (!curled()) curl(false);
				});
			});
		}
		// Scute shrapnel every 7 s: a ring of hurt around it.
		if (ticks % 140 == 100 && !near(at, 6).isEmpty()) {
			for (int t = 0; t < 15; t += 5) later(t, () -> telegraph(shell.position(), 5, ParticleTypes.CRIT));
			later(15, () -> {
				sound(shell.position(), SoundEvents.ARMADILLO_SCUTE_DROP, 2, 0.8f);
				for (ServerPlayer q : near(shell.position(), 5.2)) q.hurtServer(level, level.damageSources().mobAttack(shell), 3);
			});
		}
		// Burrowing every 16 s: gone for 2 s, then up under a marked player.
		if (ticks % 320 == 220) {
			Vec3 spot = Vec3.atBottomCenterOf(groundAt(p.position()));
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DIRT.defaultBlockState()), at.x, at.y + 0.5, at.z, 40, 1, 0.5, 1, 0.1);
			sound(at, SoundEvents.ROOTED_DIRT_BREAK, 2, 0.6f);
			effect(shell, MobEffects.INVISIBILITY, 40, 0);
			burrowUntil = ticks + 40;
			for (int t = 0; t < 40; t += 5) later(t, () -> telegraph(spot, 2, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DIRT.defaultBlockState())));
			later(40, () -> {
				if (dead(shell)) return;
				shell.removeEffect(MobEffects.INVISIBILITY);
				shell.teleportTo(spot.x, spot.y, spot.z);
				level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DIRT.defaultBlockState()), spot.x, spot.y + 0.5, spot.z, 50, 1.2, 0.5, 1.2, 0.2);
				shockwave(shell, spot, 3, 6, 0.6, 0.9);
			});
		}
	}
}
