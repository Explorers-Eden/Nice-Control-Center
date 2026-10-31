package eu.explorerseden.nicecontrolcenter.games;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import net.minecraft.core.BlockPos;
import net.minecraft.core.FrontAndTop;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

import eu.explorerseden.nicecontrolcenter.Dungeons;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;

/**
 * A dungeon's blocks: saved as pieces of 16 × 16 columns (as high as the box between its corners), each a structure file with its
 * blocks, block entities (chests keep a loot table that isn't rolled yet) and entities, in
 * world/nicecontrolcenter/dungeons/<name>/. Reading the world and placing blocks happen on the server thread
 * (Minecraft needs that); writing, reading and parsing the files on a worker of their own.
 */
public final class DungeonTemplates {
	/** Block flags: tell clients, no neighbour updates or shape changes, no drops when replaced. */
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "ncc-dungeon-files");
		t.setDaemon(true);
		return t;
	});

	/** One piece: where it sits in the box (from the lowest corner) and its blocks. */
	public record Piece(int dx, int dz, StructureTemplate template) {
	}

	private DungeonTemplates() {
	}

	static Path dir(MinecraftServer server, String name) {
		return server.getWorldPath(LevelResource.ROOT).resolve("nicecontrolcenter").resolve("dungeons").resolve(name.toLowerCase(java.util.Locale.ROOT));
	}

	// ---------------------------------------------------------------- saving

	/**
	 * Copies the box (inclusive corners) into pieces and fills in the dungeon's size and markers; the files are
	 * written on the worker. The future says when they're on disk (or why not).
	 */
	static CompletableFuture<Void> save(MinecraftServer server, ServerLevel level, BlockPos a, BlockPos b, Dungeons.Dungeon d) {
		BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
		BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
		int sx = max.getX() - min.getX() + 1, sy = max.getY() - min.getY() + 1, sz = max.getZ() - min.getZ() + 1;
		List<CompoundTag> tags = new ArrayList<>();
		List<int[]> where = new ArrayList<>();
		List<Dungeons.Marker> markers = new ArrayList<>();
		List<Dungeons.Connector> connectors = new ArrayList<>();
		for (int dx = 0; dx < sx; dx += Dungeons.PIECE) {
			for (int dz = 0; dz < sz; dz += Dungeons.PIECE) {
				StructureTemplate t = new StructureTemplate();
				Vec3i size = new Vec3i(Math.min(Dungeons.PIECE, sx - dx), sy, Math.min(Dungeons.PIECE, sz - dz));
				// Structure voids stay out, so the copy keeps the void around them.
				t.fillFromWorld(level, min.offset(dx, 0, dz), size, true, List.of(Blocks.STRUCTURE_VOID));
				scan(t, dx, dz, markers, connectors);
				tags.add(t.save(new CompoundTag()));
				where.add(new int[] {dx, dz});
			}
		}
		d.sourceDim = level.dimension().identifier().toString();
		d.from = new int[] {min.getX(), min.getY(), min.getZ()};
		d.to = new int[] {max.getX(), max.getY(), max.getZ()};
		d.sizeX = sx;
		d.sizeY = sy;
		d.sizeZ = sz;
		d.pieces = tags.size();
		d.markers = markers;
		d.connectors = connectors;
		d.savedAt = System.currentTimeMillis();
		Path target = dir(server, d.name);
		return CompletableFuture.runAsync(() -> {
			try {
				// Into a fresh folder first, then swapped in: a run starting meanwhile still reads the old one whole.
				Path tmp = target.resolveSibling(target.getFileName() + ".saving");
				deleteTree(tmp);
				Files.createDirectories(tmp);
				for (int i = 0; i < tags.size(); i++) NbtIo.writeCompressed(tags.get(i), tmp.resolve(where.get(i)[0] + "_" + where.get(i)[1] + ".nbt"));
				Path old = target.resolveSibling(target.getFileName() + ".old");
				deleteTree(old);
				if (Files.exists(target)) Files.move(target, old);
				Files.move(tmp, target);
				deleteTree(old);
			} catch (IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		}, IO);
	}

	/** The markers (data structure blocks) and connectors (jigsaw blocks) of a template, shifted by dx/dz. */
	private static void scan(StructureTemplate t, int dx, int dz, List<Dungeons.Marker> markers, List<Dungeons.Connector> connectors) {
		StructurePlaceSettings plain = new StructurePlaceSettings();
		for (StructureTemplate.StructureBlockInfo info : t.filterBlocks(BlockPos.ZERO, plain, Blocks.STRUCTURE_BLOCK)) {
			CompoundTag nbt = info.nbt();
			if (nbt == null || !"DATA".equals(nbt.getStringOr("mode", ""))) continue;
			String text = nbt.getStringOr("metadata", "").strip();
			String type = text.split("[\\s:]", 2)[0].toLowerCase(java.util.Locale.ROOT);
			if (!Dungeons.MARKERS.contains(type)) continue;
			Dungeons.Marker m = new Dungeons.Marker();
			m.type = type;
			m.arg = text.length() > type.length() ? text.substring(type.length()).replaceFirst("^[\\s:]+", "") : "";
			m.x = info.pos().getX() + dx;
			m.y = info.pos().getY();
			m.z = info.pos().getZ() + dz;
			markers.add(m);
		}
		for (StructureTemplate.StructureBlockInfo info : t.filterBlocks(BlockPos.ZERO, plain, Blocks.JIGSAW)) {
			CompoundTag nbt = info.nbt() == null ? new CompoundTag() : info.nbt();
			FrontAndTop o = info.state().getValue(JigsawBlock.ORIENTATION);
			Dungeons.Connector c = new Dungeons.Connector();
			c.x = info.pos().getX() + dx;
			c.y = info.pos().getY();
			c.z = info.pos().getZ() + dz;
			c.facing = o.front().getSerializedName();
			c.top = o.top().getSerializedName();
			c.joint = nbt.getStringOr("joint", "rollable");
			c.name = shortId(nbt.getStringOr("name", ""));
			c.target = shortId(nbt.getStringOr("target", ""));
			String pool = shortId(nbt.getStringOr("pool", ""));
			c.pool = pool.isEmpty() || "empty".equals(pool) ? "" : Dungeons.pool(pool);
			c.finalState = nbt.getStringOr("final_state", "minecraft:air");
			connectors.add(c);
		}
	}

	/** "minecraft:door" → "door" (other namespaces stay). */
	private static String shortId(String id) {
		return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
	}

	// ---------------------------------------------------------------- rooms

	/** Rooms turn as a whole, so they're one file each and at most this big. */
	public static final int ROOM_MAX = 64;

	static Path roomFile(MinecraftServer server, String name) {
		return server.getWorldPath(LevelResource.ROOT).resolve("nicecontrolcenter").resolve("dungeon-rooms").resolve(name.toLowerCase(java.util.Locale.ROOT) + ".nbt");
	}

	/** Copies a room's box and fills in its size, markers and connectors; the file is written on the worker. */
	static CompletableFuture<Void> saveRoom(MinecraftServer server, ServerLevel level, BlockPos a, BlockPos b, Dungeons.Room room) {
		BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
		BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
		Vec3i size = new Vec3i(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1);
		StructureTemplate t = new StructureTemplate();
		t.fillFromWorld(level, min, size, true, List.of(Blocks.STRUCTURE_VOID));
		List<Dungeons.Marker> markers = new ArrayList<>();
		List<Dungeons.Connector> connectors = new ArrayList<>();
		scan(t, 0, 0, markers, connectors);
		CompoundTag tag = t.save(new CompoundTag());
		room.sourceDim = level.dimension().identifier().toString();
		room.from = new int[] {min.getX(), min.getY(), min.getZ()};
		room.to = new int[] {max.getX(), max.getY(), max.getZ()};
		room.sizeX = size.getX();
		room.sizeY = size.getY();
		room.sizeZ = size.getZ();
		room.markers = markers;
		room.connectors = connectors;
		room.savedAt = System.currentTimeMillis();
		Path target = roomFile(server, room.name);
		return CompletableFuture.runAsync(() -> {
			try {
				Files.createDirectories(target.getParent());
				Path tmp = target.resolveSibling(target.getFileName() + ".saving");
				NbtIo.writeCompressed(tag, tmp);
				Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		}, IO);
	}

	/** Reads and parses a room (call it on the worker). */
	static StructureTemplate readRoom(MinecraftServer server, String name) {
		Path f = roomFile(server, name);
		try {
			StructureTemplate t = new StructureTemplate();
			t.load(server.registryAccess().lookupOrThrow(Registries.BLOCK), NbtIo.readCompressed(f, NbtAccounter.unlimitedHeap()));
			return t;
		} catch (IOException | RuntimeException e) {
			throw new IllegalStateException("The room " + name + " can't be read: " + e.getMessage(), e);
		}
	}

	static void deleteRoom(MinecraftServer server, String name) {
		Path f = roomFile(server, name);
		IO.execute(() -> {
			try {
				Files.deleteIfExists(f);
			} catch (IOException e) {
				NiceControlCenter.LOGGER.warn("Dungeons: could not remove the room file {}: {}", f, e.getMessage());
			}
		});
	}

	/** Places a room turned around its own corner: pos is where the room's (0, 0, 0) lands (server thread). */
	static void pasteRoom(ServerLevel level, BlockPos pos, Rotation rotation, StructureTemplate t, RandomSource random) {
		t.placeInWorld(level, pos, pos, new StructurePlaceSettings().setRotation(rotation).setIgnoreEntities(false), random, FLAGS);
		excludeIn(level, pos, t, true);
	}

	/** Mobs saved with a piece or room: Nice Mob Manager leaves them alone (tagged at once, before it sees them). */
	private static void excludeIn(ServerLevel level, BlockPos at, StructureTemplate t, boolean turned) {
		var size = t.getSize();
		int w = turned ? Math.max(size.getX(), size.getZ()) : size.getX(), d = turned ? w : size.getZ();
		net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(at.getX() - w, at.getY() - 1, at.getZ() - d, at.getX() + w + 1, at.getY() + size.getY() + 1, at.getZ() + d + 1);
		for (Entity e : level.getEntitiesOfClass(Entity.class, box, e -> !(e instanceof Player))) e.addTag(MobLines.NMM_EXCLUDE);
	}

	/** Runs a job on the files worker. */
	static <T> CompletableFuture<T> onWorker(java.util.function.Supplier<T> job) {
		return CompletableFuture.supplyAsync(job, IO);
	}

	static void delete(MinecraftServer server, String name) {
		Path target = dir(server, name);
		IO.execute(() -> {
			try {
				deleteTree(target);
			} catch (IOException e) {
				NiceControlCenter.LOGGER.warn("Dungeons: could not remove the dungeon files {}: {}", target, e.getMessage());
			}
		});
	}

	private static void deleteTree(Path p) throws IOException {
		if (!Files.exists(p)) return;
		try (Stream<Path> s = Files.walk(p)) {
			for (Path f : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(f);
		}
	}

	// ---------------------------------------------------------------- loading and pasting

	/** Reads and parses every piece on the worker. */
	static CompletableFuture<List<Piece>> load(MinecraftServer server, Dungeons.Dungeon d) {
		Path folder = dir(server, d.name);
		var blocks = server.registryAccess().lookupOrThrow(Registries.BLOCK);
		return CompletableFuture.supplyAsync(() -> {
			List<Piece> out = new ArrayList<>();
			try (Stream<Path> files = Files.list(folder)) {
				for (Path f : files.filter(x -> x.getFileName().toString().endsWith(".nbt")).sorted().toList()) {
					String[] xz = f.getFileName().toString().replace(".nbt", "").split("_");
					StructureTemplate t = new StructureTemplate();
					t.load(blocks, NbtIo.readCompressed(f, NbtAccounter.unlimitedHeap()));
					out.add(new Piece(Integer.parseInt(xz[0]), Integer.parseInt(xz[1]), t));
				}
			} catch (IOException | RuntimeException e) {
				throw new IllegalStateException("The dungeon " + d.name + " can't be read: " + e.getMessage(), e);
			}
			if (out.isEmpty()) throw new IllegalStateException("The dungeon " + d.name + " has no saved blocks: /dungeon save " + d.name);
			return out;
		}, IO);
	}

	/** Places one piece with the box's lowest corner at origin (server thread). */
	static void paste(ServerLevel level, BlockPos origin, Piece p, RandomSource random) {
		BlockPos at = origin.offset(p.dx(), 0, p.dz());
		p.template().placeInWorld(level, at, at, new StructurePlaceSettings().setIgnoreEntities(false), random, FLAGS);
		excludeIn(level, at, p.template(), false);
	}

	/** Sets one piece of a box to air and removes its entities, players aside (server thread). */
	static void wipe(ServerLevel level, BlockPos min, BlockPos max, int dx, int dz) {
		int x0 = min.getX() + dx, z0 = min.getZ() + dz;
		int x1 = Math.min(max.getX(), x0 + Dungeons.PIECE - 1), z1 = Math.min(max.getZ(), z0 + Dungeons.PIECE - 1);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				for (int y = min.getY(); y <= max.getY(); y++) {
					pos.set(x, y, z);
					if (!level.getBlockState(pos).isAir()) level.setBlock(pos, air, FLAGS);
				}
			}
		}
		AABB box = new AABB(x0, min.getY() - 2, z0, x1 + 1, max.getY() + 3, z1 + 1);
		for (Entity e : level.getEntitiesOfClass(Entity.class, box, e -> !(e instanceof Player))) e.discard();
	}
}
