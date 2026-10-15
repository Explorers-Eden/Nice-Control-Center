package eu.explorerseden.nicecontrolcenter.pets;

import com.mojang.math.Transformation;
import com.mojang.serialization.JsonOps;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.Pets;
import eu.explorerseden.nicecontrolcenter.mixin.DisplayAccessor;
import eu.explorerseden.nicecontrolcenter.mixin.ItemDisplayAccessor;
import net.minecraft.core.particles.ParticleTypes;
import io.netty.buffer.Unpooled;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.core.particles.ParticleOptions;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

	/** Ticks the clients take to glide to a new spot or turn (riding pets), and to a new place (Lazy). */
	private static final int GLIDE = 2;
	/** How fast the moves play: the animation clock runs at this share of game time. */
	private static final double PACE = 0.6;

	private static final class Follower {
		Display.ItemDisplay display;
		String texture;
		double size;
		/** Where the pet is, and where it sits around the player (from their feet). */
		Vec3 pos;
		Vec3 offset;
		Vec3 lastPlayer;
		/** What Random landed on, rolled again after every teleport, death or taking the pet out. */
		Pets.Behavior rolledMove;
		Pets.Trail rolledTrail;
		net.minecraft.world.level.Level level;
		/** Clients were told it rides the player, and when that was last sent; when it was spawned. */
		boolean riding;
		long ridingSent;
		long spawned;
		/** So pets of players standing together don't move in step. */
		final int phase;
		/** After a head that couldn't be made, the next try waits until then (game time). */
		long retryAt;

		Follower(int phase) {
			this.phase = phase;
		}
	}

	private static final Map<UUID, Follower> followers = new HashMap<>();
	/** True while a display of ours is being added (it isn't in followers yet). */
	private static boolean spawning;

	private PetFollowers() {
	}

	/*
	 * How a pet follows: clients are told that the head rides its owner (the server itself can't make anything
	 * ride a player), so every client keeps it on the player each frame, smooth and never behind. Its spot
	 * around the player and the way it looks are its transformation, which clients glide between. Lazy stays
	 * put in the world, so that one doesn't ride and moves on its own.
	 */
	static void tick(MinecraftServer server) {
		boolean on = PetStore.enabled();
		Iterator<Map.Entry<UUID, Follower>> it = followers.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Follower> e = it.next();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (!on || p == null) {
				discard(e.getValue(), p);
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
					discard(f, player);
					followers.remove(player.getUUID());
				}
				continue;
			}
			String texture = PetStore.texture(owned);
			double size = PetStore.size(owned);
			if (f == null) {
				f = new Follower(player.getRandom().nextInt(1000));
				followers.put(player.getUUID(), f);
				roll(f, player);
			}
			long t = time + f.phase;
			Vec3 base = player.position();
			double h = player.getBbHeight();
			// Where clients put a passenger of the player: on top of them.
			Vec3 seat = base.add(0, h, 0);
			// A teleport (or another dimension): Random picks again.
			Vec3 moved = f.lastPlayer == null ? Vec3.ZERO : base.subtract(f.lastPlayer);
			if (f.lastPlayer != null && (moved.lengthSqr() > 9 || f.level != player.level())) roll(f, player);
			f.lastPlayer = base;
			f.level = player.level();
			Pets.Behavior chosen = Pets.Behavior.of(owner.behavior);
			Pets.Behavior behavior = chosen == Pets.Behavior.RANDOM ? f.rolledMove : chosen;
			Target goal = target(player, behavior, t * PACE, f);
			Vec3 want = base.add(goal.offset);
			boolean fresh = f.display == null || f.display.isRemoved() || f.display.level() != player.level() || !texture.equals(f.texture) || size != f.size
					|| f.pos == null || f.pos.distanceToSqr(want) > JUMP * JUMP;
			if (fresh) {
				if (time < f.retryAt) continue;
				discard(f, player);
				f.offset = goal.offset;
				f.pos = want;
				f.display = spawn(player.level(), goal.stay ? want : seat, texture, size);
				f.texture = texture;
				f.size = size;
				f.spawned = time;
				// Tried again in a minute (it would warn every tick otherwise).
				if (f.display == null) {
					f.retryAt = time + 1200;
					continue;
				}
				pose(f, goal.stay ? Vec3.ZERO : goal.offset.subtract(0, h, 0), goal.yaw, size, 0);
				continue;
			}
			if (goal.stay) {
				// Lazy: a spot in the world, not around the player.
				if (f.riding) ride(f, player, false, time);
				f.pos = f.pos.add(want.subtract(f.pos).scale(goal.follow));
				f.offset = f.pos.subtract(base);
				f.display.setPos(f.pos);
				pose(f, Vec3.ZERO, goal.yaw, size, GLIDE);
			} else {
				f.offset = f.offset.add(goal.offset.subtract(f.offset).scale(goal.follow));
				f.pos = base.add(f.offset);
				// Told again now and then, for players who only just came near (and more often right after spawning).
				if (!f.riding || time - f.ridingSent >= (time - f.spawned < 40 ? 5 : 20)) ride(f, player, true, time);
				// The entity itself stays on the seat, for the server's tracking and for clients not told yet.
				if (f.display.position().distanceToSqr(seat) > 0.09) f.display.setPos(seat);
				pose(f, f.offset.subtract(0, h, 0), goal.yaw, size, GLIDE);
			}
			Pets.Trail trail = Pets.Trail.of(owner.trail);
			trail(player, trail == Pets.Trail.RANDOM ? f.rolledTrail : trail, f, t, size);
		}
	}

	/** The head's spot (from where the entity is) and the way it looks, which clients glide to in ticks. */
	private static void pose(Follower f, Vec3 at, float yaw, double size, int ticks) {
		float s = (float) size;
		// A head item is drawn below the display's spot; lift it so the spot is its middle.
		Vector3f move = new Vector3f((float) at.x, (float) at.y + 0.25f * s, (float) at.z);
		// The same turn a display with this yaw gets (the entity's own yaw stays 0).
		Quaternionf turn = new Quaternionf().rotationY((float) Math.toRadians(-(yaw + FACE)));
		DisplayAccessor d = (DisplayAccessor) f.display;
		d.nicecontrolcenter$setTransformation(new Transformation(move, turn, new Vector3f(s, s, s), new Quaternionf()));
		d.nicecontrolcenter$setInterpolationDuration(ticks);
		d.nicecontrolcenter$setInterpolationDelay(0);
	}

	/** Tells the owner and everyone who sees them that the head rides the owner (or no longer does). */
	private static void ride(Follower f, ServerPlayer player, boolean on, long time) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeVarInt(player.getId());
		// Players never have real passengers, so this list is all of them.
		buf.writeVarIntArray(on ? new int[] {f.display.getId()} : new int[0]);
		ClientboundSetPassengersPacket packet = ClientboundSetPassengersPacket.STREAM_CODEC.decode(buf);
		buf.release();
		player.level().getChunkSource().sendToTrackingPlayersAndSelf(player, packet);
		f.riding = on;
		f.ridingSent = time;
	}

	/** Removes the head (and tells clients it no longer rides the player). */
	private static void discard(Follower f, ServerPlayer player) {
		if (f.display == null) return;
		if (f.riding && player != null) ride(f, player, false, 0);
		f.riding = false;
		f.display.discard();
		f.display = null;
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

	private static Target target(ServerPlayer player, Pets.Behavior behavior, double t, Follower f) {
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
			// Random is rolled into a real move before this; Float just in case.
			case FLOAT, RANDOM -> new Target(l.at(0.75, h * 0.85 + 0.12 * Math.sin(t * 0.08), -0.25), look, 0.35);
			case SHOULDER -> new Target(l.at(0.5, h * 0.83 + 0.2, -0.05), look, 0.7);
			case FOLLOW -> new Target(l.at(0, h * 0.7 + 0.1 * Math.sin(t * 0.06), -1.4), look, 0.15);
			case PEEK -> {
				double side = Math.max(-1, Math.min(1, Math.sin(t * 0.035) * 2.5));
				yield new Target(l.at(side * 0.5, h * 0.95 + 0.05 * Math.sin(t * 0.1), -0.5), look, 0.3);
			}
			case BOUNCE -> new Target(l.at(0.85, h * 0.4 + 0.7 * Math.abs(Math.sin(t * 0.14)), 0), look, 0.5);
			case ORBIT -> path(t, 0.6, s -> new Vec3(Math.cos(s * 0.1) * 1.1, h + 0.3 + 0.1 * Math.sin(s * 0.2), Math.sin(s * 0.1) * 1.1));
			case HALO -> path(t, 0.7, s -> new Vec3(Math.cos(s * 0.08) * 0.45, h + 0.55, Math.sin(s * 0.08) * 0.45));
			case SPIRAL -> path(t, 0.6, s -> new Vec3(Math.cos(s * 0.12), h * 0.15 + (0.5 + 0.5 * Math.sin(s * 0.035)) * h, Math.sin(s * 0.12)));
			case FIGURE_EIGHT -> path(t, 0.6, s -> l.at(Math.sin(s * 0.07) * 1.3, h * 0.75 + 0.1 * Math.sin(s * 0.14), Math.sin(s * 0.14) * 0.6));
			case WANDER -> path(t, 0.25, s -> new Vec3(Math.sin(s * 0.031) * 1.1 + Math.sin(s * 0.017 + 1) * 0.5, h * 0.8 + Math.sin(s * 0.05) * 0.25,
					Math.cos(s * 0.023) * 1.1 + Math.cos(s * 0.041 + 2) * 0.4));
			case SPIN -> new Target(new Vec3(0, h + 0.55 + 0.08 * Math.sin(t * 0.1), 0), (float) ((t * 9) % 360), 0.7);
			case WOBBLE -> new Target(l.at(0.75, h * 0.85 + 0.06 * Math.sin(t * 0.11), -0.2), look + (float) (35 * Math.sin(t * 0.15)), 0.35);
			// Looks back at the player (the other way than they look).
			case GUIDE -> new Target(l.at(0.35, h * 0.8 + 0.1 * Math.sin(t * 0.07), 1.4), look + 180, 0.2);
			case SLEEPY -> new Target(l.at(0.6, 0.3 + 0.04 * Math.sin(t * 0.03), -0.3), look + (float) (8 * Math.sin(t * 0.02)), 0.15);
			case PENDULUM -> {
				// Hangs from a point above and behind the head.
				double swing = Math.sin(t * 0.08) * 1.0;
				yield new Target(l.at(Math.sin(swing) * 0.7, h + 1.0 - Math.cos(swing) * 0.7, -0.45), look + (float) Math.toDegrees(swing) * 0.5f, 0.5);
			}
			case ZIGZAG -> {
				// A triangle wave: straight runs with sharp turns.
				double side = Math.asin(Math.sin(t * 0.06)) * 2 / Math.PI;
				yield new Target(l.at(side * 0.9, h * 0.75, -0.9), look, 0.45);
			}
			case SATELLITE -> path(t, 0.6, s -> l.at(Math.cos(s * 0.09) * 1.1, h * 0.6 + Math.sin(s * 0.09) * 0.85, Math.sin(s * 0.09) * 0.35));
			case DART -> {
				// A new random spot around the player every 2.5 seconds, reached quickly.
				long hop = (long) (t / 50);
				double a = frac(Math.sin(hop * 12.9898) * 43758.5453) * Math.PI * 2;
				double up = frac(Math.sin(hop * 78.233) * 12543.123);
				Vec3 spot = new Vec3(Math.cos(a) * 1.2, h * (0.45 + 0.6 * up), Math.sin(a) * 1.2);
				// Looks at the player.
				yield new Target(spot, (float) Math.toDegrees(Math.atan2(Math.cos(a), -Math.sin(a))), 0.3);
			}
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

	/** The part after the decimal point (0 to 1). */
	private static double frac(double x) {
		return x - Math.floor(x);
	}

	/** A pet on a path around the player, looking the way it flies. */
	private static Target path(double t, double follow, java.util.function.DoubleFunction<Vec3> at) {
		Vec3 now = at.apply(t);
		return new Target(now, heading(now, at.apply(t + 1)), follow);
	}

	/** New picks for Random (only real moves and trails this server has). */
	private static void roll(Follower f, ServerPlayer player) {
		var r = player.getRandom();
		List<Pets.Behavior> moves = new ArrayList<>(List.of(Pets.Behavior.values()));
		moves.remove(Pets.Behavior.RANDOM);
		moves.remove(f.rolledMove);
		f.rolledMove = moves.get(r.nextInt(moves.size()));
		List<Pets.Trail> trails = new ArrayList<>();
		for (Pets.Trail t : Pets.Trail.values()) if (t.real() && available(t) && t != f.rolledTrail) trails.add(t);
		f.rolledTrail = trails.isEmpty() ? Pets.Trail.OFF : trails.get(r.nextInt(trails.size()));
	}

	/** Whether this game version has the trail's particle (Noxious Gas only came in 26.2). */
	static boolean available(Pets.Trail trail) {
		return !trail.real() || particle(trail) != null;
	}

	/** The pet's particle trail, if its owner picked one (and the panel allows them). */
	private static void trail(ServerPlayer player, Pets.Trail trail, Follower f, long t, double size) {
		if (!PetStore.settings().particles || trail == null || !trail.real() || t % trail.every != 0) return;
		ParticleOptions type = particle(trail);
		if (type == null) return;
		ServerLevel level = player.level();
		// Somewhere around the head, not inside it, and with no push of their own: they float where they appear.
		double spread = 0.2 + 0.25 * size;
		if (trail == Pets.Trail.NOTES) {
			// A note's colour comes in the first of the three numbers, so it's placed by hand.
			var r = player.getRandom();
			level.sendParticles(ParticleTypes.NOTE, f.pos.x + (r.nextDouble() - 0.5) * 2 * spread, f.pos.y + r.nextDouble() * spread, f.pos.z + (r.nextDouble() - 0.5) * 2 * spread,
					0, r.nextDouble(), 0, 0, 1);
			return;
		}
		var r = player.getRandom();
		if (trail == Pets.Trail.AUTUMN_LEAVES || trail == Pets.Trail.LUSH_LEAVES) {
			int[] colours = trail == Pets.Trail.AUTUMN_LEAVES ? AUTUMN : LUSH;
			type = ColorParticleOption.create(ParticleTypes.TINTED_LEAVES, 0xFF000000 | colours[r.nextInt(colours.length)]);
		}
		level.sendParticles(type, f.pos.x, f.pos.y, f.pos.z, 1, spread, spread, spread, 0);
	}

	/** Red, orange and yellow leaves; and lush greens. */
	private static final int[] AUTUMN = {0xC0392B, 0xD35400, 0xE67E22, 0xF39C12, 0xF1C40F, 0xA93226};
	private static final int[] LUSH = {0x4CAF50, 0x66BB33, 0x7CCF3A, 0x3E9E3E, 0x8BD448};

	/** The game's particle for a trail, or null when this version doesn't have it. */
	private static ParticleOptions particle(Pets.Trail trail) {
		return switch (trail) {
			case SPARKLES -> ParticleTypes.END_ROD;
			case FLAMES -> ParticleTypes.SMALL_FLAME;
			case SOUL_FLAMES -> ParticleTypes.SOUL_FIRE_FLAME;
			case SNOW -> ParticleTypes.SNOWFLAKE;
			case GLOW -> ParticleTypes.GLOW;
			case PETALS -> ParticleTypes.CHERRY_LEAVES;
			case MAGIC -> ParticleTypes.ENCHANT;
			case FIREFLIES -> ParticleTypes.FIREFLY;
			case FIREWORKS -> ParticleTypes.FIREWORK;
			case TOTEM -> ParticleTypes.TOTEM_OF_UNDYING;
			case SOULS -> ParticleTypes.SCULK_SOUL;
			case PORTAL -> ParticleTypes.REVERSE_PORTAL;
			case NAUTILUS -> ParticleTypes.NAUTILUS;
			case SPORES -> ParticleTypes.SPORE_BLOSSOM_AIR;
			case ASH -> ParticleTypes.WHITE_ASH;
			case PALE_LEAVES -> ParticleTypes.PALE_OAK_LEAVES;
			case HAPPY -> ParticleTypes.HAPPY_VILLAGER;
			case HEARTS -> ParticleTypes.HEART;
			case NOTES -> ParticleTypes.NOTE;
			case WITCH -> ParticleTypes.WITCH;
			case OMINOUS -> ParticleTypes.OMINOUS_SPAWNING;
			case RAID_OMEN -> ParticleTypes.RAID_OMEN;
			case CAMPFIRE_SMOKE -> ParticleTypes.CAMPFIRE_COSY_SMOKE;
			// Coloured when sent; a placeholder colour here.
			case AUTUMN_LEAVES, LUSH_LEAVES -> ColorParticleOption.create(ParticleTypes.TINTED_LEAVES, 0xFFFFFFFF);
			// By name: 26.1 doesn't have it.
			case NOXIOUS_GAS -> BuiltInRegistries.PARTICLE_TYPE.getValue(Identifier.withDefaultNamespace("noxious_gas")) instanceof SimpleParticleType p ? p : null;
			case OFF, RANDOM -> null;
		};
	}

	private static Display.ItemDisplay spawn(ServerLevel level, Vec3 pos, String texture, double size) {
		ItemStack head = head(level.getServer(), texture);
		if (head.isEmpty()) return null;
		@SuppressWarnings("unchecked")
		EntityType<Display.ItemDisplay> type = (EntityType<Display.ItemDisplay>) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("item_display"));
		Display.ItemDisplay d = new Display.ItemDisplay(type, level);
		d.setPos(pos);
		d.addTag(TAG);
		((ItemDisplayAccessor) d).nicecontrolcenter$setItemStack(head);
		float s = (float) size;
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
		if (f != null) discard(f, null);
	}

	static void removeAll() {
		for (Follower f : followers.values()) discard(f, null);
		followers.clear();
	}

	/** A pet display of ours from a session that's over: gone. */
	static boolean leftOver(Entity e) {
		return !spawning && e instanceof Display.ItemDisplay && e.entityTags().contains(TAG) && followers.values().stream().noneMatch(f -> f.display == e);
	}
}
