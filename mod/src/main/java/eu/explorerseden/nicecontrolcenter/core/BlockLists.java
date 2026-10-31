package eu.explorerseden.nicecontrolcenter.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Block lists as the panel takes them: block ids and block tags, comma-separated ("minecraft:stone_bricks,
 * #minecraft:wool"); a tag stands for all its blocks, a block listed twice comes twice as often. Kept a minute
 * (tags can change with /reload).
 */
public final class BlockLists {
	private record Cached(List<Block> blocks, long at) {
	}

	private static final Map<String, Cached> cache = new ConcurrentHashMap<>();

	private BlockLists() {
	}

	/** The blocks of the list, tags spread out (air and unknown ids left out); empty when nothing's usable. */
	public static List<Block> of(String list) {
		String key = list == null ? "" : list;
		long now = System.currentTimeMillis();
		Cached c = cache.get(key);
		if (c != null && now - c.at() < 60_000) return c.blocks();
		List<Block> out = new ArrayList<>();
		for (String raw : key.split(",")) {
			String id = raw.strip();
			if (id.isEmpty()) continue;
			if (id.startsWith("#")) {
				Identifier tag = Identifier.tryParse(id.substring(1));
				if (tag == null) continue;
				for (Holder<Block> h : BuiltInRegistries.BLOCK.getTagOrEmpty(TagKey.create(Registries.BLOCK, tag))) {
					if (h.value() != Blocks.AIR) out.add(h.value());
				}
				continue;
			}
			Identifier i = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
			Block b = i == null ? null : BuiltInRegistries.BLOCK.getValue(i);
			if (b != null && b != Blocks.AIR) out.add(b);
		}
		List<Block> done = List.copyOf(out);
		cache.put(key, new Cached(done, now));
		return done;
	}

	/** The list's blocks, or the fallback alone when nothing in it is usable. */
	public static List<Block> of(String list, Block fallback) {
		List<Block> b = of(list);
		return b.isEmpty() ? List.of(fallback) : b;
	}

	/** Several entries (a list field) as one list. */
	public static List<Block> of(List<String> entries, Block fallback) {
		return of(entries == null ? "" : String.join(",", entries), fallback);
	}
}
