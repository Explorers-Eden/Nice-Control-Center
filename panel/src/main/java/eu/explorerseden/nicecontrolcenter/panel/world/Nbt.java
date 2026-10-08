package eu.explorerseden.nicecontrolcenter.panel.world;

import java.io.DataInput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A small reader for Minecraft's NBT format: compounds become maps, lists become lists, numbers become
 * boxed numbers, arrays stay arrays. Enough for chunk data; nothing here writes NBT.
 */
public final class Nbt {
	private Nbt() {
	}

	/** Reads the root compound (a named tag of type 10). */
	public static Map<String, Object> read(DataInput in) throws IOException {
		byte type = in.readByte();
		if (type != 10) throw new IOException("Not an NBT compound (type " + type + ")");
		in.readUTF();
		@SuppressWarnings("unchecked")
		Map<String, Object> root = (Map<String, Object>) payload(in, type, 0);
		return root;
	}

	private static Object payload(DataInput in, byte type, int depth) throws IOException {
		if (depth > 512) throw new IOException("NBT nested too deeply");
		switch (type) {
			case 1 -> {
				return in.readByte();
			}
			case 2 -> {
				return in.readShort();
			}
			case 3 -> {
				return in.readInt();
			}
			case 4 -> {
				return in.readLong();
			}
			case 5 -> {
				return in.readFloat();
			}
			case 6 -> {
				return in.readDouble();
			}
			case 7 -> {
				byte[] a = new byte[checked(in.readInt())];
				in.readFully(a);
				return a;
			}
			case 8 -> {
				return in.readUTF();
			}
			case 9 -> {
				byte itemType = in.readByte();
				int n = checked(in.readInt());
				List<Object> list = new ArrayList<>(Math.min(n, 4096));
				for (int i = 0; i < n; i++) list.add(payload(in, itemType, depth + 1));
				return list;
			}
			case 10 -> {
				Map<String, Object> map = new HashMap<>();
				while (true) {
					byte t = in.readByte();
					if (t == 0) return map;
					String name = in.readUTF();
					map.put(name, payload(in, t, depth + 1));
				}
			}
			case 11 -> {
				int[] a = new int[checked(in.readInt())];
				for (int i = 0; i < a.length; i++) a[i] = in.readInt();
				return a;
			}
			case 12 -> {
				long[] a = new long[checked(in.readInt())];
				for (int i = 0; i < a.length; i++) a[i] = in.readLong();
				return a;
			}
			default -> throw new IOException("Unknown NBT tag type " + type);
		}
	}

	private static int checked(int n) throws IOException {
		if (n < 0 || n > 16_000_000) throw new IOException("Bad NBT length " + n);
		return n;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> compound(Map<String, Object> parent, String key) {
		Object v = parent == null ? null : parent.get(key);
		return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
	}

	public static List<?> list(Map<String, Object> parent, String key) {
		Object v = parent == null ? null : parent.get(key);
		return v instanceof List<?> l ? l : List.of();
	}

	public static int integer(Map<String, Object> parent, String key, int fallback) {
		Object v = parent == null ? null : parent.get(key);
		return v instanceof Number n ? n.intValue() : fallback;
	}

	public static String string(Map<String, Object> parent, String key) {
		Object v = parent == null ? null : parent.get(key);
		return v instanceof String s ? s : null;
	}
}
