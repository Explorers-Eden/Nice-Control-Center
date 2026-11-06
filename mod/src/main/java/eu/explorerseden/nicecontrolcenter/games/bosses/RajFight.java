package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.Vec3;

/**
 * Raj Raksha (Katters Structures, by Katter): a pillager in diamond armour riding a floating elder guardian. His
 * arrows call lightning where they land, rings of lightning strike around him, gliders near him get struck, and
 * hitting him may bring a minion or darkness.
 */
final class RajFight extends BossFight {
	private static final String NBT = "{PersistenceRequired:1b,Health:160f,DeathLootTable:\"minecraft:empty\",Passengers:[{id:\"minecraft:pillager\",DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,"
			+ "Health:200f,PatrolLeader:0b,Patrolling:0b,CanJoinRaid:0b,CustomName:[{bold:false,color:\"#9EBBFF\",italic:false,text:\"Raj \"},{bold:false,color:\"#FF7070\",italic:false,text:\"Raksha\"}],"
			+ "equipment:{chest:{id:\"minecraft:diamond_chestplate\",count:1,components:{\"minecraft:enchantments\":{\"minecraft:blast_protection\":2,\"minecraft:fire_protection\":2,"
			+ "\"minecraft:projectile_protection\":2,\"minecraft:protection\":2,\"minecraft:vanishing_curse\":1}}},mainhand:{id:\"minecraft:crossbow\",count:1,components:{\"minecraft:enchantments\":"
			+ "{\"minecraft:multishot\":1,\"minecraft:piercing\":4,\"minecraft:infinity\":1},\"minecraft:charged_projectiles\":[{id:\"minecraft:tipped_arrow\",count:1,components:"
			+ "{\"minecraft:potion_contents\":{potion:\"minecraft:weakness\"},\"minecraft:intangible_projectile\":{}}}]}}},drop_chances:{chest:0.000,mainhand:0.000},"
			+ "active_effects:[{id:\"minecraft:slow_falling\",amplifier:0,duration:-1,show_particles:0b,show_icon:0b},{id:\"minecraft:fire_resistance\",amplifier:200,duration:-1,show_particles:0b}],"
			+ "attributes:[{id:\"minecraft:follow_range\",base:48},{id:\"minecraft:gravity\",base:0.02},{id:\"minecraft:max_health\",base:200},{id:\"minecraft:safe_fall_distance\",base:128},"
			+ "{id:\"minecraft:scale\",base:1.5}]}],active_effects:[{id:\"minecraft:slow_falling\",amplifier:0,duration:-1,show_particles:0b,show_icon:0b},{id:\"minecraft:fire_resistance\","
			+ "amplifier:200,duration:-1,show_particles:0b}],attributes:[{id:\"minecraft:follow_range\",base:48},{id:\"minecraft:gravity\",base:0.02},{id:\"minecraft:max_health\",base:160},"
			+ "{id:\"minecraft:safe_fall_distance\",base:128},{id:\"minecraft:scale\",base:1}]}";
	private static final String MINION = "{Silent:1b,Invulnerable:1b,DeathLootTable:\"minecraft:empty\",CanDuplicate:0b,Passengers:[{id:\"minecraft:guardian\",DeathLootTable:\"minecraft:empty\","
			+ "attributes:[{id:\"minecraft:scale\",base:0.3}]}],active_effects:[{id:\"minecraft:invisibility\",amplifier:0,duration:-1,show_particles:0b}],attributes:[{id:\"minecraft:scale\",base:0.5}]}";
	private static final DustColorTransitionOptions DUST_SMALL = new DustColorTransitionOptions(0xFFFFFF, 0x54E8FF, 0.8f);
	private static final DustColorTransitionOptions DUST_BIG = new DustColorTransitionOptions(0xFFFFFF, 0x54E8FF, 1.5f);

	private final LivingEntity vehicle;
	private final LivingEntity rider;
	/** His arrows in flight and where they were last. */
	private final Map<AbstractArrow, Vec3> arrows = new HashMap<>();
	/** Minions: the allays carrying a guardian. */
	private final List<Entity> minions = new ArrayList<>();

	RajFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		vehicle = (LivingEntity) spawn("minecraft:elder_guardian", x, y, z, NBT, true, false);
		rider = vehicle == null ? null : (LivingEntity) vehicle.getFirstPassenger();
	}

	@Override
	String name() {
		return "Raj Raksha";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.BLUE;
	}

	@Override
	LivingEntity body() {
		return dead(rider) ? vehicle : rider;
	}

	@Override
	boolean alive() {
		return !dead(rider) || !dead(vehicle);
	}

	@Override
	void tick() {
		// The guardian goes once its rider is down; minions without him go too.
		if (ticks % 10 == 0) {
			if (dead(rider) && !dead(vehicle)) vehicle.discard();
			for (Entity m : List.copyOf(minions)) {
				if (m.isRemoved() || m.getPassengers().isEmpty() || dead(vehicle)) {
					for (Entity p : m.getPassengers()) p.discard();
					m.discard();
					minions.remove(m);
				}
			}
		}
		if (dead(rider)) return;
		// His armour glows.
		if (ticks % 2 == 0) {
			level.sendParticles(DUST_SMALL, rider.getX(), rider.getY() - 1, rider.getZ(), 1, 1.5, 1.5, 1.5, 1);
			level.sendParticles(DUST_BIG, rider.getX(), rider.getY() - 1, rider.getZ(), 1, 1.5, 1.5, 1.5, 1);
			if (random.nextInt(10) == 0) level.sendParticles(ParticleTypes.END_ROD, rider.getX(), rider.getY() - 1, rider.getZ(), 1, 1.5, 1.5, 1.5, 0.01);
		}
		arrows();
		// Every 10 seconds: half the time a ring of lightning; gliders close by get struck.
		if (ticks % 200 == 0 && !dead(vehicle)) {
			Vec3 at = vehicle.position();
			if (random.nextInt(2) == 0) {
				int dx = 5 + random.nextInt(12), dz = 5 + random.nextInt(12);
				int[][] corners = { { dx, dz }, { -dx, -dz }, { dx, -dz }, { -dx, dz } };
				for (int[] c : corners) for (int i = 0; i < 2; i++) if (random.nextInt(2) == 0) lightning(ground(at.add(c[0], 0, c[1])));
			}
			for (ServerPlayer p : near(at, 24)) if (p.isFallFlying()) lightning(p.position());
		}
	}

	/** His arrows: lightning where one lands (in a block or a target). */
	private void arrows() {
		for (AbstractArrow a : level.getEntitiesOfClass(AbstractArrow.class, around(rider, 64), x -> x.getOwner() == rider && !arrows.containsKey(x))) arrows.put(a, a.position());
		for (Map.Entry<AbstractArrow, Vec3> e : List.copyOf(arrows.entrySet())) {
			AbstractArrow a = e.getKey();
			boolean landed = a.isRemoved() || a.getDeltaMovement().lengthSqr() < 0.0001;
			if (!landed) {
				arrows.put(a, a.position());
				continue;
			}
			Vec3 at = a.isRemoved() ? e.getValue() : a.position();
			lightning(at);
			level.sendParticles(ParticleTypes.TRIAL_SPAWNER_DETECTED_PLAYER_OMINOUS, at.x, at.y, at.z, 25, 0.7, 0.7, 0.7, 0);
			level.sendParticles(ParticleTypes.TRIAL_OMEN, at.x, at.y, at.z, 10, 1, 1, 1, 0.5);
			if (!a.isRemoved()) a.discard();
			arrows.remove(a);
		}
	}

	/** Hit: sometimes a minion next to whoever hit him, sometimes darkness for them. */
	@Override
	void hurt(LivingEntity piece, DamageSource source) {
		if (piece != rider || !(source.getEntity() instanceof ServerPlayer p)) return;
		if (random.nextInt(100) < 15) {
			int[][] spots = { { 0, 3 }, { 0, -3 }, { 3, 0 }, { -3, 0 } };
			int[] s = spots[random.nextInt(4)];
			Entity m = spawn("minecraft:allay", p.getX() + s[0], p.getY() + 2, p.getZ() + s[1], MINION, false, false);
			if (m != null) {
				m.addTag(ASSET);
				minions.add(m);
			}
		}
		if (random.nextInt(100) < 15) effect(p, MobEffects.DARKNESS, 400, 0);
	}

	/** The highest solid spot under (or at) the point. */
	private Vec3 ground(Vec3 at) {
		net.minecraft.core.BlockPos b = net.minecraft.core.BlockPos.containing(at);
		for (int i = 0; i < 24 && b.getY() > level.getMinY() && level.getBlockState(b.below()).isAir(); i++) b = b.below();
		return Vec3.atBottomCenterOf(b);
	}
}
