package eu.explorerseden.nicecontrolcenter.games;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import eu.explorerseden.nicecontrolcenter.Dungeons;

/**
 * Picks the rooms of one dungeon copy, like vanilla jigsaw structures: a spot (a jigsaw with a target pool) gets a
 * room of that group whose connector is named like the spot's target, turned so the two face each other and placed
 * so they sit face to face. Rooms never reach outside the dungeon's box and never overlap each other; a spot nothing
 * fits in stays closed. Positions here are relative to the box's lowest corner. Plain math on the saved data.
 */
public final class DungeonRooms {
	/** A room in the copy: its (0, 0, 0) lands at pos after turning by rotation; door is the connector it joined with. */
	public record Placed(Dungeons.Room room, BlockPos pos, Rotation rotation, int level, Dungeons.Connector door) {
	}

	/** A connector block and what it turns into. */
	public record Joint(BlockPos pos, String finalState) {
	}

	/** The rooms, their markers (turned, relative to the box) and every connector to finish. */
	public record Layout(List<Placed> rooms, List<Dungeons.Marker> markers, List<Joint> joints) {
	}

	/** An open spot: where it is, where it faces, its top, joint, group, target, the level a room there gets, the room it's in (-1: the dungeon) and its entry in the joints. */
	private record Spot(BlockPos pos, Direction facing, Direction top, boolean rollable, String pool, String target, int level, Integer chance, int parent, int joint) {
	}

	/** An integer box (inclusive). */
	private record Box(int x0, int y0, int z0, int x1, int y1, int z1) {
		boolean overlaps(Box o) {
			return x0 <= o.x1 && x1 >= o.x0 && y0 <= o.y1 && y1 >= o.y0 && z0 <= o.z1 && z1 >= o.z0;
		}

		boolean inside(Box o) {
			return x0 >= o.x0 && x1 <= o.x1 && y0 >= o.y0 && y1 <= o.y1 && z0 >= o.z0 && z1 <= o.z1;
		}
	}

	private DungeonRooms() {
	}

	public static Layout plan(Dungeons.Dungeon d, Dungeons.Settings s, Random rnd) {
		List<Placed> placed = new ArrayList<>();
		List<Box> boxes = new ArrayList<>();
		List<Dungeons.Marker> markers = new ArrayList<>();
		List<Joint> joints = new ArrayList<>();
		Map<String, Integer> used = new HashMap<>();
		Box area = new Box(0, 0, 0, d.sizeX - 1, d.sizeY - 1, d.sizeZ - 1);
		ArrayDeque<Spot> open = new ArrayDeque<>();
		for (Dungeons.Connector c : d.connectors) {
			BlockPos at = new BlockPos(c.x, c.y, c.z);
			joints.add(new Joint(at, c.finalState));
			if (c.spot()) open.add(spot(c, at, Rotation.NONE, 1, d.spotChances.get(c.x + "," + c.y + "," + c.z), -1, joints.size() - 1));
		}
		while (!open.isEmpty() && placed.size() < d.maxRooms) {
			Spot sp = open.poll();
			if (sp.level > d.roomDepth) continue;
			Dungeons.Group g = s.group(sp.pool);
			int chance = sp.chance != null ? sp.chance : g.fillChance;
			if (chance < 100 && rnd.nextInt(100) >= chance) continue;
			if (sp.level - 1 < g.minDepth) continue;
			List<Dungeons.Room> fresh = new ArrayList<>(), again = new ArrayList<>();
			for (Dungeons.Room r : s.roomsIn(sp.pool)) {
				if (!r.enabled || !r.saved()) continue;
				int n = used.getOrDefault(r.id, 0);
				if (r.maxPerCopy > 0 && n >= r.maxPerCopy) continue;
				if (r.chance < 100 && rnd.nextInt(100) >= r.chance) continue;
				(n == 0 ? fresh : again).add(r);
			}
			// Rooms not in this copy yet come first; one already in it only when none of those fits.
			Placed fit = null;
			for (List<Dungeons.Room> list : List.of(byWeight(fresh, rnd), byWeight(again, rnd))) {
				for (Dungeons.Room r : list) {
					fit = fit(r, sp, area, boxes, rnd);
					if (fit != null) break;
				}
				if (fit != null) break;
			}
			if (fit == null) continue;
			placed.add(fit);
			// A filled spot becomes what the room's door turns into (the doorway); an empty one keeps its own block (a wall).
			joints.set(sp.joint, new Joint(sp.pos, fit.door.finalState));
			boxes.add(box(fit.room, fit.pos, fit.rotation));
			used.merge(fit.room.id, 1, Integer::sum);
			for (Dungeons.Marker m : fit.room.markers) {
				BlockPos at = at(fit, m.x, m.y, m.z);
				Dungeons.Marker w = new Dungeons.Marker();
				w.type = m.type;
				w.arg = m.arg;
				w.x = at.getX();
				w.y = at.getY();
				w.z = at.getZ();
				markers.add(w);
			}
			for (Dungeons.Connector c : fit.room.connectors) {
				BlockPos at = at(fit, c.x, c.y, c.z);
				joints.add(new Joint(at, c.finalState));
				if (c.spot()) open.add(spot(c, at, fit.rotation, fit.level + 1, null, placed.size() - 1, joints.size() - 1));
			}
		}
		return new Layout(placed, markers, joints);
	}

	/** A room's first way into the spot: a connector named like its target, turned to face it, inside the box. */
	private static Placed fit(Dungeons.Room r, Spot sp, Box area, List<Box> boxes, Random rnd) {
		List<Dungeons.Connector> doors = new ArrayList<>();
		for (Dungeons.Connector c : r.connectors) if (c.name.equals(sp.target)) doors.add(c);
		java.util.Collections.shuffle(doors, rnd);
		List<Rotation> turns = new ArrayList<>(List.of(Rotation.values()));
		for (Dungeons.Connector c : doors) {
			Direction front = dir(c.facing, Direction.NORTH), top = dir(c.top, Direction.UP);
			java.util.Collections.shuffle(turns, rnd);
			for (Rotation rot : turns) {
				if (rot.rotate(front) != sp.facing.getOpposite()) continue;
				if (sp.facing.getAxis().isVertical() && !sp.rollable && rot.rotate(top) != sp.top) continue;
				// The room's connector sits right in front of the spot.
				BlockPos door = sp.pos.relative(sp.facing);
				BlockPos pos = door.subtract(StructureTemplate.transform(new BlockPos(c.x, c.y, c.z), Mirror.NONE, rot, BlockPos.ZERO));
				Box b = box(r, pos, rot);
				if (!b.inside(area)) continue;
				boolean clear = true;
				// A room may sit inside the room whose spot it fills (a random corner of it), never in any other.
				for (int i = 0; i < boxes.size(); i++) {
					if (i != sp.parent && b.overlaps(boxes.get(i))) {
						clear = false;
						break;
					}
				}
				if (clear) return new Placed(r, pos, rot, sp.level, c);
			}
		}
		return null;
	}

	/** Random order where a heavier room tends to come first. */
	private static List<Dungeons.Room> byWeight(List<Dungeons.Room> rooms, Random rnd) {
		List<Dungeons.Room> left = new ArrayList<>(rooms), out = new ArrayList<>();
		while (!left.isEmpty()) {
			int total = 0;
			for (Dungeons.Room r : left) total += r.weight;
			int roll = rnd.nextInt(total);
			for (int i = 0; i < left.size(); i++) {
				roll -= left.get(i).weight;
				if (roll < 0) {
					out.add(left.remove(i));
					break;
				}
			}
		}
		return out;
	}

	private static Spot spot(Dungeons.Connector c, BlockPos at, Rotation rot, int level, Integer chance, int parent, int joint) {
		return new Spot(at, rot.rotate(dir(c.facing, Direction.NORTH)), rot.rotate(dir(c.top, Direction.UP)), !"aligned".equals(c.joint), c.pool, c.target, level, chance, parent, joint);
	}

	/** Where a block of the room (its own coordinates) ends up. */
	private static BlockPos at(Placed p, int x, int y, int z) {
		return p.pos.offset(StructureTemplate.transform(new BlockPos(x, y, z), Mirror.NONE, p.rotation, BlockPos.ZERO));
	}

	private static Box box(Dungeons.Room r, BlockPos pos, Rotation rot) {
		BlockPos a = pos.offset(StructureTemplate.transform(BlockPos.ZERO, Mirror.NONE, rot, BlockPos.ZERO));
		BlockPos b = pos.offset(StructureTemplate.transform(new BlockPos(r.sizeX - 1, r.sizeY - 1, r.sizeZ - 1), Mirror.NONE, rot, BlockPos.ZERO));
		return new Box(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
				Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
	}

	private static Direction dir(String name, Direction fallback) {
		Direction d = name == null ? null : Direction.byName(name);
		return d == null ? fallback : d;
	}
}
