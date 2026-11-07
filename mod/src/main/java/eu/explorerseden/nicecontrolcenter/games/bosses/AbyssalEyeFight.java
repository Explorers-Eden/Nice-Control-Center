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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The Abyssal Eye (NCC): an elder guardian floating over the arena. It locks its laser on a player (a beam of
 * particles for two seconds) and fires, unless a block is in the way: take cover. Its gaze tires everyone (mining
 * fatigue), its spikes hurt whoever hits it close up, and at half health three guardians join.
 */
final class AbyssalEyeFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,NoAI:1b,NoGravity:1b,Health:160f,CustomName:{text:\"The Abyssal Eye\",color:\"#4FB3A9\",bold:true},"
			+ "attributes:[{id:\"minecraft:max_health\",base:160},{id:\"minecraft:scale\",base:1.5},{id:\"minecraft:knockback_resistance\",base:1},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final String ADD = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,CustomName:\"Abyssal Spawn\"}";

	private final LivingEntity eye;
	private final Vec3 home;
	private ServerPlayer locked;
	private long fireAt = -1;
	private boolean adds;
	private final List<Entity> spawned = new ArrayList<>();

	AbyssalEyeFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		double up = bounds == null ? y + 4 : Math.min(y + 4, bounds.maxY - 4);
		home = new Vec3(x, up, z);
		eye = (LivingEntity) spawn("minecraft:elder_guardian", x, up, z, BODY, true, false);
	}

	@Override
	String name() {
		return "The Abyssal Eye";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.GREEN;
	}

	@Override
	LivingEntity body() {
		return eye;
	}

	@Override
	boolean alive() {
		return !dead(eye);
	}

	/** Spikes: whoever hits it up close is hurt back. */
	@Override
	boolean allowDamage(LivingEntity piece, DamageSource source, float amount) {
		if (piece == eye && source.getDirectEntity() instanceof ServerPlayer p) p.hurtServer(level, level.damageSources().thorns(eye), 2);
		return true;
	}

	@Override
	void tick() {
		spawned.removeIf(BossFight::dead);
		Vec3 at = eye.position();
		// Hovering at its spot, slowly turning to whoever it watches.
		ServerPlayer watch = locked != null ? locked : nearest(at, 40);
		Vec3 back = home.subtract(at);
		eye.setDeltaMovement(back.lengthSqr() > 1 ? back.normalize().scale(0.08) : Vec3.ZERO);
		if (watch != null) {
			Vec3 d = watch.getEyePosition().subtract(eye.getEyePosition());
			float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90);
			eye.setYRot(yaw);
			eye.setYHeadRot(yaw);
			eye.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
		}
		// Gaze: mining fatigue around it.
		if (ticks % 40 == 0) for (ServerPlayer p : near(at, 40)) effect(p, MobEffects.MINING_FATIGUE, 120, 1);
		// The laser: locked for 2 s, then it fires unless cover is in the way.
		if (locked != null) {
			// (Gone from the fight meanwhile: out, another dimension, too far: no shot.)
			if (locked.isRemoved() || !locked.isAlive() || locked.level() != level || viewers != null && !viewers.test(locked) || locked.distanceToSqr(eye) > 48 * 48) {
				locked = null;
			} else if (ticks % 2 == 0) {
				Vec3 from = eye.getEyePosition(), to = locked.getEyePosition();
				for (double f = 0; f <= 1; f += 0.04) {
					Vec3 q = from.add(to.subtract(from).scale(f));
					level.sendParticles(ParticleTypes.BUBBLE_POP, q.x, q.y, q.z, 1, 0, 0, 0, 0);
				}
			}
			if (locked != null && ticks >= fireAt) {
				Vec3 from = eye.getEyePosition(), to = locked.getEyePosition();
				boolean blocked = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, eye)).getType() != HitResult.Type.MISS;
				sound(from, SoundEvents.GUARDIAN_ATTACK, 2, 0.6f);
				if (!blocked) {
					locked.hurtServer(level, level.damageSources().indirectMagic(eye, eye), 8);
					level.sendParticles(ParticleTypes.SONIC_BOOM, to.x, to.y, to.z, 1, 0, 0, 0, 0);
				}
				locked = null;
			}
			return;
		}
		if (ticks % 140 == 60) {
			ServerPlayer p = nearest(at, 40);
			if (p != null) {
				locked = p;
				fireAt = ticks + 40;
				sound(at, SoundEvents.ELDER_GUARDIAN_CURSE, 1.5f, 1.2f);
				p.sendOverlayMessage(net.minecraft.network.chat.Component.literal("The Abyssal Eye locks on to you: take cover!").withStyle(net.minecraft.ChatFormatting.AQUA));
			}
		}
		if (!adds && share(eye) <= 0.5f) {
			adds = true;
			say(at, 40, styled("The Abyssal Eye ", 0x4FB3A9, true, false).copy().append(plain("calls from the deep.")));
			for (int i = 0; i < 3; i++) {
				Entity g = spawn("minecraft:guardian", at.x + random.nextGaussian() * 3, at.y, at.z + random.nextGaussian() * 3, ADD, false, false);
				if (g != null) {
					spawned.add(g);
					g.setNoGravity(true);
					if (g instanceof Mob m) m.setTarget(nearest(at, 40));
				}
			}
		}
	}
}
