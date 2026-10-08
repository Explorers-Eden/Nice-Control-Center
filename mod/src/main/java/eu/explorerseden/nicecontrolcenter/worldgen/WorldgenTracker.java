package eu.explorerseden.nicecontrolcenter.worldgen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.Structure;

import eu.explorerseden.nicecontrolcenter.core.Tracker;
import eu.explorerseden.nicecontrolcenter.data.Breakdown;

/**
 * Time spent generating new chunks, per stage, placed feature and structure.
 *
 * <p>World generation runs on worker threads, in parallel, so everything here is thread-safe and
 * each thread keeps its own frame stack (features can place other features; each gets its self
 * time). This is worker time: it slows down chunk loading for players, not the tick itself.
 */
public final class WorldgenTracker {
	public static final String STAGE_STRUCTURE_PLANNING = "Structure planning";
	public static final String STAGE_TERRAIN = "Terrain shape";
	public static final String STAGE_SURFACE = "Surface";
	public static final String STAGE_CARVERS = "Caves and ravines";
	public static final String STAGE_DECORATION = "Features and structures";

	/** Labels for a timed frame: a stage, a feature or a structure. */
	private static final int STAGE = 0;
	private static final int FEATURE = 1;
	private static final int STRUCTURE = 2;

	private static final class Acc {
		final LongAdder ns = new LongAdder();
		final LongAdder count = new LongAdder();
	}

	private static final class Bucket {
		final ConcurrentHashMap<String, Acc> stages = new ConcurrentHashMap<>();
		final ConcurrentHashMap<String, Acc> features = new ConcurrentHashMap<>();
		final ConcurrentHashMap<String, Acc> structures = new ConcurrentHashMap<>();
		final AtomicLong chunks = new AtomicLong();
	}

	/** Where a new chunk was generated, for crediting it to the nearest player. */
	public record NewChunk(String dimension, int x, int z) {
	}

	private static final class Frames {
		long[] start = new long[32];
		long[] child = new long[32];
		int depth;
	}

	private static volatile Bucket bucket = new Bucket();
	private static final ConcurrentLinkedQueue<NewChunk> newChunks = new ConcurrentLinkedQueue<>();
	private static final AtomicLong queued = new AtomicLong();
	private static final ThreadLocal<Frames> FRAMES = ThreadLocal.withInitial(Frames::new);
	private static final Map<PlacedFeature, String> featureIds = Collections.synchronizedMap(new IdentityHashMap<>());
	private static final Map<Structure, String> structureIds = Collections.synchronizedMap(new IdentityHashMap<>());

	private WorldgenTracker() {
	}

	public static boolean enabled() {
		return Tracker.enabled() && Tracker.serverThread() != null;
	}

	public static void enter() {
		Frames f = FRAMES.get();
		int d = f.depth++;
		if (d >= f.start.length) {
			f.start = java.util.Arrays.copyOf(f.start, d * 2);
			f.child = java.util.Arrays.copyOf(f.child, d * 2);
		}
		f.child[d] = 0;
		f.start[d] = System.nanoTime();
	}

	public static void exitStage(String stage) {
		exit(STAGE, stage);
	}

	public static void exitFeature(PlacedFeature feature, RegistryAccess registries) {
		exit(FEATURE, featureId(feature, registries));
	}

	public static void exitStructure(Structure structure, RegistryAccess registries) {
		exit(STRUCTURE, structureId(structure, registries));
	}

	private static void exit(int kind, String key) {
		long now = System.nanoTime();
		Frames f = FRAMES.get();
		int d = --f.depth;
		if (d < 0) {
			f.depth = 0;
			return;
		}
		long elapsed = now - f.start[d];
		if (d > 0) {
			f.child[d - 1] += elapsed;
		}
		long self = elapsed - f.child[d];
		Bucket b = bucket;
		Map<String, Acc> map = kind == STAGE ? b.stages : kind == FEATURE ? b.features : b.structures;
		Acc acc = map.computeIfAbsent(key, k -> new Acc());
		// Stages report their full time (features nested inside are shown separately as a breakdown of it).
		acc.ns.add(kind == STAGE ? elapsed : self);
		acc.count.increment();
	}

	public static void newChunk(String dimension, int x, int z) {
		bucket.chunks.incrementAndGet();
		if (queued.incrementAndGet() < 50_000) {
			newChunks.add(new NewChunk(dimension, x, z));
		} else {
			queued.decrementAndGet();
		}
	}

	/** Takes the chunks generated since the last call (player view). */
	public static List<NewChunk> drainNewChunks() {
		List<NewChunk> result = new ArrayList<>();
		NewChunk chunk;
		while ((chunk = newChunks.poll()) != null) {
			queued.decrementAndGet();
			result.add(chunk);
		}
		return result;
	}

	/** Hands over the last second's numbers. */
	public static Breakdown.Worldgen drain() {
		Bucket b = bucket;
		bucket = new Bucket();
		Breakdown.Worldgen w = new Breakdown.Worldgen();
		w.chunks = b.chunks.get();
		copy(b.stages, w.stages);
		copy(b.features, w.features);
		copy(b.structures, w.structures);
		return w;
	}

	private static void copy(Map<String, Acc> from, Map<String, Breakdown.Timing> to) {
		from.forEach((key, acc) -> {
			Breakdown.Timing t = new Breakdown.Timing();
			t.ns = acc.ns.sum();
			t.count = acc.count.sum();
			to.put(key, t);
		});
	}

	private static String featureId(PlacedFeature feature, RegistryAccess registries) {
		String id = featureIds.get(feature);
		if (id == null) {
			id = lookup(registries.lookupOrThrow(Registries.PLACED_FEATURE), feature, "(inline feature)");
			featureIds.put(feature, id);
		}
		return id;
	}

	private static String structureId(Structure structure, RegistryAccess registries) {
		String id = structureIds.get(structure);
		if (id == null) {
			id = lookup(registries.lookupOrThrow(Registries.STRUCTURE), structure, "(unnamed structure)");
			structureIds.put(structure, id);
		}
		return id;
	}

	private static <T> String lookup(Registry<T> registry, T value, String fallback) {
		Identifier key = registry.getKey(value);
		return key == null ? fallback : key.toString();
	}

	/** Registries change on /reload; forget cached ids. */
	public static void clearCaches() {
		featureIds.clear();
		structureIds.clear();
	}
}
