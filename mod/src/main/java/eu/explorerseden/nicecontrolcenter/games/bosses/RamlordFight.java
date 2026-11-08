package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The Ramlord (NCC): a giant goat. Long rams along a marked line that throw players across the arena, a thrown horn,
 * rocks falling where dust warned, and a mountain leap that lands in a shockwave.
 */
final class RamlordFight extends BossFight {
	private static final String BODY = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Health:160f,CustomName:{text:\"The Ramlord\",color:\"#D9D2C3\",bold:true},"
			+ "attributes:[{id:\"minecraft:max_health\",base:160},{id:\"minecraft:scale\",base:3},{id:\"minecraft:knockback_resistance\",base:0.9},{id:\"minecraft:step_height\",base:2},"
			+ "{id:\"minecraft:safe_fall_distance\",base:64},{id:\"minecraft:explosion_knockback_resistance\",base:1}]}";
	private static final String ROCK = "{teleport_duration:14,block_state:" + blockState("minecraft:stone", "") + ",transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
			+ "translation:[-0.75f,0f,-0.75f],scale:[1.5f,1.5f,1.5f]}}";

	private final LivingEntity ram;
	private Vec3 charge;
	private long chargeUntil = -1;
	private final Set<ServerPlayer> rammed = new HashSet<>();
	private Vec3 leap;
	private long leapFrom = -1;

	RamlordFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		ram = (LivingEntity) spawn("minecraft:goat", x, y, z, BODY, true, false);
	}

	@Override
	String name() {
		return "The Ramlord";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.WHITE;
	}

	@Override
	LivingEntity body() {
		return ram;
	}

	@Override
	boolean alive() {
		return !dead(ram);
	}

	@Override
	void tick() {
		Vec3 at = ram.position();
		// Ramming along the line.
		if (ticks <= chargeUntil && charge != null) {
			Vec3 dir = charge.subtract(at).multiply(1, 0, 1);
			if (dir.lengthSqr() > 1) ram.setDeltaMovement(dir.normalize().scale(1.0).add(0, ram.getDeltaMovement().y, 0));
			for (ServerPlayer p : near(at, 2.5)) {
				if (!rammed.add(p)) continue;
				p.hurtServer(level, level.damageSources().mobAttack(ram), 6);
				push(p, dir.normalize().scale(2.6).add(0, 0.7, 0));
				sound(p.position(), SoundEvents.GOAT_RAM_IMPACT, 2, 0.6f);
			}
			return;
		}
		// The leap: in the air until it lands, then the shockwave.
		if (leap != null) {
			if (ticks >= leapFrom && (ram.onGround() || ticks > leapFrom + 50)) {
				leap = null;
				sound(ram.position(), SoundEvents.GENERIC_EXPLODE.value(), 1.5f, 0.7f);
				shockwave(ram, ram.position(), 6, 6, 1.4, 0.6);
			}
			return;
		}
		ServerPlayer p = nearest(at, 32);
		if (p == null) return;
		// A long ram every 10 s.
		if (ticks % 200 == 40) {
			Vec3 d = p.position().subtract(at).multiply(1, 0, 1);
			Vec3 end = d.lengthSqr() < 0.01 ? p.position() : at.add(d.normalize().scale(Math.sqrt(d.lengthSqr()) + 6));
			for (int t = 0; t < 20; t += 4) later(t, () -> {
				for (double f = 0; f <= 1; f += 0.05) {
					Vec3 q = at.add(end.subtract(at).scale(f));
					level.sendParticles(ParticleTypes.CLOUD, q.x, q.y + 0.1, q.z, 1, 0, 0, 0, 0);
				}
			});
			sound(at, SoundEvents.GOAT_SCREAMING_PREPARE_RAM, 2, 0.6f);
			later(20, () -> {
				charge = end;
				chargeUntil = ticks + 22;
				rammed.clear();
			});
		}
		// A horn thrown every 7 s.
		if (ticks % 140 == 90) horn(p);
		// Rocks every 9 s at a player further away.
		if (ticks % 180 == 0) {
			for (ServerPlayer q : near(at, 32)) {
				if (q.distanceToSqr(ram) < 8 * 8) continue;
				rock(q.position());
				break;
			}
		}
		// The mountain leap every 14 s.
		if (ticks % 280 == 150) {
			Vec3 spot = Vec3.atBottomCenterOf(groundAt(p.position()));
			leap = spot;
			leapFrom = Long.MAX_VALUE;
			for (int t = 0; t < 20; t += 4) later(t, () -> telegraph(spot, 3, ParticleTypes.CLOUD));
			sound(at, SoundEvents.GOAT_LONG_JUMP, 2, 0.6f);
			later(20, () -> {
				if (dead(ram)) {
					leap = null;
					return;
				}
				Vec3 d = spot.subtract(ram.position());
				ram.setDeltaMovement(d.x / 20, 1.1, d.z / 20);
				leapFrom = ticks + 6;
			});
		}
	}

	/** A horn flies at the player over a second: 6 if it reaches them. */
	private void horn(ServerPlayer p) {
		Vec3 from = ram.position().add(0, 2.5, 0);
		Entity h = spawn("minecraft:item_display", from.x, from.y, from.z, "{teleport_duration:3,item:{id:\"minecraft:goat_horn\",count:1},"
				+ "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[1.5f,1.5f,1.5f]}}", false, true);
		if (h == null) return;
		sound(from, SoundEvents.GOAT_HORN_BREAK, 1.5f, 0.8f);
		for (int i = 1; i <= 7; i++) {
			int step = i;
			later(i * 3, () -> {
				if (h.isRemoved()) return;
				Vec3 to = p.position().add(0, 1, 0);
				Vec3 q = from.add(to.subtract(from).scale(step / 7.0));
				h.teleportTo(q.x, q.y, q.z);
				if (step == 7) {
					h.discard();
					if (p.position().add(0, 1, 0).distanceToSqr(q) < 2.5 * 2.5) p.hurtServer(level, level.damageSources().mobAttack(ram), 6);
				}
			});
		}
	}

	/** Dust marks the spot, then a rock drops there: 7 damage, and it's gone. */
	private void rock(Vec3 target) {
		Vec3 spot = Vec3.atBottomCenterOf(groundAt(target));
		for (int t = 0; t < 30; t += 5) later(t, () -> telegraph(spot, 1.6, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState())));
		Entity d = spawn("minecraft:block_display", spot.x, spot.y + 14, spot.z, ROCK, false, true);
		later(30, () -> {
			if (d != null) d.teleportTo(spot.x, spot.y, spot.z);
		});
		later(44, () -> {
			if (d != null) d.discard();
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()), spot.x, spot.y + 0.5, spot.z, 40, 0.8, 0.3, 0.8, 0.1);
			sound(spot, SoundEvents.STONE_BREAK, 2, 0.6f);
			for (ServerPlayer p : near(spot, 2)) p.hurtServer(level, level.damageSources().mobAttack(ram), 7);
		});
	}
}
