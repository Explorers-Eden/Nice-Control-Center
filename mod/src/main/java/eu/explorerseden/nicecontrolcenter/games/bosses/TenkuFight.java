package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Tenkū Mizu Gami (Katters Structures, by Katter): a giant turtle circled by eight dolphins (one leaves for every
 * 50 health it loses). The dolphins shoot beams that leave clouds (nausea and a blast, hunger, poison, slowness,
 * wither), tridents and potions fall on players further away, helpers come for those close by, and now and then it
 * throws the nearest player back. Out of water it hovers towards the players (in water it swims).
 */
final class TenkuFight extends BossFight {
	private static final String NBT = "{DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,Health:500f,Age:2,CustomName:{bold:false,color:\"#93FF87\",italic:false,text:\"Tenkū Mizu Gami\"},"
			+ "attributes:[{id:\"minecraft:knockback_resistance\",base:100},{id:\"minecraft:max_health\",base:500},{id:\"minecraft:safe_fall_distance\",base:1024},{id:\"minecraft:scale\",base:4},"
			+ "{id:\"minecraft:explosion_knockback_resistance\",base:100}]}";
	private static final String DOLPHIN = "{DeathLootTable:\"minecraft:empty\",NoGravity:1b,Silent:1b,Invulnerable:1b,NoAI:1b,CanFindTreasure:0b,GotFish:0b,attributes:[{id:\"minecraft:scale\",base:1.5}]}";
	private static final String HELPER = "{DeathLootTable:\"minecraft:empty\",Passengers:[{id:\"minecraft:drowned\",DeathLootTable:\"minecraft:empty\",attributes:[{id:\"minecraft:max_health\",base:5},"
			+ "{id:\"minecraft:scale\",base:0.5}]}],active_effects:[{id:\"minecraft:wind_charged\",amplifier:2,duration:-1,show_particles:0b}],attributes:[{id:\"minecraft:scale\",base:0.6}]}";
	/** Where the dolphins circle (sideways, forwards from the seat, which turns). */
	private static final double[][] RING = { { 7, 0 }, { -7, 0 }, { 0, 7 }, { 0, -7 }, { 4.5, -4.5 }, { 4.5, 4.5 }, { -4.5, -4.5 }, { -4.5, 4.5 } };
	/** The beams: dust colour and what the cloud does. */
	private static final int[] BEAM_COLORS = { 0xFF0000, 0x6C803E, 0xCCFA14, 0x505050, 0x0B538A };

	private final LivingEntity turtle;
	private final Entity seat;
	private final Entity[] dolphins = new Entity[8];
	private float spin;
	private long pushReady;
	/** The beams' blasts to come: the TNT (looks like a conduit) goes off without breaking blocks. */
	private final List<PrimedTnt> blasts = new ArrayList<>();

	TenkuFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		turtle = (LivingEntity) spawn("minecraft:turtle", x, y, z, NBT, true, false);
		seat = turtle == null ? null : spawn("minecraft:armor_stand", x, y, z, "{NoGravity:1b,Invulnerable:1b,Marker:1b,Invisible:1b}", false, true);
		if (seat != null) seat.startRiding(turtle, true, false);
		for (int i = 0; i < 8 && turtle != null; i++) dolphins[i] = spawn("minecraft:dolphin", x, y, z, DOLPHIN, false, true);
	}

	@Override
	String name() {
		return "Tenkū Mizu Gami";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.GREEN;
	}

	@Override
	LivingEntity body() {
		return turtle;
	}

	@Override
	boolean alive() {
		return !dead(turtle);
	}

	@Override
	void tick() {
		defend(turtle, 10);
		Vec3 at = turtle.position();
		ring();
		hover();
		blasts();
		float health = turtle.getHealth();
		// One dolphin leaves per 50 health lost; then the nearest player is thrown back (at most once a minute).
		for (int i = 0; i < 8; i++) {
			if (dolphins[i] != null && !dolphins[i].isRemoved() && health <= 400 - 50 * i) {
				dolphins[i].discard();
				if (ticks >= pushReady) {
					ServerPlayer p = nearest(at, 18);
					if (p != null) {
						Vec3 away = p.position().subtract(at).multiply(1, 0, 1).normalize().scale(2.5);
						push(p, new Vec3(away.x, 0.5, away.z));
						pushReady = ticks + 1200;
					}
				}
			}
		}
		if (ticks % 20 == 0) for (ServerPlayer p : near(at, 48)) effect(p, MobEffects.MINING_FATIGUE, 100, 4);
		if (random.nextInt(10) == 0) {
			// A trident or a potion over players 12–40 blocks away.
			for (ServerPlayer p : near(at, 40)) {
				if (p.distanceToSqr(turtle) < 12 * 12 || random.nextInt(100) >= 15) continue;
				dropFromAbove(p.position(), random.nextInt(2) == 0 ? "{id:\"minecraft:trident\",count:1}" : splash("minecraft:strong_turtle_master"), 60);
			}
		}
		if (random.nextInt(10) == 0) {
			// Helpers for those close by.
			for (ServerPlayer p : near(at, 12)) if (random.nextInt(100) < 5) spawn("minecraft:guardian", p.getX(), p.getY(), p.getZ(), HELPER, false, false);
		}
		if (random.nextInt(10) == 0) {
			// Beams at those further away.
			for (ServerPlayer p : near(at, 128)) {
				if (p.distanceToSqr(turtle) < 13 * 13 || random.nextInt(100) >= 15) continue;
				Entity d = dolphins[random.nextInt(8)];
				if (d != null && !d.isRemoved()) beam(d, p, random.nextInt(5));
			}
		}
		// Helpers without their rider (or far from Tenkū) go.
		if (ticks % 20 == 0) {
			for (Entity e : parts) {
				if (e.isRemoved() || !net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().equals("guardian")) continue;
				if (e.getPassengers().isEmpty() || e.distanceToSqr(turtle) > 64 * 64) {
					for (Entity r : e.getPassengers()) r.discard();
					e.discard();
				}
			}
		}
	}

	/** The seat turns; the dolphins circle with it, looking at the nearest player. */
	private void ring() {
		if (seat == null) return;
		spin = (spin + 1) % 360;
		double rad = Math.toRadians(spin);
		double cos = Math.cos(rad), sin = Math.sin(rad);
		Vec3 c = seat.position();
		ServerPlayer look = nearest(c, 48);
		for (int i = 0; i < 8; i++) {
			Entity d = dolphins[i];
			if (d == null || d.isRemoved()) continue;
			double lx = RING[i][0], lz = RING[i][1];
			Vec3 to = new Vec3(c.x + lx * cos - lz * sin, c.y - 2, c.z + lx * sin + lz * cos);
			float yaw = d.getYRot(), pitch = d.getXRot();
			if (look != null) {
				Vec3 dir = look.getEyePosition().subtract(to);
				yaw = (float) (Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90);
				pitch = (float) -Math.toDegrees(Math.atan2(dir.y, Math.sqrt(dir.x * dir.x + dir.z * dir.z)));
			}
			d.snapTo(to.x, to.y, to.z, yaw, pitch);
			d.setYHeadRot(yaw);
			if (ticks % 2 == 0) level.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD, to.x, to.y + 0.5, to.z, 2, 0.1, 0, 0.1, 0);
		}
	}

	/** Out of water: it floats towards the nearest player, about three blocks above the ground. */
	private void hover() {
		if (turtle.isInWater()) {
			if (turtle.isNoGravity()) turtle.setNoGravity(false);
			return;
		}
		if (!turtle.isNoGravity()) turtle.setNoGravity(true);
		if (ticks % 5 != 0) return;
		ServerPlayer p = nearest(turtle.position(), 48);
		Vec3 target = p == null ? turtle.position() : p.position();
		double floor = target.y;
		BlockPos below = turtle.blockPosition();
		for (int i = 0; i < 16 && level.getBlockState(below.below()).isAir(); i++) below = below.below();
		double ground = below.getY();
		double wantY = Math.max(ground, floor) + 3;
		Vec3 dir = new Vec3(target.x - turtle.getX(), 0, target.z - turtle.getZ());
		Vec3 move = dir.lengthSqr() > 9 ? dir.normalize().scale(0.12) : Vec3.ZERO;
		turtle.setDeltaMovement(move.x, Math.max(-0.1, Math.min(0.1, (wantY - turtle.getY()) * 0.1)), move.z);
		if (dir.lengthSqr() > 0.01) turtle.setYRot((float) (Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90));
	}

	/** A beam from the dolphin towards the player: dust along the way, a cloud where it ends. */
	private void beam(Entity from, ServerPlayer to, int kind) {
		Vec3 start = from.position();
		Vec3 dir = to.position().subtract(start).normalize();
		DustParticleOptions dust = new DustParticleOptions(BEAM_COLORS[kind], 1f);
		Vec3 at = start;
		for (double d = 0; d <= 48; d += 0.25) {
			at = start.add(dir.scale(d));
			BlockPos b = BlockPos.containing(at);
			if (!level.getBlockState(b).getCollisionShape(level, b).isEmpty()) break;
			if (to.position().distanceToSqr(at) <= 0.25 || to.getBoundingBox().contains(at)) break;
			if ((int) (d * 4) % 2 == 0) level.sendParticles(dust, at.x, at.y, at.z, 1, 0.1, 0.1, 0.1, 0);
		}
		String color = String.valueOf(BEAM_COLORS[kind]);
		String particle = "Particle:{type:\"dust\",color:" + rgb(BEAM_COLORS[kind]) + ",scale:2},Radius:2f,Duration:100,";
		switch (kind) {
			case 0 -> {
				spawn("minecraft:area_effect_cloud", at.x, at.y, at.z, "{" + particle + "potion_contents:{custom_color:" + color + ",custom_effects:[{id:\"minecraft:nausea\",amplifier:1,duration:100}]}}", false, true);
				Entity t = spawn("minecraft:tnt", at.x, at.y + 2, at.z, "{fuse:100,block_state:{Name:\"minecraft:conduit\"}}", false, true);
				if (t instanceof PrimedTnt tnt) blasts.add(tnt);
			}
			case 1 -> spawn("minecraft:area_effect_cloud", at.x, at.y, at.z, "{" + particle + "potion_contents:{custom_color:" + color + ",custom_effects:[{id:\"minecraft:hunger\",amplifier:1,duration:400}]}}", false, true);
			case 2 -> spawn("minecraft:area_effect_cloud", at.x, at.y, at.z, "{" + particle + "potion_contents:{custom_color:" + color + ",custom_effects:[{id:\"minecraft:poison\",amplifier:1,duration:100}]}}", false, true);
			case 3 -> spawn("minecraft:area_effect_cloud", at.x, at.y, at.z, "{" + particle + "potion_contents:{potion:\"minecraft:slowness\",custom_color:" + color + "}}", false, true);
			default -> spawn("minecraft:area_effect_cloud", at.x, at.y, at.z, "{" + particle + "potion_contents:{custom_color:" + color + ",custom_effects:[{id:\"minecraft:wither\",amplifier:1,duration:200}]}}", false, true);
		}
	}

	private static String rgb(int c) {
		return "[" + ((c >> 16 & 255) / 255f) + "f," + ((c >> 8 & 255) / 255f) + "f," + ((c & 255) / 255f) + "f]";
	}

	/** The blasts go off just before the TNT would, without breaking blocks. */
	private void blasts() {
		for (PrimedTnt t : List.copyOf(blasts)) {
			if (t.isRemoved()) {
				blasts.remove(t);
				continue;
			}
			if (t.getFuse() > 2) continue;
			Vec3 at = t.position();
			t.discard();
			blasts.remove(t);
			level.explode(null, at.x, at.y, at.z, 3f, Level.ExplosionInteraction.NONE);
		}
	}

	/** A helper that hurts a player slows them down (or darkens their view). */
	@Override
	void hit(ServerPlayer victim, Entity attacker) {
		if (attacker == turtle) return;
		int k = random.nextInt(6);
		if (k < 5) effect(victim, MobEffects.SLOWNESS, 400, k);
		else effect(victim, MobEffects.DARKNESS, 100, 1);
	}
}
