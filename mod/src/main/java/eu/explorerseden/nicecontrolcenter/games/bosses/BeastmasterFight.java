package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import eu.explorerseden.nicecontrolcenter.games.Aggro;

/**
 * The Beastmaster (NCC): a pillager on his ravager. Mounted, the ravager charges along marked lines and roars (a
 * stunning ring) while he shoots from its back. Both must fall: whichever is left fights on enraged; on foot he sets
 * wolves on the players and throws snares.
 */
final class BeastmasterFight extends BossFight {
	private static final int RGB = 0x6B4A3A;
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Health:100f,CustomName:{text:\"Gorehorn\",color:\"#6B4A3A\",bold:true},"
			+ "attributes:[{id:\"minecraft:max_health\",base:100},{id:\"minecraft:attack_damage\",base:6},{id:\"minecraft:follow_range\",base:40},{id:\"minecraft:explosion_knockback_resistance\",base:1}],"
			+ "Passengers:[{id:\"minecraft:pillager\",DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Health:120f,CustomName:{text:\"The Beastmaster\",color:\"#6B4A3A\",bold:true},"
			+ "equipment:{mainhand:{id:\"minecraft:crossbow\",count:1}},drop_chances:{mainhand:0f},"
			+ "attributes:[{id:\"minecraft:max_health\",base:120},{id:\"minecraft:follow_range\",base:40},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}]}";
	private static final String WOLF = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,CustomName:\"War Hound\",attributes:[{id:\"minecraft:max_health\",base:16},{id:\"minecraft:attack_damage\",base:3}]}";

	private final LivingEntity ravager;
	private LivingEntity rider;
	private final List<Entity> wolves = new ArrayList<>();
	private Vec3 charge;
	private long chargeUntil = -1;
	private final Set<ServerPlayer> trampled = new HashSet<>();
	private boolean riderAlone, mountAlone;

	BeastmasterFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		ravager = (LivingEntity) spawn("minecraft:ravager", x, y, z, BODY, true, false);
		if (ravager != null) for (Entity e : ravager.getPassengers()) if (e instanceof LivingEntity l) rider = l;
		if (ravager instanceof Raider r) r.setCanJoinRaid(false);
		if (rider instanceof Raider r) r.setCanJoinRaid(false);
	}

	@Override
	String name() {
		return "The Beastmaster";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.RED;
	}

	/** The bar follows him while he lives, then the ravager. */
	@Override
	LivingEntity body() {
		return !dead(rider) ? rider : ravager;
	}

	@Override
	boolean alive() {
		return !dead(rider) || !dead(ravager);
	}

	@Override
	void tick() {
		wolves.removeIf(BossFight::dead);
		if (!riderAlone && dead(ravager) && !dead(rider)) {
			riderAlone = true;
			effect(rider, MobEffects.SPEED, -1, 1);
			effect(rider, MobEffects.STRENGTH, -1, 0);
			say(rider.position(), 40, styled("The Beastmaster ", RGB, true, false).copy().append(plain("roars over his fallen beast and fights on foot!")));
			hounds(rider.position(), 2);
		}
		if (!mountAlone && dead(rider) && !dead(ravager)) {
			mountAlone = true;
			effect(ravager, MobEffects.SPEED, -1, 1);
			say(ravager.position(), 40, styled("Gorehorn ", RGB, true, false).copy().append(plain("goes berserk without its master!")));
		}
		if (!dead(ravager)) mounted();
		else if (!dead(rider)) onFoot();
	}

	private void hounds(Vec3 at, int n) {
		ServerPlayer p = nearest(at, 40);
		for (int i = 0; i < n && wolves.size() < 4; i++) {
			Entity w = spawn("minecraft:wolf", at.x + random.nextGaussian() * 2, at.y, at.z + random.nextGaussian() * 2, WOLF, false, false);
			if (w instanceof Mob m) {
				Aggro.make(m, 3);
				wolves.add(w);
				if (p != null) m.setTarget(p);
			}
		}
		sound(at, SoundEvents.PILLAGER_CELEBRATE, 2, 0.7f);
	}

	private void mounted() {
		Vec3 at = ravager.position();
		ServerPlayer p = nearest(at, 40);
		if (ticks % 20 == 0 && p != null && ravager instanceof Mob m && m.getTarget() == null) m.setTarget(p);
		// Charging along the line: 7 and a hard knock for everyone in the way.
		if (ticks <= chargeUntil && charge != null) {
			Vec3 dir = charge.subtract(at).multiply(1, 0, 1);
			if (dir.lengthSqr() > 1) ravager.setDeltaMovement(dir.normalize().scale(0.8).add(0, ravager.getDeltaMovement().y, 0));
			else chargeUntil = ticks;
			for (ServerPlayer q : near(at, 2.5)) {
				if (!trampled.add(q)) continue;
				q.hurtServer(level, level.damageSources().mobAttack(ravager), 7);
				push(q, (dir.lengthSqr() < 0.01 ? ravager.getLookAngle() : dir.normalize()).scale(1.6).add(0, 0.6, 0));
			}
			return;
		}
		if (p == null) return;
		// A charge every 9 s (6 when berserk): a marked line first.
		if (ticks % (mountAlone ? 120 : 180) == 60) {
			Vec3 d = p.position().subtract(at).multiply(1, 0, 1);
			Vec3 end = d.lengthSqr() < 0.01 ? p.position() : at.add(d.normalize().scale(Math.sqrt(d.lengthSqr()) + 5));
			for (int t = 0; t < 20; t += 4) later(t, () -> {
				for (double f = 0; f <= 1; f += 0.05) {
					Vec3 q = at.add(end.subtract(at).scale(f));
					level.sendParticles(ParticleTypes.ANGRY_VILLAGER, q.x, q.y + 0.2, q.z, 1, 0, 0, 0, 0);
				}
			});
			ravager.setDeltaMovement(Vec3.ZERO);
			later(20, () -> {
				if (dead(ravager)) return;
				charge = end;
				chargeUntil = ticks + 30;
				trampled.clear();
				sound(ravager.position(), SoundEvents.RAVAGER_STEP, 3, 0.6f);
			});
		}
		// The roar every 12 s when someone is close: a ring warns, then a stunning blast.
		if (ticks % 240 == 150 && !near(at, 6).isEmpty()) {
			for (int t = 0; t < 20; t += 4) later(t, () -> telegraph(ravager.position(), 5, ParticleTypes.POOF));
			ravager.setDeltaMovement(Vec3.ZERO);
			later(20, () -> {
				if (dead(ravager)) return;
				sound(ravager.position(), SoundEvents.RAVAGER_ROAR, 3, 0.8f);
				for (ServerPlayer q : near(ravager.position(), 5)) {
					effect(q, MobEffects.SLOWNESS, 40, 3);
					effect(q, MobEffects.WEAKNESS, 40, 1);
				}
				shockwave(ravager, ravager.position(), 5, 4, 1.2, 0.4);
			});
		}
	}

	private void onFoot() {
		Vec3 at = rider.position();
		ServerPlayer p = nearest(at, 40);
		if (ticks % 20 == 0 && p != null && rider instanceof Mob m && m.getTarget() == null) m.setTarget(p);
		// More hounds every 15 s (four at most).
		if (ticks % 300 == 0) hounds(at, 2);
		// A snare every 8 s: a cobweb at a player's feet and slowness.
		if (ticks % 160 == 80) {
			List<ServerPlayer> ps = near(at, 24);
			if (!ps.isEmpty()) {
				ServerPlayer t = ps.get(random.nextInt(ps.size()));
				BlockPos b = t.blockPosition();
				tempBlock(b, Blocks.COBWEB.defaultBlockState(), 60);
				effect(t, MobEffects.SLOWNESS, 60, 1);
				sound(t.position(), SoundEvents.TRIPWIRE_CLICK_ON, 2, 0.6f);
			}
		}
	}
}
