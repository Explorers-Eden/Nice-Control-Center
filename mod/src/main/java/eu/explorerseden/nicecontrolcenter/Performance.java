package eu.explorerseden.nicecontrolcenter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Performance settings in the spirit of Paper and ServerCore, kept in config/nicecontrolcenter-performance.json:
 * dynamic distances while the server lags, entity activation range, merge radius, breeding caps, villager
 * brains, frozen chunks. Written by the panel, read by the mod when the file changes; Minecraft-free so
 * both compile it. Everything is off until switched on.
 */
public final class Performance {
	public static final String FILE = "nicecontrolcenter-performance.json";

	public static final class Dynamic {
		public boolean enabled = false;
		/** Steps down while the average tick takes longer than this (ms; 50 = the server can't keep up). */
		public int targetMspt = 40;
		/** Steps back up after a minute below this. */
		public int recoverMspt = 30;
		public int simulationMin = 5;
		public int viewMin = 6;
		/** Lowest mob cap in percent of vanilla. */
		public int mobCapMin = 50;
		/** Also lower the view distance (players notice it more), after simulation distance and mob caps. */
		public boolean view = true;
	}

	public static final class Activation {
		public boolean enabled = false;
		/** Blocks from the nearest player; farther away an entity only ticks every {@link #every} ticks. */
		public int monsters = 32;
		public int animals = 32;
		public int villagers = 32;
		public int other = 16;
		public int every = 4;
	}

	public static final class Merge {
		public boolean enabled = false;
		/** Vanilla merges within 0.5 blocks. */
		public double items = 2.5;
		public double xp = 4;
	}

	public static final class Breeding {
		public boolean enabled = false;
		/** No breeding while this many of the same kind live within {@link #radius} blocks. */
		public int animals = 32;
		public int villagers = 16;
		public int radius = 16;
	}

	public static final class Villagers {
		/** Villagers that can't move (trading cells) think only every {@link #every} ticks. */
		public boolean lobotomize = false;
		public int every = 20;
	}

	public static final class Chunk {
		public String dimension;
		public int x;
		public int z;

		public Chunk() {
		}

		public Chunk(String dimension, int x, int z) {
			this.dimension = dimension;
			this.x = x;
			this.z = z;
		}
	}

	public static final class Settings {
		public Dynamic dynamic = new Dynamic();
		public Activation activation = new Activation();
		public Merge merge = new Merge();
		public Breeding breeding = new Breeding();
		public Villagers villagers = new Villagers();
		/** Chunks whose entities and block entities don't tick. */
		public List<Chunk> frozen = new ArrayList<>();

		/** Missing parts (older file, partial JSON) get their defaults. */
		public Settings fill() {
			if (dynamic == null) dynamic = new Dynamic();
			if (activation == null) activation = new Activation();
			if (merge == null) merge = new Merge();
			if (breeding == null) breeding = new Breeding();
			if (villagers == null) villagers = new Villagers();
			if (frozen == null) frozen = new ArrayList<>();
			frozen.removeIf(c -> c == null || c.dimension == null || c.dimension.isBlank());
			return this;
		}
	}

	private Performance() {
	}

	/** Returns an error, or null. */
	public static String validate(Settings s) {
		if (s == null) return "No settings.";
		s.fill();
		Dynamic d = s.dynamic;
		if (d.targetMspt < 5 || d.targetMspt > 200) return "The target tick time must be between 5 and 200 ms.";
		if (d.recoverMspt < 1 || d.recoverMspt >= d.targetMspt) return "The recover tick time must be below the target.";
		if (d.simulationMin < 2 || d.simulationMin > 32) return "The lowest simulation distance must be between 2 and 32.";
		if (d.viewMin < 2 || d.viewMin > 32) return "The lowest view distance must be between 2 and 32.";
		if (d.mobCapMin < 10 || d.mobCapMin > 100) return "The lowest mob cap must be between 10 and 100 %.";
		Activation a = s.activation;
		for (int r : new int[] { a.monsters, a.animals, a.villagers, a.other }) {
			if (r < 8 || r > 256) return "Activation ranges must be between 8 and 256 blocks.";
		}
		if (a.every < 2 || a.every > 40) return "Inactive entities tick every 2 to 40 ticks.";
		if (s.merge.items < 0.5 || s.merge.items > 8 || s.merge.xp < 0.5 || s.merge.xp > 8) return "Merge radii must be between 0.5 and 8 blocks.";
		Breeding b = s.breeding;
		if (b.animals < 2 || b.animals > 1000 || b.villagers < 2 || b.villagers > 1000) return "Breeding caps must be between 2 and 1000.";
		if (b.radius < 4 || b.radius > 128) return "The breeding radius must be between 4 and 128 blocks.";
		if (s.villagers.every < 2 || s.villagers.every > 100) return "Trapped villagers think every 2 to 100 ticks.";
		if (s.frozen.size() > 500) return "At most 500 frozen chunks.";
		return null;
	}

	/** The settings in the file; defaults when it's missing or unreadable. */
	public static Settings read(Path file) {
		try {
			if (!Files.exists(file)) return new Settings();
			Settings s = Json.GSON.fromJson(Files.readString(file), Settings.class);
			if (s == null || validate(s) != null) return new Settings();
			return s;
		} catch (IOException | RuntimeException e) {
			return new Settings();
		}
	}

	public static void write(Path file, Settings settings) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, Json.GSON.newBuilder().setPrettyPrinting().create().toJson(settings), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
