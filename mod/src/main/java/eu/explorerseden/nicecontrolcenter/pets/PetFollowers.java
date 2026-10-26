package eu.explorerseden.nicecontrolcenter.pets;

import com.mojang.math.Transformation;
import com.mojang.serialization.JsonOps;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.Pets;
import eu.explorerseden.nicecontrolcenter.mixin.DisplayAccessor;
import eu.explorerseden.nicecontrolcenter.mixin.ItemDisplayAccessor;
import eu.explorerseden.nicecontrolcenter.mixin.TextDisplayAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.ColorParticleOption;
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
 * tick (clients smooth the movement), with a text display above it for its name and speech bubbles. Display
 * entities can't be hit, pushed or collided with, and every player nearby sees them. The displays are tagged
 * and never outlive their owner's session (also not a crash: leftovers are removed when their chunk loads).
 * Perks send a pet on short outings (fly somewhere, do something, come back); reactions play short moves.
 */
final class PetFollowers {
	static final String TAG = "nicecontrolcenter_pet";
	/** Further than this from where it should be (a teleport), the pet jumps instead of flying there. */
	private static final double JUMP = 12;
	/** A head item on a display faces the other way than an entity with the same yaw. */
	private static final float FACE = 180;

	/**
	 * Clients are sent a keyframe (spot and turn of the pet and its name) every KEY ticks and glide to it over GLIDE
	 * ticks. A glide is a bit longer than the gap, so it's always running at an even pace: ticks and packets never
	 * arrive perfectly evenly (and not at all on time while the server lags), and gliding to a new spot every single
	 * tick turned that unevenness into jitter, even with the pet barely moving.
	 */
	private static final int KEY = 3;
	private static final int GLIDE = 4;
	/** How fast the moves play: the animation clock runs at this share of game time. */
	private static final double PACE = 0.6;
	/** Ticks of the owner's movement the pet and its name run ahead (they arrive about this far behind where they're sent). */
	private static final double LEAD = (KEY + GLIDE) / 2.0;
	/** Ticks a reaction move fades in and out over. */
	private static final double FADE = 5;
	/** Share of the way the shown turn moves to the wanted one each tick (any jump in where it looks becomes a quick turn). */
	private static final double TURN_EASE = 0.45;
	/** Share of the way the evened-out movement moves to this tick's each tick. */
	private static final double PACE_EASE = 0.3;

	/** Short moves pets make when they react to something. */
	enum Move {
		HOP, BOUNCE, SPIN, DROOP, SHIVER, WIGGLE, LOOK_UP, CURL, FLINCH, LOOK_AT,
		/** Bobs on the beat and sways (music; kept going while a jukebox plays nearby). */
		DANCE,
		/** Hops and spins (big moments). */
		CHEER,
		/** Circles its owner, hopping and spinning (boss beaten, birthday). */
		PARTY,
		/** A somersault (flying, falling). */
		FLIP,
		/** Comes up to its owner's face (pat, owner back). */
		NUZZLE,
		/** Shakes like a wet dog, slowing down (rain over). */
		SHAKE_OFF,
		/** Looks slowly left and right (dark, deep down). */
		LOOK_AROUND,
		/** Nods (good deal, harvest). */
		NOD,
		/** Shakes its head (a warning). */
		HEAD_SHAKE
	}

	/** A trip away from the owner and back (perks). */
	private static final class Outing {
		final java.util.function.Supplier<Vec3> target;
		final double reach;
		final int hold;
		final Runnable arrive;
		final java.util.function.Consumer<Vec3> each;
		final java.util.function.Consumer<Boolean> done;
		final long until;
		boolean arrived;
		boolean back;
		long holdUntil;

		Outing(java.util.function.Supplier<Vec3> target, double reach, int hold, Runnable arrive, java.util.function.Consumer<Vec3> each,
				java.util.function.Consumer<Boolean> done, long until) {
			this.target = target;
			this.reach = reach;
			this.hold = hold;
			this.arrive = arrive;
			this.each = each;
			this.done = done;
			this.until = until;
		}
	}

	private static final class Follower {
		Display.ItemDisplay display;
		/** Name and speech bubble above the head (only while there is something to show), and its text. */
		Display.TextDisplay label;
		Component labelText;
		/** The owner's movement per tick, evened out (it reaches the server in uneven steps), for the name's lead. */
		Vec3 pace = Vec3.ZERO;
		/** Where the last keyframe put the pet (its name sits above that). */
		Vec3 shown;
		/** The turn it shows, eased toward the wanted one. */
		float yawShown = Float.NaN;
		float pitchShown;
		/** The pet shown (after Random pet's pick). */
		Pets.Owned owned;
		Outing outing;
		Move move;
		long moveStart;
		long moveUntil;
		Vec3 lookAt;
		String bubble;
		long bubbleUntil;
		/** Put away: it says bye and goes at this game time (0: staying). */
		long leaveAt;
		boolean greeted;
		String texture;
		double size;
		/** Where the pet is, and where it sits around the player (from their feet). */
		Vec3 pos;
		Vec3 offset;
		Vec3 lastPlayer;
		/** What Random landed on, rolled again after every teleport, death or taking the pet out. */
		Pets.Behavior rolledMove;
		Pets.Trail rolledTrail;
		/** The owned pet shown while the owner has Random pet on. */
		String rolledPet;
		net.minecraft.world.level.Level level;
		/** When it was spawned. */
		long spawned;
		/** So pets of players standing together don't move in step. */
		final int phase;
		/** After a head that couldn't be made, the next try waits until then (game time). */
		long retryAt;
		/** Which way it looks while roaming (turned a little each tick, never snapped). */
		float roamYaw = Float.NaN;

		Follower(int phase) {
			this.phase = phase;
		}
	}

	private static final Map<UUID, Follower> followers = new HashMap<>();
	/** Extra pets out at once (a reward, Pets.Owner.alsoOut): up to two more per player, without reactions or outings. */
	private static final Map<UUID, List<Follower>> extras = new HashMap<>();
	/** True while a display of ours is being added (it isn't in followers yet). */
	private static boolean spawning;

	private PetFollowers() {
	}

	/*
	 * How a pet follows: the server places the head (and its name) each tick, a little ahead by the owner's
	 * movement, and clients glide to each spot. The way it looks is its transformation, which clients turn
	 * between. Reaction moves add to its spot and turn. Lazy stays put in the world instead of around the owner.
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
			boolean leaving = false;
			if (owned == null || owner == null) {
				if (f == null) continue;
				// Put away (not hidden or gone): it says bye before it goes.
				if (f.leaveAt == 0 && owner != null && shown(player) && f.display != null && !f.display.isRemoved() && f.owned != null
						&& PetReactions.bye(player)) {
					f.leaveAt = time + 30;
					endOuting(f, false);
				}
				if (f.leaveAt == 0 || time >= f.leaveAt || f.owned == null || owner == null) {
					discard(f, player);
					followers.remove(player.getUUID());
					continue;
				}
				owned = f.owned;
				leaving = true;
			} else if (f != null) {
				f.leaveAt = 0;
			}
			if (f == null) {
				f = new Follower(player.getRandom().nextInt(1000));
				followers.put(player.getUUID(), f);
				roll(f, player);
			}
			long t = time + f.phase;
			Vec3 base = player.position();
			double h = player.getBbHeight();
			// A teleport (or another dimension): Random picks again.
			Vec3 moved = f.lastPlayer == null ? Vec3.ZERO : base.subtract(f.lastPlayer);
			if (f.lastPlayer != null && (moved.lengthSqr() > 9 || f.level != player.level())) {
				endOuting(f, false);
				roll(f, player);
			}
			f.lastPlayer = base;
			f.level = player.level();
			if (owner.random && !leaving) {
				// Picked again if the rolled one was taken away (or Random was just switched on).
				if (f.rolledPet == null || owner.owned(f.rolledPet) == null) rollPet(f, owner, player);
				Pets.Owned pick = f.rolledPet == null ? null : owner.owned(f.rolledPet);
				if (pick != null) owned = pick;
			}
			f.owned = owned;
			String texture = PetStore.texture(owned);
			double size = PetStore.size(owned);
			Pets.Behavior chosen = Pets.Behavior.of(owner.behavior);
			Pets.Behavior behavior = chosen == Pets.Behavior.RANDOM ? f.rolledMove : chosen;
			// While the owner is AFK the pet roams around them on its own; its move comes back with them.
			boolean afk = eu.explorerseden.nicecontrolcenter.core.PlayerActivity.isAfk(player);
			if (!afk) f.roamYaw = Float.NaN;
			Target goal = afk ? roam(player, t * PACE, f) : target(player, behavior, t * PACE, f);
			if (!goal.stay) goal = new Target(clear(goal.offset, h, size, player.getYRot()), goal.yaw, goal.follow, false);
			if (f.outing != null && time > f.outing.until) endOuting(f, false);
			float pitch = 0;
			if (f.outing != null && f.pos != null) {
				goal = outing(f, f.outing, base, goal, player, time);
			} else if (f.move != null && time < f.moveUntil) {
				double a = time - f.moveStart;
				// How far through the move (0 to 1).
				double p = Math.min(1, a / Math.max(1, f.moveUntil - f.moveStart));
				Vec3 off = goal.offset;
				float yaw = goal.yaw;
				Vec3 off0 = off;
				float yaw0 = yaw;
				double ownerYaw = Math.toRadians(player.getYRot());
				switch (f.move) {
					case HOP -> off = off.add(0, 0.45 * Math.abs(Math.sin(a * 0.3)), 0);
					case BOUNCE -> off = off.add(0, 0.3 * Math.abs(Math.sin(a * 0.3)), 0);
					// Whole turns, eased, so it ends facing where it started.
					case SPIN -> yaw += (float) (720 * ease(p));
					case DROOP -> {
						off = off.add(0, -0.3, 0);
						pitch = 30;
					}
					// Slow enough for keyframes every few ticks (faster just looked like random jumps).
					case SHIVER -> off = off.add(0.05 * Math.sin(a * 0.9), 0, 0.05 * Math.cos(a * 1.1));
					case WIGGLE -> yaw += (float) (30 * Math.sin(a * 0.7));
					case LOOK_UP -> pitch = -40;
					case CURL -> {
						off = new Vec3(off.x * 0.8, 0.35, off.z * 0.8);
						pitch = 20;
					}
					case FLINCH -> off = off.add(0, 0.5 * Math.max(0, 1 - a / 10), 0);
					case LOOK_AT -> {
						if (f.lookAt != null) yaw = heading(base.add(off), f.lookAt);
					}
					case DANCE -> {
						// A beat every 10 ticks; a sway (to the owner's sides) every 20.
						double beat = Math.abs(Math.sin(a * Math.PI / 10));
						double sway = Math.sin(a * Math.PI / 20);
						off = off.add(-Math.cos(ownerYaw) * 0.15 * sway, 0.15 * beat, -Math.sin(ownerYaw) * 0.15 * sway);
						yaw += (float) (25 * sway);
						pitch = (float) (15 * beat);
					}
					case CHEER -> {
						off = off.add(0, 0.5 * Math.abs(Math.sin(a * 0.21)) * (1 - 0.4 * p), 0);
						yaw += (float) (720 * ease(p));
					}
					case PARTY -> {
						// Once around the owner (and a bit more), hopping, spinning on the way.
						double turn = 2.4 * Math.PI * ease(p);
						double cos = Math.cos(turn), sin = Math.sin(turn);
						off = new Vec3(off.x * cos - off.z * sin, off.y + 0.3 * Math.abs(Math.sin(a * 0.4)), off.x * sin + off.z * cos);
						yaw += (float) (1080 * ease(p));
					}
					case FLIP -> {
						off = off.add(0, 0.6 * Math.sin(Math.PI * p), 0);
						pitch = (float) (-360 * ease(p));
					}
					case NUZZLE -> {
						// Up to the owner's face and back, looking at them.
						Vec3 face = new Vec3(0, player.getEyeHeight(), 0);
						Vec3 side = new Vec3(off.x, 0, off.z);
						if (side.lengthSqr() < 1e-4) side = new Vec3(-Math.sin(ownerYaw), 0, Math.cos(ownerYaw));
						Vec3 from = face.add(side.normalize().scale(0.45));
						off = off.add(from.subtract(off).scale(Math.sin(Math.PI * p)));
						yaw = heading(base.add(off), base.add(face));
						pitch = (float) (10 * Math.sin(a * 0.8));
					}
					case SHAKE_OFF -> {
						yaw += (float) (35 * Math.sin(a * 0.9) * (1 - p));
						off = off.add(0, 0.08 * Math.abs(Math.sin(a * 0.5)) * (1 - p), 0);
					}
					case LOOK_AROUND -> {
						yaw += (float) (70 * Math.sin(a * 0.1));
						pitch = -10;
					}
					case NOD -> pitch = (float) (20 * Math.sin(a * 0.6) * (1 - p * 0.5));
					case HEAD_SHAKE -> yaw += (float) (30 * Math.sin(a * 0.9) * (1 - p));
				}
				sparkle(player, f, a);
				// Fades in over the first ticks and out over the last, so starting or ending a move never jumps. Whole
				// turns (spin, cheer, party, flip) start and end at their usual angle by themselves.
				double env = ease(Math.min(1, a / FADE)) * ease(Math.min(1, (f.moveUntil - time) / FADE));
				off = off0.add(off.subtract(off0).scale(env));
				boolean turns = f.move == Move.SPIN || f.move == Move.CHEER || f.move == Move.PARTY || f.move == Move.FLIP;
				if (!turns) {
					yaw = yaw0 + (float) (net.minecraft.util.Mth.wrapDegrees(yaw - yaw0) * env);
					pitch = (float) (pitch * env);
				}
				goal = new Target(off, yaw, Math.max(goal.follow, 0.6), goal.stay);
			}
			Vec3 want = base.add(goal.offset);
			// Far away means a teleport; an outing may take the pet further (teleports end outings anyway).
			boolean fresh = f.display == null || f.display.isRemoved() || f.display.level() != player.level() || !texture.equals(f.texture) || size != f.size
					|| f.pos == null || f.outing == null && f.pos.distanceToSqr(want) > JUMP * JUMP;
			if (fresh) {
				if (time < f.retryAt) continue;
				discard(f, player);
				f.offset = goal.offset;
				f.pos = want;
				f.display = spawn(player.level(), want, texture, size);
				f.texture = texture;
				f.size = size;
				f.spawned = time;
				// Tried again in a minute (it would warn every tick otherwise).
				if (f.display == null) {
					f.retryAt = time + 1200;
					continue;
				}
				pose(f, goal.yaw, pitch, size, 0);
				f.shown = want;
				f.yawShown = goal.yaw;
				f.pitchShown = pitch;
				if (!f.greeted) {
					f.greeted = true;
					PetReactions.hello(player);
				}
				continue;
			}
			if (goal.stay) {
				// Lazy: a spot in the world, not around the player.
				f.pos = f.pos.add(want.subtract(f.pos).scale(goal.follow));
				f.offset = f.pos.subtract(base);
			} else {
				// Gliding there may cut through the player; it goes around them instead.
				f.offset = clear(f.offset.add(goal.offset.subtract(f.offset).scale(goal.follow)), h, size, player.getYRot());
				f.pos = base.add(f.offset);
			}
			// Clients glide the pet and its name behind where they're sent, so both lead by the owner's movement to keep up.
			f.pace = moved.lengthSqr() > 9 ? Vec3.ZERO : f.pace.add(moved.subtract(f.pace).scale(PACE_EASE));
			Vec3 lead = goal.stay ? Vec3.ZERO : f.pace.scale(LEAD);
			Vec3 shown = f.pos.add(lead);
			// A keyframe every KEY ticks (each pet on its own ticks); in between, clients carry on gliding.
			boolean key = (time + f.phase) % KEY == 0;
			// The turn is eased every tick (the shortest way round), so switching what it does never snaps its head.
			if (Float.isNaN(f.yawShown)) f.yawShown = goal.yaw;
			f.yawShown += (float) (net.minecraft.util.Mth.wrapDegrees(goal.yaw - f.yawShown) * TURN_EASE);
			f.pitchShown += (float) (net.minecraft.util.Mth.wrapDegrees(pitch - f.pitchShown) * TURN_EASE);
			if (key) {
				if (f.display.position().distanceToSqr(shown) > 1e-6) f.display.setPos(shown);
				pose(f, f.yawShown, f.pitchShown, size, GLIDE);
				f.shown = shown;
			}
			label(f, player, owner, size, time, f.shown == null ? shown : f.shown);
			Pets.Trail trail = Pets.Trail.of(owner.trail);
			trail(player, trail == Pets.Trail.RANDOM ? f.rolledTrail : trail, f, t, size);
		}
		tickExtras(server, time);
	}

	/** Behaviours that move along a path around the player: extra pets take the same path, spread out along it. */
	private static final java.util.Set<Pets.Behavior> PATHS = java.util.EnumSet.of(Pets.Behavior.ORBIT, Pets.Behavior.HALO, Pets.Behavior.SPIRAL,
			Pets.Behavior.FIGURE_EIGHT, Pets.Behavior.WANDER, Pets.Behavior.SATELLITE, Pets.Behavior.DART, Pets.Behavior.SPIN);

	/**
	 * Extra pets: the owner's move, each in its own spot (spread along a path, or on the other side / behind for a
	 * fixed spot), then pushed apart so no two heads touch, and kept out of the player.
	 */
	private static void tickExtras(MinecraftServer server, long time) {
		Iterator<Map.Entry<UUID, List<Follower>>> it = extras.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, List<Follower>> e = it.next();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			Pets.Owner owner = p == null ? null : PetStore.data().owners.get(p.getStringUUID());
			if (p == null || !PetStore.enabled() || owner == null || owner.alsoOut.isEmpty() || !shown(p) || shownOwned(p.getUUID()) == null) {
				for (Follower f : e.getValue()) discard(f, p);
				it.remove();
			}
		}
		if (!PetStore.enabled()) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Pets.Owner owner = PetStore.data().owners.get(player.getStringUUID());
			Follower main = followers.get(player.getUUID());
			if (owner == null || owner.alsoOut.isEmpty() || main == null || shownOwned(player.getUUID()) == null || main.pos == null) continue;
			// Which pets: the ones they still own, not the main one, as many as their slots allow.
			List<Pets.Owned> pets = new java.util.ArrayList<>();
			for (String id : owner.alsoOut) {
				Pets.Owned o = owner.owned(id);
				if (o != null && main.owned != null && !o.pet.equals(main.owned.pet) && pets.size() < Math.min(2, owner.slots)) pets.add(o);
			}
			List<Follower> list = extras.computeIfAbsent(player.getUUID(), k -> new java.util.ArrayList<>());
			while (list.size() > pets.size()) discard(list.remove(list.size() - 1), player);
			while (list.size() < pets.size()) list.add(new Follower(player.getRandom().nextInt(1000)));
			if (pets.isEmpty()) continue;
			Vec3 base = player.position();
			double h = player.getBbHeight();
			Pets.Behavior chosen = Pets.Behavior.of(owner.behavior);
			Pets.Behavior behavior = chosen == Pets.Behavior.RANDOM ? main.rolledMove : chosen;
			if (behavior == null) behavior = Pets.Behavior.FLOAT;
			boolean afk = eu.explorerseden.nicecontrolcenter.core.PlayerActivity.isAfk(player);
			int n = pets.size() + 1;
			Vec3[] goals = new Vec3[pets.size()];
			float[] yaws = new float[pets.size()];
			double[] sizes = new double[pets.size()];
			for (int i = 0; i < pets.size(); i++) {
				Follower f = list.get(i);
				f.owned = pets.get(i);
				sizes[i] = PetStore.size(f.owned);
				long t = time + main.phase;
				Target goal = afk ? roam(player, (t + 400L * (i + 1)) * PACE, f) : target(player, behavior, t * PACE, f);
				Vec3 off = goal.stay ? goal.offset : slot(goal.offset, behavior, i + 1, n, player.getYRot());
				goals[i] = off;
				yaws[i] = goal.yaw;
			}
			separate(main.offset == null ? main.pos.subtract(base) : main.offset, main.size, goals, sizes, h, player.getYRot());
			for (int i = 0; i < pets.size(); i++) {
				Follower f = list.get(i);
				String texture = PetStore.texture(f.owned);
				double size = sizes[i];
				Vec3 want = base.add(goals[i]);
				boolean fresh = f.display == null || f.display.isRemoved() || f.display.level() != player.level() || !texture.equals(f.texture) || size != f.size
						|| f.pos == null || f.pos.distanceToSqr(want) > JUMP * JUMP;
				if (fresh) {
					if (time < f.retryAt) continue;
					discard(f, player);
					f.offset = goals[i];
					f.pos = want;
					f.display = spawn(player.level(), want, texture, size);
					f.texture = texture;
					f.size = size;
					f.spawned = time;
					if (f.display == null) {
						f.retryAt = time + 1200;
						continue;
					}
					pose(f, yaws[i], 0, size, 0);
					f.shown = want;
					f.yawShown = yaws[i];
					continue;
				}
				f.offset = clear(f.offset.add(goals[i].subtract(f.offset).scale(0.3)), h, size, player.getYRot());
				f.pos = base.add(f.offset);
				Vec3 lead = main.pace.scale(LEAD);
				Vec3 shownAt = f.pos.add(lead);
				if (Float.isNaN(f.yawShown)) f.yawShown = yaws[i];
				f.yawShown += (float) (net.minecraft.util.Mth.wrapDegrees(yaws[i] - f.yawShown) * TURN_EASE);
				if ((time + f.phase) % KEY == 0) {
					if (f.display.position().distanceToSqr(shownAt) > 1e-6) f.display.setPos(shownAt);
					pose(f, f.yawShown, 0, size, GLIDE);
					f.shown = shownAt;
				}
				label(f, player, owner, size, time, f.shown == null ? shownAt : f.shown);
				Pets.Trail trail = Pets.Trail.of(owner.trail);
				trail(player, trail == Pets.Trail.RANDOM ? main.rolledTrail : trail, f, time + f.phase, size);
			}
		}
	}

	/** An extra pet's spot: along a path a share of a turn further, else mirrored to the other side (slot 1) or behind and higher (slot 2). */
	private static Vec3 slot(Vec3 off, Pets.Behavior behavior, int slot, int n, float look) {
		if (PATHS.contains(behavior)) {
			double a = 2 * Math.PI * slot / n;
			double cos = Math.cos(a), sin = Math.sin(a);
			return new Vec3(off.x * cos - off.z * sin, off.y, off.x * sin + off.z * cos);
		}
		double yaw = Math.toRadians(look);
		double fx = -Math.sin(yaw), fz = Math.cos(yaw);
		double rx = -fz, rz = fx;
		// The spot in the player's own right / forward terms.
		double right = off.x * rx + off.z * rz;
		double forward = off.x * fx + off.z * fz;
		if (slot == 1) right = -right;
		else {
			right = right * 0.2;
			forward = forward - 0.9;
		}
		if (Math.abs(right) < 0.35 && slot == 1) right = right < 0 ? -0.6 : 0.6;
		return new Vec3(rx * right + fx * forward, off.y + (slot == 2 ? 0.35 : 0), rz * right + fz * forward);
	}

	/**
	 * Pushes the extra pets' spots apart (and away from the main pet's) until every two heads are at least their
	 * sizes apart, a few rounds, then keeps them out of the player. The main pet doesn't move.
	 */
	private static void separate(Vec3 mainOff, double mainSize, Vec3[] goals, double[] sizes, double h, float look) {
		for (int round = 0; round < 4; round++) {
			for (int i = 0; i < goals.length; i++) {
				// Away from the main pet.
				goals[i] = apart(goals[i], mainOff, (sizes[i] + mainSize) * 0.5 + 0.15, 1.0);
				for (int j = 0; j < goals.length; j++) {
					if (i == j) continue;
					double need = (sizes[i] + sizes[j]) * 0.5 + 0.15;
					Vec3 d = goals[i].subtract(goals[j]);
					if (d.lengthSqr() >= need * need) continue;
					// Both move half the way.
					Vec3 pushed = apart(goals[i], goals[j], need, 0.5);
					Vec3 moved = pushed.subtract(goals[i]);
					goals[i] = pushed;
					goals[j] = goals[j].subtract(moved);
				}
			}
		}
		for (int i = 0; i < goals.length; i++) goals[i] = clear(goals[i], h, sizes[i], look);
	}

	/** a moved away from b to at least need apart (share: how much of the gap a takes). */
	private static Vec3 apart(Vec3 a, Vec3 b, double need, double share) {
		Vec3 d = a.subtract(b);
		double len = d.length();
		if (len >= need) return a;
		// Right on top of each other: apart sideways.
		Vec3 dir = len < 1e-3 ? new Vec3(1, 0, 0) : d.scale(1 / len);
		return a.add(dir.scale((need - len) * share));
	}

	/** Where an outing takes the pet now (a spot in the world), running its steps on the way. */
	private static Target outing(Follower f, Outing o, Vec3 base, Target home, ServerPlayer player, long time) {
		Vec3 homeAt = base.add(home.offset);
		Vec3 dest = null;
		if (!o.back) {
			dest = o.target.get();
			if (dest == null) {
				o.back = true;
			} else if (!o.arrived && f.pos.distanceToSqr(dest) < o.reach * o.reach) {
				o.arrived = true;
				o.holdUntil = time + o.hold;
				if (o.arrive != null) o.arrive.run();
				// The step may have ended it (or sent the pet off again).
				if (f.outing != o) return home;
			}
			if (o.arrived && time >= o.holdUntil) o.back = true;
		}
		if (o.back) {
			dest = homeAt;
			if (f.pos.distanceToSqr(homeAt) < 0.6 * 0.6) {
				f.outing = null;
				if (o.done != null) o.done.accept(true);
				return home;
			}
		}
		if (o.each != null) o.each.accept(f.pos);
		// On the way: where it goes; there: its owner.
		float yaw = o.arrived && !o.back ? heading(f.pos, player.getEyePosition()) : heading(f.pos, dest);
		return new Target(dest.subtract(base), yaw, 0.2, true);
	}

	private static void endOuting(Follower f, boolean ok) {
		Outing o = f.outing;
		if (o == null) return;
		f.outing = null;
		if (o.done != null) o.done.accept(ok);
	}

	// ---------------------------------------------------------------- for perks and reactions

	/** The pet shown for the player now (not one saying bye), or null. */
	static Pets.Owned shownOwned(UUID player) {
		Follower f = followers.get(player);
		return f == null || f.display == null || f.display.isRemoved() || f.leaveAt != 0 ? null : f.owned;
	}

	/** Where the player's pet is (the middle of its head), or null. */
	static Vec3 position(UUID player) {
		Follower f = followers.get(player);
		return f == null || f.display == null ? null : f.pos;
	}

	static boolean onOuting(UUID player) {
		Follower f = followers.get(player);
		return f != null && f.outing != null;
	}

	/**
	 * Sends the pet to target (followed while it moves; null: come back), runs arrive once it's within reach,
	 * waits there hold ticks, comes back and calls done(true); done(false) when cut short (teleport, timeout,
	 * put away). each gets the pet's position every tick. False when it has no pet out or is on another outing.
	 */
	static boolean startOuting(ServerPlayer player, java.util.function.Supplier<Vec3> target, double reach, int hold, Runnable arrive,
			java.util.function.Consumer<Vec3> each, java.util.function.Consumer<Boolean> done, int timeout) {
		Follower f = followers.get(player.getUUID());
		if (f == null || f.display == null || f.pos == null || f.leaveAt != 0 || f.outing != null) return false;
		f.outing = new Outing(target, reach, hold, arrive, each, done, player.level().getServer().getTickCount() + timeout);
		return true;
	}

	static void cancelOuting(UUID player) {
		Follower f = followers.get(player);
		if (f != null) endOuting(f, false);
	}

	/** A short move (lookAt: where LOOK_AT looks). */
	static void animate(UUID player, Move move, int ticks, Vec3 lookAt) {
		Follower f = followers.get(player);
		if (f == null || f.display == null) return;
		long now = f.display.level().getServer().getTickCount();
		f.move = move;
		f.moveStart = now;
		f.moveUntil = now + ticks;
		f.lookAt = lookAt;
	}

	/**
	 * Keeps the pet dancing while music plays: starts the dance when it isn't doing another move, and keeps
	 * a running one going for ticks more.
	 */
	static void dance(UUID player, int ticks) {
		Follower f = followers.get(player);
		if (f == null || f.display == null || f.outing != null) return;
		long now = f.display.level().getServer().getTickCount();
		if (f.move == Move.DANCE && now < f.moveUntil) {
			f.moveUntil = Math.max(f.moveUntil, now + ticks);
		} else if (f.move == null || now >= f.moveUntil) {
			animate(player, Move.DANCE, ticks, null);
		}
	}

	/** Particles while a move plays (the reaction's own burst comes once, at the start). */
	private static void sparkle(ServerPlayer player, Follower f, double a) {
		if (f.pos == null) return;
		ServerLevel level = player.level();
		Vec3 at = f.pos.add(0, 0.25 * f.size + 0.15, 0);
		int tick = (int) a;
		switch (f.move) {
			// A note on every beat, in a colour that walks through the rainbow (count 0: x is the colour).
			case DANCE -> {
				if (tick % 10 == 0) level.sendParticles(ParticleTypes.NOTE, at.x, at.y + 0.3, at.z, 0, (tick / 10 % 24) / 24.0, 0, 0, 1);
			}
			case CHEER -> {
				if (tick % 5 == 0) level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 2, 0.2, 0.2, 0.2, 0.02);
			}
			case PARTY -> {
				if (tick % 4 == 0) level.sendParticles(ParticleTypes.FIREWORK, at.x, at.y, at.z, 1, 0.15, 0.15, 0.15, 0.02);
				if (tick % 12 == 0) level.sendParticles(ParticleTypes.NOTE, at.x, at.y + 0.3, at.z, 0, (tick / 12 % 24) / 24.0, 0, 0, 1);
			}
			case FLIP -> {
				if (tick == 0) level.sendParticles(ParticleTypes.CLOUD, at.x, at.y - 0.3, at.z, 4, 0.15, 0.05, 0.15, 0.01);
			}
			case NUZZLE -> {
				if (tick % 10 == 5) level.sendParticles(ParticleTypes.HEART, at.x, at.y + 0.1, at.z, 1, 0.1, 0.1, 0.1, 0);
			}
			case SHAKE_OFF -> {
				if (tick % 3 == 0 && tick < 18) level.sendParticles(ParticleTypes.SPLASH, at.x, at.y - 0.1, at.z, 4, 0.2, 0.1, 0.2, 0.1);
			}
			default -> {
			}
		}
	}

	/** Slow, fast, slow (0 to 1). */
	private static double ease(double p) {
		return p * p * (3 - 2 * p);
	}

	/** A speech bubble above the pet for a while. */
	static void bubble(UUID player, String text, int ticks) {
		Follower f = followers.get(player);
		if (f == null || f.display == null) return;
		f.bubble = text;
		f.bubbleUntil = f.display.level().getServer().getTickCount() + ticks;
	}

	/** The name (hidden while the owner sneaks) and bubble above the pet; no entity while there's neither. */
	private static void label(Follower f, ServerPlayer player, Pets.Owner owner, double size, long time, Vec3 shown) {
		String nick = PetStore.settings().names && owner.showName && f.owned != null && !player.isShiftKeyDown() ? f.owned.nickname : "";
		boolean bubble = f.bubble != null && time < f.bubbleUntil;
		if (!bubble && (nick == null || nick.isEmpty())) {
			removeLabel(f);
			return;
		}
		MutableComponent text = Component.empty();
		if (bubble) text.append(Component.literal(f.bubble).withStyle(ChatFormatting.WHITE));
		if (nick != null && !nick.isEmpty()) {
			if (bubble) text.append("\n");
			int rgb = eu.explorerseden.nicecontrolcenter.Claims.parseColor(f.owned.nameColor);
			text.append(Component.literal(nick).withColor(rgb < 0 ? 0xFFFFFF : rgb));
		}
		// Just above the head, sent the same way, so the two move as one.
		Vec3 at = shown.add(0, 0.25 * size + 0.12, 0);
		if (f.label == null || f.label.isRemoved() || f.label.level() != player.level()) {
			removeLabel(f);
			f.label = spawnLabel(player.level(), at);
			if (f.label == null) return;
		} else if (f.label.position().distanceToSqr(at) > 1e-6) {
			f.label.setPos(at);
		}
		if (!text.equals(f.labelText)) {
			((TextDisplayAccessor) f.label).nicecontrolcenter$setText(text);
			f.labelText = text;
		}
	}

	private static Display.TextDisplay spawnLabel(ServerLevel level, Vec3 at) {
		@SuppressWarnings("unchecked")
		EntityType<Display.TextDisplay> type = (EntityType<Display.TextDisplay>) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("text_display"));
		Display.TextDisplay d = new Display.TextDisplay(type, level);
		d.setPos(at);
		d.addTag(TAG);
		DisplayAccessor a = (DisplayAccessor) d;
		a.nicecontrolcenter$setBillboard(Display.BillboardConstraints.CENTER);
		a.nicecontrolcenter$setMoveDuration(GLIDE);
		a.nicecontrolcenter$setViewRange(0.5f);
		((TextDisplayAccessor) d).nicecontrolcenter$setLineWidth(200);
		spawning = true;
		try {
			return level.addFreshEntity(d) ? d : null;
		} finally {
			spawning = false;
		}
	}

	private static void removeLabel(Follower f) {
		if (f.label != null) f.label.discard();
		f.label = null;
		f.labelText = null;
	}

	/** The way the head looks (pitch: nods down), which clients turn to in ticks. */
	private static void pose(Follower f, float yaw, float pitch, double size, int ticks) {
		float s = (float) size;
		// The same turn a display with this yaw gets (the entity's own yaw stays 0).
		Quaternionf turn = new Quaternionf().rotationY((float) Math.toRadians(-(yaw + FACE))).rotateX((float) Math.toRadians(pitch));
		// A head item is drawn below the display's spot (it turns around its top face); lift it, turned the same way,
		// so the spot is its middle and nods and flips turn it in place instead of swinging it around.
		Vector3f move = turn.transform(new Vector3f(0, 0.25f * s, 0));
		DisplayAccessor d = (DisplayAccessor) f.display;
		d.nicecontrolcenter$setTransformation(new Transformation(move, turn, new Vector3f(s, s, s), new Quaternionf()));
		d.nicecontrolcenter$setInterpolationDuration(ticks);
		d.nicecontrolcenter$setInterpolationDelay(0);
	}

	/** Removes the head and its name. */
	private static void discard(Follower f, ServerPlayer player) {
		endOuting(f, false);
		removeLabel(f);
		if (f.display == null) return;
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

	/**
	 * Keeps the head out of the player's body: an offset (from their feet) closer to their middle than their
	 * half width plus the head's half size is pushed out sideways, so no move ever passes through them.
	 */
	private static Vec3 clear(Vec3 off, double h, double size, float look) {
		double r = 0.3 + 0.25 * size + 0.1;
		if (off.y < -0.25 * size || off.y > h + 0.25 * size) return off;
		double d2 = off.x * off.x + off.z * off.z;
		if (d2 >= r * r) return off;
		double d = Math.sqrt(d2);
		if (d < 1e-3) {
			// Right in the middle: out to the player's right.
			double yaw = Math.toRadians(look);
			return new Vec3(-Math.cos(yaw) * r, off.y, -Math.sin(yaw) * r);
		}
		return new Vec3(off.x / d * r, off.y, off.z / d * r);
	}

	/**
	 * AFK owner: the pet floats to a new spot a few blocks around them every few seconds, slowly. It looks where it goes and, once there, turns to its owner; the turn is
	 * eased so it never spins.
	 */
	private static Target roam(ServerPlayer player, double t, Follower f) {
		double h = player.getBbHeight();
		long hop = (long) (t / 70) + f.phase;
		double a = frac(Math.sin(hop * 12.9898) * 43758.5453) * Math.PI * 2;
		double far = 1.6 + 2.0 * frac(Math.sin(hop * 39.346) * 11.135);
		double up = h * (0.35 + 0.6 * frac(Math.sin(hop * 78.233) * 12543.123));
		Vec3 spot = new Vec3(Math.cos(a) * far, up + 0.06 * Math.sin(t * 0.08), Math.sin(a) * far);
		Vec3 now = f.offset == null ? spot : f.offset;
		Vec3 eyes = new Vec3(0, player.getEyeHeight(), 0);
		// On the way: where it goes. There (or nearly): its owner.
		float want = now.distanceToSqr(spot) > 0.5 * 0.5 ? heading(now, spot) : heading(now, eyes);
		if (Float.isNaN(f.roamYaw)) f.roamYaw = want;
		f.roamYaw += net.minecraft.util.Mth.wrapDegrees(want - f.roamYaw) * 0.12f;
		return new Target(spot, f.roamYaw, 0.03);
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
		Pets.Owner owner = PetStore.data().owners.get(player.getStringUUID());
		if (owner != null) rollPet(f, owner, player);
	}

	/** One of the owner's pets for Random pet, not the same one twice in a row when they have more. */
	private static void rollPet(Follower f, Pets.Owner owner, ServerPlayer player) {
		List<Pets.Owned> pets = new ArrayList<>(owner.pets);
		if (pets.size() > 1) pets.removeIf(o -> o.pet.equals(f.rolledPet));
		f.rolledPet = pets.isEmpty() ? null : pets.get(player.getRandom().nextInt(pets.size())).pet;
	}

	/** The pet shown for this player right now while Random pet is on, or null. */
	static String shownPet(UUID player) {
		Follower f = followers.get(player);
		return f == null || f.display == null ? null : f.rolledPet;
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
		// Close around the head, not inside it, and with no push of their own: they float where they appear.
		// Placed by hand in a small box (sendParticles' spread is a bell curve, so a few always strayed far, often
		// into the owner's face), a little below the head's middle and nudged to its side away from the owner.
		double spread = 0.12 + 0.12 * size;
		var r = player.getRandom();
		Vec3 eyes = player.getEyePosition();
		double ax = f.pos.x - eyes.x;
		double az = f.pos.z - eyes.z;
		double len = Math.sqrt(ax * ax + az * az);
		double away = 0.1 * size;
		double cx = f.pos.x + (len > 1e-3 ? ax / len * away : 0);
		double cz = f.pos.z + (len > 1e-3 ? az / len * away : 0);
		double x = cx + (r.nextDouble() - 0.5) * 2 * spread;
		double y = f.pos.y - 0.15 * size + (r.nextDouble() - 0.5) * spread;
		double z = cz + (r.nextDouble() - 0.5) * 2 * spread;
		if (trail == Pets.Trail.NOTES) {
			// A note's colour comes in the first of the three numbers.
			level.sendParticles(ParticleTypes.NOTE, x, y, z, 0, r.nextDouble(), 0, 0, 1);
			return;
		}
		if (trail == Pets.Trail.AUTUMN_LEAVES || trail == Pets.Trail.LUSH_LEAVES) {
			int[] colours = trail == Pets.Trail.AUTUMN_LEAVES ? AUTUMN : LUSH;
			type = ColorParticleOption.create(ParticleTypes.TINTED_LEAVES, 0xFF000000 | colours[r.nextInt(colours.length)]);
		}
		level.sendParticles(type, x, y, z, 1, 0, 0, 0, 0);
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
			NiceControlCenter.LOGGER.warn("A head can't be made: {}", e.toString());
			return ItemStack.EMPTY;
		}
	}

	static void remove(UUID player) {
		Follower f = followers.remove(player);
		if (f != null) discard(f, null);
		List<Follower> more = extras.remove(player);
		if (more != null) for (Follower x : more) discard(x, null);
	}

	static void removeAll() {
		for (Follower f : followers.values()) discard(f, null);
		followers.clear();
		for (List<Follower> more : extras.values()) for (Follower x : more) discard(x, null);
		extras.clear();
	}

	/** A pet display of ours from a session that's over: gone. */
	static boolean leftOver(Entity e) {
		return !spawning && (e instanceof Display.ItemDisplay || e instanceof Display.TextDisplay) && e.entityTags().contains(TAG)
				&& followers.values().stream().noneMatch(f -> f.display == e || f.label == e)
				&& extras.values().stream().flatMap(List::stream).noneMatch(f -> f.display == e || f.label == e);
	}
}
