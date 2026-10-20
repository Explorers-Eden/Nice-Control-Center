package eu.explorerseden.nicecontrolcenter.pets;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;

/**
 * Where players placed ore and natural stone, so the Miner perk only takes blocks from world generation. Kept
 * per chunk (saved with it) as a sorted list of positions; only those block kinds are recorded, so it stays
 * small. Blocks placed before this was added count as natural.
 */
public final class PlacedBlocks {
	private static final long[] NONE = new long[0];
	private static AttachmentType<long[]> type;

	private PlacedBlocks() {
	}

	static void register() {
		type = AttachmentRegistry.create(Identifier.fromNamespaceAndPath("nicecontrolcenter", "placed"),
				b -> b.persistent(Codec.LONG_STREAM.xmap(LongStream::toArray, Arrays::stream)));
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
			if (!(level instanceof ServerLevel server)) return;
			boolean natural = true;
			if (tracked(state)) {
				natural = !isPlaced(server, pos);
				forget(server, pos);
			}
			if (player instanceof ServerPlayer p) {
				PetPerks.mined(p, pos, state, natural);
				PetReactions.broke(p, state);
			}
		});
	}

	/**
	 * Vanilla's ore tags: minecraft:ores (26.3 has only that one), and the one per ore of 26.1 and 26.2. A tag
	 * this version doesn't have never matches.
	 */
	private static final List<TagKey<Block>> ORES = java.util.stream.Stream.of("ores", "coal_ores", "iron_ores", "copper_ores", "gold_ores",
			"redstone_ores", "lapis_ores", "diamond_ores", "emerald_ores").map(n -> TagKey.create(Registries.BLOCK, Identifier.withDefaultNamespace(n))).toList();

	/** Ore the Miner may take (vanilla's ore tags, nether quartz and ancient debris). */
	public static boolean isOre(BlockState s) {
		for (TagKey<Block> tag : ORES) if (s.is(tag)) return true;
		return s.is(Blocks.NETHER_QUARTZ_ORE) || s.is(Blocks.ANCIENT_DEBRIS);
	}

	/** Stone the world is made of (overworld and nether base stone, end stone). */
	public static boolean isStone(BlockState s) {
		return s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.BASE_STONE_NETHER) || s.is(Blocks.END_STONE);
	}

	static boolean tracked(BlockState s) {
		return isOre(s) || isStone(s);
	}

	/** A block was placed (any way: players, dispensers). */
	public static void placed(Level level, BlockPos pos, BlockState state) {
		if (type == null || !(level instanceof ServerLevel) || !tracked(state)) return;
		LevelChunk chunk = level.getChunkAt(pos);
		long[] list = chunk.getAttachedOrElse(type, NONE);
		long key = pos.asLong();
		int i = Arrays.binarySearch(list, key);
		if (i >= 0) return;
		int at = -i - 1;
		long[] grown = new long[list.length + 1];
		System.arraycopy(list, 0, grown, 0, at);
		grown[at] = key;
		System.arraycopy(list, at, grown, at + 1, list.length - at);
		chunk.setAttached(type, grown);
		chunk.markUnsaved();
	}

	public static boolean isPlaced(ServerLevel level, BlockPos pos) {
		if (type == null || !level.hasChunkAt(pos)) return false;
		long[] list = level.getChunkAt(pos).getAttached(type);
		return list != null && Arrays.binarySearch(list, pos.asLong()) >= 0;
	}

	/** The block there is gone (or never mind it). */
	public static void forget(ServerLevel level, BlockPos pos) {
		if (type == null || !level.hasChunkAt(pos)) return;
		LevelChunk chunk = level.getChunkAt(pos);
		long[] list = chunk.getAttached(type);
		if (list == null) return;
		int i = Arrays.binarySearch(list, pos.asLong());
		if (i < 0) return;
		if (list.length == 1) {
			chunk.removeAttached(type);
		} else {
			long[] shrunk = new long[list.length - 1];
			System.arraycopy(list, 0, shrunk, 0, i);
			System.arraycopy(list, i + 1, shrunk, i, list.length - i - 1);
			chunk.setAttached(type, shrunk);
		}
		chunk.markUnsaved();
	}
}
