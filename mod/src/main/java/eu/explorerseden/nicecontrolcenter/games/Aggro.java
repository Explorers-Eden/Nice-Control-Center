package eu.explorerseden.nicecontrolcenter.games;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.RangedAttackGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.LlamaSpit;

import eu.explorerseden.nicecontrolcenter.mixin.MobGoalsAccessor;

/**
 * Games: peaceful and neutral mobs in wave arenas and dungeons attack players. Neutral ones (iron golems, llamas,
 * bees, wolves, polar bears, pandas, endermen…) are hostile right away and stay angry. How they fight: mobs that
 * already shoot (llamas, snow golems) shoot; fliers and water mobs without an attack of their own (parrots, bats,
 * allays, fish, squid) spit from a distance; everything else charges in, using its own bite or swing when it has one.
 */
public final class Aggro {
	/** Mobs given their goals already (goals aren't saved: after a restart they're given them again). */
	private static final Set<Mob> done = Collections.newSetFromMap(new WeakHashMap<>());

	/** By id: 26.1 keeps it in EntityType, 26.3 in EntityTypes. */
	@SuppressWarnings("unchecked")
	private static final EntityType<LlamaSpit> SPIT = (EntityType<LlamaSpit>) net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.withDefaultNamespace("llama_spit"));

	private Aggro() {
	}

	/** Makes the mob fight players (melee damage when it has no attack of its own); hostile mobs are left as they are. */
	public static void make(Mob m, float damage) {
		if (m instanceof Enemy && !(m instanceof NeutralMob)) return;
		if (!done.add(m)) return;
		MobGoalsAccessor acc = (MobGoalsAccessor) m;
		acc.nicecontrolcenter$targets().addGoal(0, new NearestAttackableTargetGoal<>(m, Player.class, false));
		acc.nicecontrolcenter$goals().addGoal(0, new Anger(m));
		boolean ownAttack = m.getAttribute(Attributes.ATTACK_DAMAGE) != null;
		boolean cantWalk = m.getNavigation() instanceof FlyingPathNavigation || m.getNavigation() instanceof WaterBoundPathNavigation || m instanceof AmbientCreature;
		if (m instanceof RangedAttackMob shooter) acc.nicecontrolcenter$goals().addGoal(1, new RangedAttackGoal(shooter, 1.2, 40, 14f));
		else if (cantWalk && !ownAttack) acc.nicecontrolcenter$goals().addGoal(1, new Spit(m));
		else if (m instanceof PathfinderMob walker) acc.nicecontrolcenter$goals().addGoal(1, new Charge(walker, damage, ownAttack));
		else acc.nicecontrolcenter$goals().addGoal(1, new Spit(m));
	}

	private static LivingEntity target(Mob m) {
		LivingEntity t = m.getTarget();
		return t instanceof Player p && p.isAlive() && !p.isSpectator() && !p.isCreative() ? t : null;
	}

	/** Neutral mobs stay angry at the player they're after (bees sting, golems swing, wolves bite). */
	private static final class Anger extends Goal {
		private final Mob mob;

		Anger(Mob mob) {
			this.mob = mob;
		}

		@Override
		public boolean canUse() {
			return mob instanceof NeutralMob && target(mob) != null;
		}

		@Override
		public void tick() {
			if (mob instanceof NeutralMob n && mob.tickCount % 20 == 0) n.startPersistentAngerTimer();
		}
	}

	/** Runs at the player and hits them: with its own attack when it has one, else plain damage. */
	private static final class Charge extends Goal {
		private final PathfinderMob mob;
		private final float damage;
		private final boolean own;
		private int cooldown;

		Charge(PathfinderMob mob, float damage, boolean own) {
			this.mob = mob;
			this.damage = damage;
			this.own = own;
			setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
		}

		@Override
		public boolean canUse() {
			return target(mob) != null;
		}

		@Override
		public boolean requiresUpdateEveryTick() {
			return true;
		}

		@Override
		public void stop() {
			mob.getNavigation().stop();
		}

		@Override
		public void tick() {
			LivingEntity t = target(mob);
			if (t == null || !(mob.level() instanceof ServerLevel level)) return;
			mob.getLookControl().setLookAt(t, 30f, 30f);
			if (mob.tickCount % 10 == 0) mob.getNavigation().moveTo(t, 1.4);
			if (cooldown > 0) cooldown--;
			if (cooldown > 0 || !mob.isWithinMeleeAttackRange(t)) return;
			cooldown = 20;
			if (own) mob.doHurtTarget(level, t);
			else t.hurtServer(level, mob.damageSources().mobAttack(mob), damage);
		}
	}

	/** Keeps its distance and spits at the player (1 damage a spit, the mob gets the credit). */
	private static final class Spit extends Goal {
		private final Mob mob;
		private int cooldown = 20;

		Spit(Mob mob) {
			this.mob = mob;
			setFlags(EnumSet.of(Flag.LOOK));
		}

		@Override
		public boolean canUse() {
			LivingEntity t = target(mob);
			return t != null && mob.distanceToSqr(t) < 20 * 20;
		}

		@Override
		public boolean requiresUpdateEveryTick() {
			return true;
		}

		@Override
		public void tick() {
			LivingEntity t = target(mob);
			if (t == null) return;
			mob.getLookControl().setLookAt(t, 30f, 30f);
			// Closer when far off (fliers and swimmers move on their own otherwise).
			if (mob.tickCount % 20 == 0 && mob.distanceToSqr(t) > 10 * 10) mob.getNavigation().moveTo(t, 1.2);
			if (--cooldown > 0 || !mob.hasLineOfSight(t)) return;
			cooldown = 40;
			LlamaSpit spit = new LlamaSpit(SPIT, mob.level());
			spit.setOwner(mob);
			spit.setPos(mob.getX(), mob.getEyeY() - 0.1, mob.getZ());
			double dx = t.getX() - mob.getX(), dy = t.getY(0.33) - spit.getY(), dz = t.getZ() - mob.getZ();
			spit.shoot(dx, dy + Math.sqrt(dx * dx + dz * dz) * 0.2, dz, 1.5f, 6f);
			mob.level().addFreshEntity(spit);
			mob.playSound(net.minecraft.sounds.SoundEvents.LLAMA_SPIT, 1f, 1.2f);
		}
	}
}
