package eu.explorerseden.nicecontrolcenter.tweaks;

import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.Tweaks;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies config/nicecontrolcenter-tweaks.json (see {@link Tweaks}): read again when it changes, with the
 * id and #tag lists turned into matchers once, since they're checked for every block a player breaks.
 */
public final class TweaksControl {
	private static volatile Tweaks.Settings settings = new Tweaks.Settings().fill();
	private static volatile Matchers matchers = new Matchers(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
	private static long modified = -1;

	/** A list of ids and #tags. */
	public record Match<T>(List<Identifier> ids, List<TagKey<T>> tags) {
		boolean isEmpty() {
			return ids.isEmpty() && tags.isEmpty();
		}
	}

	record Matchers(List<Match<Item>> fellTools, List<Match<Item>> fellDenied, List<Match<Block>> logs, List<Match<Block>> logsDenied,
			List<Match<Block>> leaves, List<Match<Item>> veinTools, List<Match<Item>> veinDenied, List<Match<Block>> ores, List<Match<Block>> oresDenied,
			List<Match<Item>> saplings, List<Match<Block>> saplingGround, List<Match<Item>> spawnerTools) {
	}

	private TweaksControl() {
	}

	public static Tweaks.Settings settings() {
		return settings;
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(Tweaks.FILE);
	}

	public static void register() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(s -> {
			if (modified < 0 || s.getTickCount() % 100 == 0) reload();
			Felling.tick(s);
			TreeFall.tick(s);
			ToolWear.tick();
			AfkTabList.tick(s);
			// AFK players can turn AFK while others sleep; the game only counts sleepers again when someone gets in or out of bed.
			if (settings.sleep.ignoreAfk && s.getTickCount() % 20 == 0) {
				for (var level : s.getAllLevels()) {
					if (level.players().stream().anyMatch(p -> p.isSleeping())) level.updateSleepingPlayerList();
				}
			}
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(s -> modified = -1);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(s -> TreeFall.finishAll());
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((e, level) -> {
			if (TreeFall.leftOver(e)) e.discard();
		});
		Felling.register();
		Spawners.register();
		BoneMeal.register();
		Mentions.register();
		BetterMending.register();
	}

	/** Reads the file again if it changed (also called from the dashboard API, which works while the server is paused). */
	public static void reload() {
		Path f = file();
		long now;
		try {
			now = eu.explorerseden.nicecontrolcenter.core.FileWatch.modified(f);
		} catch (IOException e) {
			return;
		}
		if (now == modified) return;
		boolean first = modified < 0;
		modified = now;
		Tweaks.Settings next = Tweaks.read(f);
		settings = next;
		matchers = new Matchers(items(next.felling.tools), items(next.felling.deniedTools), blocks(next.felling.logs), blocks(next.felling.deniedLogs),
				blocks(next.felling.leaves), items(next.veins.tools), items(next.veins.deniedTools), blocks(next.veins.blocks), blocks(next.veins.deniedBlocks),
				items(next.saplings.items), blocks(next.saplings.ground), items(next.spawners.tools));
		AntiXray.rebuild(next, blocks(next.antiXray.blocks));
		if (!first) NiceControlCenter.LOGGER.info("Tweaks: reloaded the gameplay tweaks");
	}

	private static List<Match<Item>> items(List<String> list) {
		List<Identifier> ids = new ArrayList<>();
		List<TagKey<Item>> tags = new ArrayList<>();
		for (String s : list) {
			Identifier id = Identifier.tryParse(s.startsWith("#") ? s.substring(1) : s);
			if (id == null) continue;
			if (s.startsWith("#")) tags.add(TagKey.create(Registries.ITEM, id));
			else ids.add(id);
		}
		return List.of(new Match<>(ids, tags));
	}

	private static List<Match<Block>> blocks(List<String> list) {
		List<Identifier> ids = new ArrayList<>();
		List<TagKey<Block>> tags = new ArrayList<>();
		for (String s : list) {
			Identifier id = Identifier.tryParse(s.startsWith("#") ? s.substring(1) : s);
			if (id == null) continue;
			if (s.startsWith("#")) tags.add(TagKey.create(Registries.BLOCK, id));
			else ids.add(id);
		}
		return List.of(new Match<>(ids, tags));
	}

	private static boolean item(List<Match<Item>> list, ItemStack stack) {
		for (Match<Item> m : list) {
			for (TagKey<Item> t : m.tags()) if (stack.is(t)) return true;
			if (!m.ids().isEmpty() && m.ids().contains(BuiltInRegistries.ITEM.getKey(stack.getItem()))) return true;
		}
		return false;
	}

	static boolean block(List<Match<Block>> list, BlockState state) {
		for (Match<Block> m : list) {
			for (TagKey<Block> t : m.tags()) if (state.is(t)) return true;
			if (!m.ids().isEmpty() && m.ids().contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()))) return true;
		}
		return false;
	}

	static boolean fellingTool(ItemStack tool) {
		Matchers m = matchers;
		return item(m.fellTools(), tool) && !item(m.fellDenied(), tool);
	}

	static boolean isLog(BlockState state) {
		Matchers m = matchers;
		return block(m.logs(), state) && !block(m.logsDenied(), state);
	}

	static boolean isLeaves(BlockState state) {
		return block(matchers.leaves(), state);
	}

	static boolean veinTool(ItemStack tool) {
		Matchers m = matchers;
		return item(m.veinTools(), tool) && !item(m.veinDenied(), tool);
	}

	static boolean spawnerTool(ItemStack tool) {
		return item(matchers.spawnerTools(), tool);
	}

	static boolean isReplantable(ItemStack stack) {
		return item(matchers.saplings(), stack);
	}

	/** Whether a sapling may plant itself on this block (any block when the list is empty). */
	static boolean saplingGround(BlockState state) {
		return matchers.saplingGround().isEmpty() || block(matchers.saplingGround(), state);
	}

	static boolean isOre(BlockState state) {
		Matchers m = matchers;
		return block(m.ores(), state) && !block(m.oresDenied(), state);
	}

}
