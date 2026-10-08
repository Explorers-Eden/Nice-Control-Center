package eu.explorerseden.nicecontrolcenter.panel.world;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Minecraft's region files (r.X.Z.mca): 32×32 chunks, an 8 KiB header with each chunk's sector
 * offset and timestamp, then the compressed chunk data in 4 KiB sectors. Chunks too big for the file
 * live next to it as c.X.Z.mcc. Reading only; the world trimmer edits the header itself.
 */
public final class RegionFile implements AutoCloseable {
	public static final int SECTOR = 4096;
	private static final Pattern NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

	private final Path path;
	private final RandomAccessFile file;
	private final int[] offsets = new int[1024];
	private final int[] timestamps = new int[1024];
	public final int regionX;
	public final int regionZ;

	public RegionFile(Path path) throws IOException {
		this.path = path;
		Matcher m = NAME.matcher(path.getFileName().toString());
		if (!m.matches()) throw new IOException("Not a region file: " + path.getFileName());
		regionX = Integer.parseInt(m.group(1));
		regionZ = Integer.parseInt(m.group(2));
		file = new RandomAccessFile(path.toFile(), "r");
		if (file.length() >= 2L * SECTOR) {
			for (int i = 0; i < 1024; i++) offsets[i] = file.readInt();
			for (int i = 0; i < 1024; i++) timestamps[i] = file.readInt();
		}
	}

	/** Region coordinates from a file name, or null. */
	public static int[] coords(String fileName) {
		Matcher m = NAME.matcher(fileName);
		return m.matches() ? new int[] { Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) } : null;
	}

	public boolean has(int localX, int localZ) {
		return offsets[index(localX, localZ)] != 0;
	}

	public int timestamp(int localX, int localZ) {
		return timestamps[index(localX, localZ)];
	}

	static int index(int localX, int localZ) {
		return (localX & 31) + (localZ & 31) * 32;
	}

	/** The chunk's NBT, or null when it doesn't exist (or can't be read right now). */
	public Map<String, Object> read(int localX, int localZ) throws IOException {
		int entry = offsets[index(localX, localZ)];
		if (entry == 0) return null;
		long sector = (entry >>> 8) & 0xFFFFFF;
		int count = entry & 0xFF;
		if (sector < 2 || (sector + count) * SECTOR > file.length() + SECTOR) return null;
		file.seek(sector * SECTOR);
		int length = file.readInt();
		if (length <= 1 || length > count * SECTOR + SECTOR) return null;
		int type = file.readByte() & 0xFF;
		byte[] data;
		boolean external = (type & 0x80) != 0;
		if (external) {
			Path mcc = path.resolveSibling("c." + (regionX * 32 + (localX & 31)) + "." + (regionZ * 32 + (localZ & 31)) + ".mcc");
			if (!Files.exists(mcc)) return null;
			data = Files.readAllBytes(mcc);
			type &= 0x7F;
		} else {
			data = new byte[length - 1];
			file.readFully(data);
		}
		InputStream raw = new ByteArrayInputStream(data);
		InputStream in = switch (type) {
			case 1 -> new GZIPInputStream(raw);
			case 2 -> new InflaterInputStream(raw);
			case 3 -> raw;
			default -> null; // 4 = LZ4 (region-file-compression=lz4) isn't supported by the map yet.
		};
		if (in == null) return null;
		try (DataInputStream din = new DataInputStream(new java.io.BufferedInputStream(in, 1 << 15))) {
			return Nbt.read(din);
		}
	}

	@Override
	public void close() throws IOException {
		file.close();
	}
}
