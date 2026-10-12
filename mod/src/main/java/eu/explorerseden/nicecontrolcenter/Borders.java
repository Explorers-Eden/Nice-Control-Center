package eu.explorerseden.nicecontrolcenter;

import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * World borders per dimension, kept in config/nicecontrolcenter-borders.json: inside the soft border is
 * the permanent area (never trimmed), between the soft and the hard border the outer wilds (trimmed on a
 * schedule by the panel), and nobody gets past the hard border (the mod pushes players back). Written by
 * the panel, read by the mod; Minecraft-free so both compile it.
 */
public final class Borders {
	public static final String FILE = "nicecontrolcenter-borders.json";
	public static final int MAX_RADIUS = 1_000_000;

	public static final class Border {
		/** "square" or "circle". */
		public String shape = "square";
		public int x;
		public int z;
		/** Radius of the permanent area. */
		public int soft;
		/** Radius nobody gets past; 0 = no hard border. */
		public int hard;
		/** Pregenerate this dimension (up to the hard border, or the soft one without). */
		public boolean pregen = true;
		/** A square hard border also sets the vanilla world border, so players see the wall. */
		public boolean worldBorder = true;
		/** Players near the soft border see it as a line of green particles (and a round hard border as red ones). */
		public boolean showSoft = true;

		/** How far pregeneration goes. */
		public int reach() {
			return hard > 0 ? hard : soft;
		}

		public boolean circle() {
			return "circle".equals(shape);
		}

		/** Whether a block position lies within the given radius around the center. */
		public boolean inside(double px, double pz, int radius) {
			double dx = px - x;
			double dz = pz - z;
			return circle() ? dx * dx + dz * dz <= (double) radius * radius : Math.abs(dx) <= radius && Math.abs(dz) <= radius;
		}

		/** Same, judged by the chunk's middle (like the trimmer and pregeneration). */
		public boolean insideChunk(int cx, int cz, int radius) {
			return inside(cx * 16 + 8, cz * 16 + 8, radius);
		}
	}

	private Borders() {
	}

	/** Returns an error, or null. */
	public static String validate(Border b) {
		if (b == null) return "No border.";
		if (!"square".equals(b.shape) && !"circle".equals(b.shape)) return "The shape is square or circle.";
		if (b.soft < 16 || b.soft > MAX_RADIUS) return "The soft border must be between 16 and 1,000,000 blocks.";
		if (b.hard != 0 && (b.hard < b.soft || b.hard > MAX_RADIUS)) return "The hard border must lie outside the soft border (or be 0 for none).";
		if (Math.abs((long) b.x) > 29_000_000 || Math.abs((long) b.z) > 29_000_000) return "The center must lie inside the world.";
		return null;
	}

	/** Dimension id → border; empty when the file is missing or unreadable. */
	public static Map<String, Border> read(Path file) {
		try {
			if (!Files.exists(file)) return new LinkedHashMap<>();
			Map<String, Border> map = Json.GSON.fromJson(Files.readString(file), new TypeToken<LinkedHashMap<String, Border>>() { }.getType());
			if (map == null) return new LinkedHashMap<>();
			map.values().removeIf(b -> validate(b) != null);
			return map;
		} catch (IOException | RuntimeException e) {
			return new LinkedHashMap<>();
		}
	}

	public static void write(Path file, Map<String, Border> borders) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, Json.GSON.newBuilder().setPrettyPrinting().create().toJson(borders), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
