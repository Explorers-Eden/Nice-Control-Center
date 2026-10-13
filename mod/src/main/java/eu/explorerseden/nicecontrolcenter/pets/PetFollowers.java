package eu.explorerseden.nicecontrolcenter.pets;

import com.mojang.math.Transformation;
import com.mojang.serialization.JsonOps;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.Pets;
import eu.explorerseden.nicecontrolcenter.mixin.DisplayAccessor;
import eu.explorerseden.nicecontrolcenter.mixin.ItemDisplayAccessor;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The pets that are out: a player head on an item display that the server moves next to its owner every
 * tick (clients smooth the movement). Display entities can't be hit, pushed or collided with, so a pet is
 * only for looks, and every player nearby sees it. The displays are tagged and never outlive their owner's
 * session (also not a crash: leftovers are removed when their chunk loads).
 */
final class PetFollowers {
	static final String TAG = "nicecontrolcenter_pet";
	/** Further than this from where it should be (a teleport), the pet jumps instead of flying there. */
	private static final double JUMP = 12;
	/** A head item on a display faces the other way than an entity with the same yaw. */
	private static final float FACE = 180;

	/** Ticks the client takes to glide to a new position, and how far ahead of the player (in ticks of their movement) the pet is put to make up for it. */
	private static final int GLIDE = 2;
	private static final double LEAD = 2;

	private static final class Follower {
		Display.ItemDisplay display;
		String texture;
		double size;
		/** Where it is (for Lazy) and where it sits around the player (everything else). */
		Vec3 pos;
		Vec3 offset;
		Vec3 lastPlayer;
		/** So pets of players standing together don't move in step. */
		final int phase;

		Follower(int phase) {
			this.phase = phase;
		}
	}

	private static final Map<UUID, Follower> followers = new HashMap<>();
	/** True while a display of ours is being added (it isn't in followers yet). */
	private static boolean spawning;

	private PetFollowers() {
	}

	static void tick(MinecraftServer server) {
		boolean on = PetStore.enabled();
		Iterator<Map.Entry<UUID, Follower>> it = followers.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Follower> e = it.next();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (!on || p == null) {
				if (e.getValue().display != null) e.getValue().display.discard();
				it.remove();
			}
		}
		if (!on) return;
		long time = server.getTickCount();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Pets.Owner owner = PetStore.data().owners.get(player.getStringUUID());
			Pets.Owned owned = shown(player) ? PetStore.active(player) : null;
			Follower f = followers.get(player.getUUID());
			if (owned == null || owner == null) {
				if (f != null) {
					if (f.display != null) f.display.discard();
					followers.remove(player.getUUID());
				}
				continue;
			}
			String texture = PetStore.texture(owned);
			double size = PetStore.settings().size;
			Pets.Behavior behavior = Pets.Behavior.of(owner.behavior);
			if (f == null) {
				f = new Follower(player.getRandom().nextInt(1000));
				followers.put(player.getUUID(), f);
			}
			long t = time + f.phase;
			Vec3 base = player.position();
			// The player's movement this tick (none after a teleport).
			Vec3 moved = f.lastPlayer == null ? Vec3.ZERO : base.subtract(f.lastPlayer);
			if (moved.lengthSqr() > 9) moved = Vec3.ZERO;
			f.lastPlayer = base;
			Target goal = target(player, behavior, t, f);
			Vec3 want = base.add(goal.offset);
			boolean fresh = f.display == null || f.display.isRemoved() || f.display.level() != player.level() || !texture.equals(f.texture) || size != f.size
					|| f.pos == null || f.pos.distanceToSqr(want) > JUMP * JUMP;
			if (fresh) {
				if (f.display != null) f.display.discard();
				f.display = spawn(player.level(), want, goal.yaw + FACE, texture, size);
				f.texture = texture;
				f.size = size;
				f.pos = want;
				f.offset = goal.offset;
				if (f.display == null) followers.remove(player.getUUID());
				continue;
			}
			if (goal.stay) {
				// Lazy: a spot in the world, not around the player.
				f.pos = f.pos.add(want.subtract(f.pos).scale(goal.follow));
				f.offset = f.pos.subtract(base);
			} else {
				// Moves with the player one to one; only its spot around them is eased. A little ahead, because
				// clients show it a moment late.
				f.offset = f.offset.add(goal.offset.subtract(f.offset).scale(goal.follow));
				f.pos = base.add(f.offset).add(moved.scale(LEAD));
			}
			f.display.setPos(f.pos);
			f.display.setYRot(goal.yaw + FACE);
			trail(player, owner, f, t, size);
		}
	}

	/** Pets hide while their owner can't be seen. */
	private static boolean shown(ServerPlayer player) {
		return player.isAlive() && !player.isSpectator() && !player.isInvisible();
	}

	/**
	 * Where the pet wants to be (from the player's feet), which way it looks, how quickly it gets there
	 * (share of the way per tick) and whether it stays where it is in the world (Lazy).
	 */
	private record Target(Vec3 offset, float yaw, double follow, boolean stay) {
		Target(Vec3 offset, float yaw, double follow) {
			this(offset, yaw, follow, false);
		}
	}

	/** The yaw of moving from a to b. */
	private static float heading(Vec3 a, Vec3 b) {
		return (float) Math.toDegrees(Math.atan2(-(b.x - a.x), b.z - a.z));
	}

	private static Target target(ServerPlayer player, Pets.Behavior behavior, long t, Follower f) {
		double h = player.getBbHeight();
		float look = player.getYRot();
		double yaw = Math.toRadians(look);
		// Where the player faces, and their right hand side.
		double fx = -Math.sin(yaw), fz = Math.cos(yaw);
		double rx = -fz, rz = fx;
		// right, up and forward from the player's view, as an offset in the world.
		record Local(double fx, double fz, double rx, double rz) {
			Vec3 at(double right, double up, double forward) {
				return new Vec3(rx * right + fx * forward, up, rz * right + fz * forward);
			}
		}
		Local l = new Local(fx, fz, rx, rz);
		return switch (behavior) {
			case FLOAT -> new Target(l.at(0.75, h * 0.85 + 0.12 * Math.sin(t * 0.08), -0.25), look, 0.35);
			case SHOULDER -> new Target(l.at(0.5, h * 0.83 + 0.2, -0.05), look, 0.7);
			case FOLLOW -> new Target(l.at(0, h * 0.7 + 0.1 * Math.sin(t * 0.06), -1.4), look, 0.15);
			case PEEK -> {
				double side = Math.max(-1, Math.min(1, Math.sin(t * 0.035) * 2.5));
				yield new Target(l.at(side * 0.5, h * 0.95 + 0.05 * Math.sin(t * 0.1), -0.5), look, 0.3);
			}
			case BOUNCE -> new Target(l.at(0.85, h * 0.4 + 0.7 * Math.abs(Math.sin(t * 0.14)), 0), look, 0.5);
			case ORBIT -> path(t, 0.6, s -> new Vec3(Math.cos(s * 0.1) * 1.1, h + 0.3 + 0.1 * Math.sin(s * 0.2), Math.sin(s * 0.1) * 1.1));
			case HALO -> path(t, 0.7, s -> new Vec3(Math.cos(s * 0.22) * 0.45, h + 0.55, Math.sin(s * 0.22) * 0.45));
			case SPIRAL -> path(t, 0.6, s -> new Vec3(Math.cos(s * 0.12), h * 0.15 + (0.5 + 0.5 * Math.sin(s * 0.035)) * h, Math.sin(s * 0.12)));
			case FIGURE_EIGHT -> path(t, 0.6, s -> l.at(Math.sin(s * 0.07) * 1.3, h * 0.75 + 0.1 * Math.sin(s * 0.14), Math.sin(s * 0.14) * 0.6));
			case WANDER -> path(t, 0.25, s -> new Vec3(Math.sin(s * 0.031) * 1.1 + Math.sin(s * 0.017 + 1) * 0.5, h * 0.8 + Math.sin(s * 0.05) * 0.25,
					Math.cos(s * 0.023) * 1.1 + Math.cos(s * 0.041 + 2) * 0.4));
			case SPIN -> new Target(new Vec3(0, h + 0.55 + 0.08 * Math.sin(t * 0.1), 0), (float) ((t * 9) % 360), 0.7);
			case LAZY -> {
				Vec3 base = player.position();
				Vec3 at = f.pos == null ? base.add(l.at(0.9, h * 0.7, 0)) : f.pos;
				Vec3 away = new Vec3(at.x - base.x, 0, at.z - base.z);
				double far = away.length();
				Vec3 spot = far > 2.6 || far < 0.6 || Math.abs(at.y - base.y - h * 0.7) > 1.5
						// Catches up to a spot a bit away from the player, on its side.
						? base.add(far < 0.6 ? l.at(0.9, h * 0.7, 0) : away.scale(1.3 / far).add(0, h * 0.7, 0))
						: at;
				yield new Target(spot.subtract(base).add(0, 0.04 * Math.sin(t * 0.08), 0), heading(at, base), far > 2.6 ? 0.12 : 0.05, true);
			}
		};
	}

	/** A pet on a path around the player, looking the way it flies. */
	private static Target path(long t, double follow, java.util.function.LongFunction<Vec3> at) {
		Vec3 now = at.apply(t);
		return new Target(now, heading(now, at.apply(t + 1)), follow);
	}

	/** The pet's particle trail, if its owner picked one (and the panel allows them). */
	private static void trail(ServerPlayer player, Pets.Owner owner, Follower f, long t, double size) {
		if (!PetStore.settings().particles) return;
		Pets.Trail trail = Pets.Trail.of(owner.trail);
		if (trail == Pets.Trail.OFF || t % trail.every != 0) return;
		ServerLevel level = player.level();
		double x = f.pos.x, y = f.pos.y - 0.15 * size, z = f.pos.z;
		switch (trail) {
			// For notes the speed is the colour.
			case NOTES -> level.sendParticles(ParticleTypes.NOTE, x, y + 0.3 * size, z, 0, player.getRandom().nextDouble(), 0, 0, 1);
			case HEARTS -> level.sendParticles(ParticleTypes.HEART, x, y + 0.3 * size, z, 1, 0.1, 0.05, 0.1, 0);
			default -> {
				// A small wisp rising off the head: count 0 makes the three numbers the particle's direction.
				var r = player.getRandom();
				double jx = (r.nextDouble() - 0.5) * 0.25 * size, jz = (r.nextDouble() - 0.5) * 0.25 * size;
				double rise = trail == Pets.Trail.MAGIC ? 0.5 : trail == Pets.Trail.SPARKLES ? 0.035 : 0.02;
				level.sendParticles(particle(trail), x + jx, y + 0.1 * size, z + jz, 0, (r.nextDouble() - 0.5) * 0.2, 1, (r.nextDouble() - 0.5) * 0.2, rise);
			}
		}
	}

	private static SimpleParticleType particle(Pets.Trail trail) {
		return switch (trail) {
			case SPARKLES -> ParticleTypes.END_ROD;
			case FLAMES -> ParticleTypes.SMALL_FLAME;
			case SOUL_FLAMES -> ParticleTypes.SOUL_FIRE_FLAME;
			case SNOW -> ParticleTypes.SNOWFLAKE;
			case GLOW -> ParticleTypes.GLOW;
			case PETALS -> ParticleTypes.CHERRY_LEAVES;
			case MAGIC -> ParticleTypes.ENCHANT;
			case SPARKS -> ParticleTypes.ELECTRIC_SPARK;
			case FIREFLIES -> ParticleTypes.FIREFLY;
			case BUBBLES -> ParticleTypes.BUBBLE_POP;
			case HEARTS -> ParticleTypes.HEART;
			case NOTES -> ParticleTypes.NOTE;
			case OFF -> ParticleTypes.END_ROD;
		};
	}

	private static Display.ItemDisplay spawn(ServerLevel level, Vec3 pos, float yaw, String texture, double size) {
		ItemStack head = head(level.getServer(), texture);
		if (head.isEmpty()) return null;
		@SuppressWarnings("unchecked")
		EntityType<Display.ItemDisplay> type = (EntityType<Display.ItemDisplay>) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("item_display"));
		Display.ItemDisplay d = new Display.ItemDisplay(type, level);
		d.setPos(pos);
		d.setYRot(yaw);
		d.addTag(TAG);
		((ItemDisplayAccessor) d).nicecontrolcenter$setItemStack(head);
		float s = (float) size;
		// A head item is drawn below the display's spot; lift it so the spot is its middle.
		((DisplayAccessor) d).nicecontrolcenter$setTransformation(new Transformation(new Vector3f(0, 0.25f * s, 0), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
		((DisplayAccessor) d).nicecontrolcenter$setMoveDuration(GLIDE);
		((DisplayAccessor) d).nicecontrolcenter$setShadowRadius(0.2f * s);
		((DisplayAccessor) d).nicecontrolcenter$setShadowStrength(0.6f);
		spawning = true;
		try {
			return level.addFreshEntity(d) ? d : null;
		} finally {
			spawning = false;
		}
	}

	/** A player head with the skin (EMPTY when the value can't be used). */
	static ItemStack head(MinecraftServer server, String texture) {
		com.google.gson.JsonObject property = new com.google.gson.JsonObject();
		property.addProperty("name", "textures");
		property.addProperty("value", texture);
		com.google.gson.JsonArray properties = new com.google.gson.JsonArray();
		properties.add(property);
		com.google.gson.JsonObject profile = new com.google.gson.JsonObject();
		profile.add("properties", properties);
		com.google.gson.JsonObject components = new com.google.gson.JsonObject();
		components.add("minecraft:profile", profile);
		com.google.gson.JsonObject json = new com.google.gson.JsonObject();
		json.addProperty("id", "minecraft:player_head");
		json.add("components", components);
		try {
			return ItemStack.CODEC.parse(server.registryAccess().createSerializationContext(JsonOps.INSTANCE), json).getOrThrow();
		} catch (RuntimeException e) {
			NiceControlCenter.LOGGER.warn("A pet's head can't be made: {}", e.toString());
			return ItemStack.EMPTY;
		}
	}

	static void remove(UUID player) {
		Follower f = followers.remove(player);
		if (f != null && f.display != null) f.display.discard();
	}

	static void removeAll() {
		for (Follower f : followers.values()) if (f.display != null) f.display.discard();
		followers.clear();
	}

	/** A pet display of ours from a session that's over: gone. */
	static boolean leftOver(Entity e) {
		return !spawning && e instanceof Display.ItemDisplay && e.entityTags().contains(TAG) && followers.values().stream().noneMatch(f -> f.display == e);
	}
}
