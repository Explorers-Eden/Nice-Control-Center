package eu.explorerseden.nicecontrolcenter.pets;

import com.mojang.math.Transformation;
import com.mojang.serialization.JsonOps;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.Pets;
import eu.explorerseden.nicecontrolcenter.mixin.DisplayAccessor;
import eu.explorerseden.nicecontrolcenter.mixin.ItemDisplayAccessor;
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

	private static final class Follower {
		Display.ItemDisplay display;
		String texture;
		double size;
		Vec3 pos;
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
				e.getValue().display.discard();
				it.remove();
			}
		}
		if (!on) return;
		long time = server.getTickCount();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Pets.Owned owned = shown(player) ? PetStore.active(player) : null;
			Follower f = followers.get(player.getUUID());
			if (owned == null) {
				if (f != null) {
					f.display.discard();
					followers.remove(player.getUUID());
				}
				continue;
			}
			String texture = PetStore.texture(owned);
			double size = PetStore.settings().size;
			Pets.Behavior behavior = Pets.Behavior.of(owned.behavior);
			if (f == null) {
				f = new Follower(player.getRandom().nextInt(1000));
				followers.put(player.getUUID(), f);
			}
			Target t = target(player, behavior, time + f.phase);
			boolean fresh = f.display == null || f.display.isRemoved() || f.display.level() != player.level() || !texture.equals(f.texture) || size != f.size
					|| f.pos.distanceToSqr(t.pos) > JUMP * JUMP;
			if (fresh) {
				if (f.display != null) f.display.discard();
				f.display = spawn(player.level(), t.pos, t.yaw, texture, size);
				f.texture = texture;
				f.size = size;
				f.pos = t.pos;
				if (f.display == null) {
					followers.remove(player.getUUID());
				}
				continue;
			}
			f.pos = f.pos.add(t.pos.subtract(f.pos).scale(t.follow));
			f.display.setPos(f.pos);
			f.display.setYRot(t.yaw);
		}
	}

	/** Pets hide while their owner can't be seen. */
	private static boolean shown(ServerPlayer player) {
		return player.isAlive() && !player.isSpectator() && !player.isInvisible();
	}

	/** Where the pet wants to be, which way it looks, and how quickly it gets there (share of the way per tick). */
	private record Target(Vec3 pos, float yaw, double follow) {
	}

	private static Target target(ServerPlayer player, Pets.Behavior behavior, long t) {
		Vec3 base = player.position();
		double h = player.getBbHeight();
		float look = player.getYRot();
		double yaw = Math.toRadians(look);
		// Where the player faces, and their right hand side.
		double fx = -Math.sin(yaw), fz = Math.cos(yaw);
		double rx = -fz, rz = fx;
		return switch (behavior) {
			case FLOAT -> new Target(base.add(rx * 0.75 - fx * 0.25, h * 0.85 + 0.12 * Math.sin(t * 0.08), rz * 0.75 - fz * 0.25), look, 0.35);
			case BOUNCE -> new Target(base.add(rx * 0.85, h * 0.4 + 0.7 * Math.abs(Math.sin(t * 0.14)), rz * 0.85), look, 0.5);
			case FOLLOW -> {
				Vec3 at = base.add(-fx * 1.4, h * 0.7 + 0.1 * Math.sin(t * 0.06), -fz * 1.4);
				yield new Target(at, look, 0.12);
			}
			case ORBIT -> {
				double a = t * 0.1;
				// Looks the way it flies (the direction of travel is a's tangent, which is yaw a).
				yield new Target(base.add(Math.cos(a) * 1.1, h + 0.3 + 0.1 * Math.sin(a * 2), Math.sin(a) * 1.1), (float) Math.toDegrees(a % (Math.PI * 2)), 0.6);
			}
			case SPIN -> new Target(base.add(0, h + 0.55 + 0.08 * Math.sin(t * 0.1), 0), (float) ((t * 9) % 360), 0.6);
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
		((DisplayAccessor) d).nicecontrolcenter$setMoveDuration(2);
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
