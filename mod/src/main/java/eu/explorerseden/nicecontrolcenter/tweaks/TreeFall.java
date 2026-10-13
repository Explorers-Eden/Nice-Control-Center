package eu.explorerseden.nicecontrolcenter.tweaks;

import com.mojang.math.Transformation;
import eu.explorerseden.nicecontrolcenter.mixin.BlockDisplayAccessor;
import eu.explorerseden.nicecontrolcenter.mixin.DisplayAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * The falling tree: the felled blocks become block displays that tip over around the stump, away from
 * the player, speeding up like a real tree; when they hit the ground they vanish and the drops land
 * where the blocks came down. Display entities are vanilla, so every client sees it. The displays are
 * tagged and never outlive the animation (also not a server stop or a chunk reload).
 */
public final class TreeFall {
	static final String TAG = "nicecontrolcenter_falling_tree";
	/** Ticks the fall takes, and how often the displays get their next angle. */
	private static final int DURATION = 24;
	private static final int STEP = 4;
	private static final int MAX_DISPLAYS = 1024;

	/** A block that falls. */
	record Piece(BlockPos pos, BlockState state) {
	}

	private static final class Fall {
		final ServerLevel level;
		final Vec3 pivot;
		final Vector3f axis;
		final List<Display.BlockDisplay> displays = new ArrayList<>();
		final List<Vector3f> offsets = new ArrayList<>();
		final List<Piece> pieces;
		final List<ItemEntity> drops;
		final boolean dropsInPlace;
		final long start;

		Fall(ServerLevel level, Vec3 pivot, Vector3f axis, List<Piece> pieces, List<ItemEntity> drops, boolean dropsInPlace) {
			this.level = level;
			this.pivot = pivot;
			this.axis = axis;
			this.pieces = pieces;
			this.drops = drops;
			this.dropsInPlace = dropsInPlace;
			this.start = level.getGameTime();
		}
	}

	private static final List<Fall> falls = new ArrayList<>();
	/** True while start() adds its displays (they aren't in a fall yet). */
	private static boolean spawning;

	private TreeFall() {
	}

	/**
	 * Starts a fall of the given (already removed) blocks around the cut at origin, away from from.
	 * The drops are spawned when it lands; dropsInPlace keeps them where they are (at the stump).
	 */
	static void start(ServerLevel level, BlockPos origin, Vec3 from, List<Piece> pieces, List<ItemEntity> drops, boolean dropsInPlace) {
		Vec3 pivot = new Vec3(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
		double dx = pivot.x - from.x, dz = pivot.z - from.z;
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len < 1.0e-3) {
			dx = 1;
			dz = 0;
			len = 1;
		}
		dx /= len;
		dz /= len;
		// Turning around up × direction tips the top of the tree towards the direction.
		Fall fall = new Fall(level, pivot, new Vector3f((float) dz, 0, (float) -dx), pieces, drops, dropsInPlace);
		spawning = true;
		try {
			spawn(level, fall, pieces);
		} finally {
			spawning = false;
		}
		BlockState first = pieces.isEmpty() ? null : pieces.get(0).state();
		if (first != null) {
			level.playSound(null, pivot.x, pivot.y + 1, pivot.z, first.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.2f, 0.5f);
		}
		falls.add(fall);
	}

	private static void spawn(ServerLevel level, Fall fall, List<Piece> pieces) {
		Vec3 pivot = fall.pivot;
		for (Piece p : pieces) {
			if (fall.displays.size() >= MAX_DISPLAYS) break;
			Display.BlockDisplay d = new Display.BlockDisplay(BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("block_display")), level);
			d.setPos(pivot);
			d.addTag(TAG);
			((BlockDisplayAccessor) d).nicecontrolcenter$setBlockState(p.state());
			Vector3f offset = new Vector3f((float) (p.pos().getX() - pivot.x), (float) (p.pos().getY() - pivot.y), (float) (p.pos().getZ() - pivot.z));
			((DisplayAccessor) d).nicecontrolcenter$setTransformation(transform(offset, fall.axis, 0));
			if (level.addFreshEntity(d)) {
				fall.displays.add(d);
				fall.offsets.add(offset);
			}
		}
	}

	/** The display's transformation at an angle: the block turned around the pivot. */
	private static Transformation transform(Vector3f offset, Vector3f axis, float angle) {
		Quaternionf q = new Quaternionf().rotateAxis(angle, axis.x, axis.y, axis.z);
		Vector3f t = q.transform(new Vector3f(offset));
		return new Transformation(t, q, new Vector3f(1, 1, 1), new Quaternionf());
	}

	static void tick(MinecraftServer server) {
		if (falls.isEmpty()) return;
		var it = falls.iterator();
		while (it.hasNext()) {
			Fall f = it.next();
			long age = f.level.getGameTime() - f.start;
			// The first step waits a tick so clients have the displays in their starting place.
			if (age >= 1 && age < DURATION + 1 && (age - 1) % STEP == 0) {
				float done = Math.min(1f, (float) (age - 1 + STEP) / DURATION);
				float angle = (float) (Math.PI / 2 * done * done);
				for (int i = 0; i < f.displays.size(); i++) {
					DisplayAccessor d = (DisplayAccessor) f.displays.get(i);
					d.nicecontrolcenter$setTransformation(transform(f.offsets.get(i), f.axis, angle));
					d.nicecontrolcenter$setInterpolationDuration(STEP);
					d.nicecontrolcenter$setInterpolationDelay(0);
				}
			}
			if (age >= DURATION + 2) {
				land(f);
				it.remove();
			}
		}
	}

	/** Ends every fall now (the server is stopping). */
	static void finishAll() {
		for (Fall f : falls) land(f);
		falls.clear();
	}

	/** A display of ours left over from an animation that didn't finish: gone. */
	static boolean leftOver(Entity e) {
		return !spawning && e instanceof Display.BlockDisplay && e.entityTags().contains(TAG) && falls.stream().noneMatch(f -> f.displays.contains(e));
	}

	private static void land(Fall f) {
		for (Display.BlockDisplay d : f.displays) d.discard();
		Quaternionf down = new Quaternionf().rotateAxis((float) (Math.PI / 2), f.axis.x, f.axis.y, f.axis.z);
		int sounds = 0;
		int particles = 0;
		for (ItemEntity item : f.drops) {
			if (!f.dropsInPlace) {
				// Where the block this item came from ended up.
				Vector3f c = new Vector3f((float) (item.getX() - f.pivot.x), (float) (item.getY() - f.pivot.y), (float) (item.getZ() - f.pivot.z));
				down.transform(c);
				BlockPos at = free(f.level, BlockPos.containing(f.pivot.x + c.x, f.pivot.y + c.y, f.pivot.z + c.z));
				item.setPos(at.getX() + 0.5, at.getY() + 0.2, at.getZ() + 0.5);
			}
			f.level.addFreshEntity(item);
		}
		for (Piece p : f.pieces) {
			Vector3f c = new Vector3f((float) (p.pos().getX() + 0.5 - f.pivot.x), (float) (p.pos().getY() + 0.5 - f.pivot.y), (float) (p.pos().getZ() + 0.5 - f.pivot.z));
			down.transform(c);
			BlockPos at = free(f.level, BlockPos.containing(f.pivot.x + c.x, f.pivot.y + c.y, f.pivot.z + c.z));
			if (particles < 48 && p.pos().getY() % 2 == 0) {
				particles++;
				f.level.levelEvent(2001, at, Block.getId(p.state()));
			}
			if (sounds < 3 && particles % 12 == 1) {
				sounds++;
				f.level.playSound(null, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, p.state().getSoundType().getFallSound(), SoundSource.BLOCKS, 1.5f, 0.6f);
			}
		}
	}

	/** The first free spot at or a little above pos, so drops don't land inside a hill. */
	private static BlockPos free(ServerLevel level, BlockPos pos) {
		BlockPos p = pos;
		for (int i = 0; i < 8 && !level.getBlockState(p).getCollisionShape(level, p).isEmpty(); i++) p = p.above();
		return p;
	}
}
