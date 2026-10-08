package eu.explorerseden.nicecontrolcenter.panel.world;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Draws a region file as a 512×512 top-down image, like a vanilla map at one pixel per block: the map
 * color of the top block, lighter or darker than the block to the north for relief, water darker with
 * depth. In dimensions with a ceiling (the Nether) it looks below the roof.
 */
public final class MapRenderer {
	public static final int TILE = 512;
	private static final Set<String> WATER = Set.of("minecraft:water", "minecraft:bubble_column", "minecraft:kelp", "minecraft:kelp_plant",
			"minecraft:seagrass", "minecraft:tall_seagrass");
	private static final int UNKNOWN = 0x7F7F7F;
	private static final int WATER_COLOR = 0x4040FF;
	private static final int HIGH = 255;
	private static final int NORMAL = 220;
	private static final int LOW = 180;

	private final Map<String, Integer> colors;

	public MapRenderer(Map<String, Integer> colors) {
		this.colors = colors;
	}

	/** The rendered region, or null when it has no finished chunks. */
	public BufferedImage render(Path regionFile, boolean ceiling) throws IOException {
		int[] height = new int[TILE * TILE];
		int[] color = new int[TILE * TILE];
		int[] depth = new int[TILE * TILE];
		byte[] kind = new byte[TILE * TILE]; // 0 nothing, 1 land, 2 water
		boolean any = false;
		try (RegionFile region = new RegionFile(regionFile)) {
			for (int cz = 0; cz < 32; cz++) {
				for (int cx = 0; cx < 32; cx++) {
					if (!region.has(cx, cz)) continue;
					Map<String, Object> chunk;
					try {
						chunk = region.read(cx, cz);
					} catch (IOException | RuntimeException e) {
						continue; // Being written right now, or damaged: next pass.
					}
					if (chunk == null || !finished(chunk)) continue;
					if (column(chunk, cx, cz, ceiling, height, color, depth, kind)) any = true;
				}
			}
		}
		if (!any) return null;
		BufferedImage image = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
		int[] out = new int[TILE * TILE];
		for (int z = 0; z < TILE; z++) {
			for (int x = 0; x < TILE; x++) {
				int i = z * TILE + x;
				if (kind[i] == 0) continue;
				int shade;
				if (kind[i] == 2) {
					double d = depth[i] * 0.1 + ((x + z) & 1) * 0.2;
					shade = d < 0.5 ? HIGH : d > 0.9 ? LOW : NORMAL;
				} else {
					int north = z > 0 && kind[i - TILE] != 0 ? height[i - TILE] : height[i];
					shade = height[i] > north ? HIGH : height[i] < north ? LOW : NORMAL;
				}
				int c = color[i];
				int r = ((c >> 16) & 0xFF) * shade / 255;
				int g = ((c >> 8) & 0xFF) * shade / 255;
				int b = (c & 0xFF) * shade / 255;
				out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
			}
		}
		image.setRGB(0, 0, TILE, TILE, out, 0, TILE);
		return image;
	}

	private static boolean finished(Map<String, Object> chunk) {
		String status = Nbt.string(chunk, "Status");
		return status == null || status.endsWith("full") || status.endsWith("spawn") || status.endsWith("heightmaps") || status.endsWith("light");
	}

	/** Fills the 16×16 columns of one chunk. */
	private boolean column(Map<String, Object> chunk, int cx, int cz, boolean ceiling, int[] height, int[] color, int[] depth, byte[] kind) {
		Map<Integer, Section> sections = new HashMap<>();
		int minSection = Integer.MAX_VALUE;
		int maxSection = Integer.MIN_VALUE;
		for (Object o : Nbt.list(chunk, "sections")) {
			if (!(o instanceof Map<?, ?> raw)) continue;
			@SuppressWarnings("unchecked")
			Map<String, Object> s = (Map<String, Object>) raw;
			Section section = Section.of(s);
			if (section == null) continue;
			sections.put(section.y, section);
			minSection = Math.min(minSection, section.y);
			maxSection = Math.max(maxSection, section.y);
		}
		if (sections.isEmpty()) return false;
		int minY = Nbt.integer(chunk, "yPos", minSection) * 16;
		int maxY = maxSection * 16 + 15;
		long[] surface = null;
		Map<String, Object> heightmaps = Nbt.compound(chunk, "Heightmaps");
		if (!ceiling && heightmaps != null && heightmaps.get("WORLD_SURFACE") instanceof long[] h && h.length > 0) surface = h;
		boolean drew = false;
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int top;
				if (surface != null) {
					int bits = 64 / (int) Math.ceil(256.0 / surface.length);
					int perLong = 64 / bits;
					int idx = z * 16 + x;
					long v = (surface[idx / perLong] >>> ((idx % perLong) * bits)) & ((1L << bits) - 1);
					top = (int) v + minY - 1;
				} else if (ceiling) {
					// Below the roof: from y 120 go down to the first air, then to the first block below it.
					int y = Math.min(maxY, 120);
					while (y > minY && !air(block(sections, x, y, z))) y--;
					while (y > minY && air(block(sections, x, y, z))) y--;
					top = y;
				} else {
					int y = maxY;
					while (y > minY && air(block(sections, x, y, z))) y--;
					top = y;
				}
				if (top < minY) continue;
				// See-through blocks (glass, air) let the map look further down, like vanilla maps.
				int y = top;
				String name = block(sections, x, y, z);
				int steps = 0;
				while (y > minY && steps < 64 && colorOf(name) == 0 && !WATER.contains(name)) {
					y--;
					steps++;
					name = block(sections, x, y, z);
				}
				int i = (cz * 16 + z) * TILE + cx * 16 + x;
				if (WATER.contains(name)) {
					int d = 0;
					int wy = y;
					while (wy > minY && d < 32 && WATER.contains(block(sections, x, wy, z))) {
						wy--;
						d++;
					}
					kind[i] = 2;
					depth[i] = d;
					color[i] = WATER_COLOR;
				} else {
					int c = colorOf(name);
					if (c == 0) continue;
					kind[i] = 1;
					color[i] = c;
				}
				height[i] = y;
				drew = true;
			}
		}
		return drew;
	}

	private int colorOf(String name) {
		if (name == null || air(name)) return 0;
		Integer c = colors.get(name);
		return c == null ? UNKNOWN : c;
	}

	private static boolean air(String name) {
		return name == null || name.equals("minecraft:air") || name.equals("minecraft:cave_air") || name.equals("minecraft:void_air");
	}

	private static String block(Map<Integer, Section> sections, int x, int y, int z) {
		Section s = sections.get(Math.floorDiv(y, 16));
		return s == null ? "minecraft:air" : s.get(x, y & 15, z);
	}

	/** One 16×16×16 section's block palette and packed indices. */
	private static final class Section {
		final int y;
		final String[] palette;
		final long[] data;
		final int bits;

		private Section(int y, String[] palette, long[] data) {
			this.y = y;
			this.palette = palette;
			this.data = data;
			this.bits = data == null ? 0 : Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.length - 1));
		}

		static Section of(Map<String, Object> s) {
			Object yObj = s.get("Y");
			if (!(yObj instanceof Number yNum)) return null;
			Map<String, Object> states = Nbt.compound(s, "block_states");
			if (states == null) return null;
			List<?> pal = Nbt.list(states, "palette");
			if (pal.isEmpty()) return null;
			String[] palette = new String[pal.size()];
			for (int i = 0; i < pal.size(); i++) {
				// Up to 1.21 a palette entry is {Name, Properties}. Since 26.1 it's a plain string, or, when any entry in
				// the section has properties, {id, properties} with the others wrapped as {"": name}.
				Object entry = pal.get(i);
				String name = "minecraft:air";
				if (entry instanceof String str) name = str;
				else if (entry instanceof Map<?, ?> m) {
					Object n = m.get("id") != null ? m.get("id") : m.get("") != null ? m.get("") : m.get("Name");
					if (n instanceof String str) name = str;
				}
				int bracket = name.indexOf('[');
				palette[i] = bracket < 0 ? name : name.substring(0, bracket);
			}
			long[] data = states.get("data") instanceof long[] d ? d : null;
			return new Section(yNum.intValue(), palette, palette.length == 1 ? null : data);
		}

		String get(int x, int y, int z) {
			if (data == null) return palette[0];
			int index = (y * 16 + z) * 16 + x;
			int perLong = 64 / bits;
			int li = index / perLong;
			if (li >= data.length) return palette[0];
			int p = (int) ((data[li] >>> ((index % perLong) * bits)) & ((1L << bits) - 1));
			return p < palette.length ? palette[p] : palette[0];
		}
	}

	// ── Files ──────────────────────────────────────────────────────────────

	public static void write(BufferedImage image, Path target) throws IOException {
		Files.createDirectories(target.getParent());
		Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
		ImageIO.write(image, "png", tmp.toFile());
		Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/** A zoomed-out tile from the four tiles one level closer (missing ones stay transparent). */
	public static BufferedImage zoomOut(BufferedImage nw, BufferedImage ne, BufferedImage sw, BufferedImage se) {
		BufferedImage out = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		int half = TILE / 2;
		if (nw != null) g.drawImage(nw, 0, 0, half, half, null);
		if (ne != null) g.drawImage(ne, half, 0, half, half, null);
		if (sw != null) g.drawImage(sw, 0, half, half, half, null);
		if (se != null) g.drawImage(se, half, half, half, half, null);
		g.dispose();
		return out;
	}
}
