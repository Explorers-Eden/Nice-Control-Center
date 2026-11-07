package eu.explorerseden.nicecontrolcenter.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.reflect.TypeToken;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.level.storage.LevelResource;

import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;

/**
 * Fabled Roots (Explorer's Eden's race and class data pack) is paused in Flying Fair and the dungeon dimension, so
 * everyone plays the games on equal terms: players there get the tag {@code fabled_roots.paused} (the data pack skips
 * their race and class effects and abilities), and the base attributes their race set (armour, jump, gravity…; their
 * size stays)
 * plus its attack-speed modifiers are put back to vanilla, saved, and restored when they leave. The saved values are
 * kept in a file, so a restart in between loses nothing.
 */
public final class FabledPause {
	public static final String TAG = "fabled_roots.paused";
	private static final Set<String> DIMENSIONS = Set.of("eden:flying_fair", eu.explorerseden.nicecontrolcenter.Dungeons.DIMENSION);
	/** What its races and classes set as base values (waypoint ranges and the player's size left alone). */
	private static final List<String> ATTRIBUTES = List.of("armor", "armor_toughness", "attack_damage", "attack_knockback", "attack_speed", "block_break_speed",
			"block_interaction_range", "burning_time", "fall_damage_multiplier", "gravity", "jump_strength", "knockback_resistance", "movement_speed", "oxygen_bonus",
			"safe_fall_distance", "sneaking_speed", "submerged_mining_speed");

	/** Per player: attribute → [base, then its fabled_roots modifiers as id, amount, operation …]. */
	private static Map<String, Map<String, List<String>>> saved = new HashMap<>();
	private static Path file;

	private FabledPause() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			file = server.getWorldPath(LevelResource.ROOT).resolve("nicecontrolcenter-fabled-paused.json");
			try {
				if (Files.exists(file)) {
					Map<String, Map<String, List<String>>> read = Json.GSON.fromJson(Files.readString(file), new TypeToken<Map<String, Map<String, List<String>>>>() {
					}.getType());
					if (read != null) saved = read;
				}
			} catch (IOException | RuntimeException e) {
				NiceControlCenter.LOGGER.warn("Fabled Roots: paused players' attributes couldn't be read: {}", e.toString());
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 20 == 7) tick(server);
		});
	}

	private static void tick(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			boolean there = DIMENSIONS.contains(p.level().dimension().identifier().toString());
			boolean paused = p.entityTags().contains(TAG);
			if (there && !paused) pause(p);
			else if (!there && paused) resume(p);
		}
	}

	private static Holder<Attribute> attribute(String name) {
		return BuiltInRegistries.ATTRIBUTE.get(Identifier.withDefaultNamespace(name)).map(h -> (Holder<Attribute>) h).orElse(null);
	}

	/** Into a game dimension: the race's values saved, vanilla ones set, the data pack told to skip them. */
	private static void pause(ServerPlayer p) {
		Map<String, List<String>> mine = new HashMap<>();
		for (String name : ATTRIBUTES) {
			Holder<Attribute> a = attribute(name);
			AttributeInstance inst = a == null ? null : p.getAttribute(a);
			if (inst == null) continue;
			List<String> keep = new ArrayList<>();
			keep.add(String.valueOf(inst.getBaseValue()));
			for (AttributeModifier m : List.copyOf(inst.getModifiers())) {
				if (!m.id().getNamespace().equals("fabled_roots")) continue;
				keep.add(m.id().toString());
				keep.add(String.valueOf(m.amount()));
				keep.add(m.operation().name());
				inst.removeModifier(m.id());
			}
			inst.setBaseValue(a.value().getDefaultValue());
			mine.put(name, keep);
		}
		// (Nothing to keep from an earlier pause that wasn't restored: that one wins.)
		saved.putIfAbsent(p.getStringUUID(), mine);
		p.addTag(TAG);
		save();
	}

	/** Out again: the race's values back. */
	private static void resume(ServerPlayer p) {
		p.removeTag(TAG);
		Map<String, List<String>> mine = saved.remove(p.getStringUUID());
		if (mine == null) return;
		for (Map.Entry<String, List<String>> e : mine.entrySet()) {
			Holder<Attribute> a = attribute(e.getKey());
			AttributeInstance inst = a == null ? null : p.getAttribute(a);
			List<String> keep = e.getValue();
			if (inst == null || keep.isEmpty()) continue;
			try {
				inst.setBaseValue(Double.parseDouble(keep.get(0)));
				for (int i = 1; i + 2 < keep.size(); i += 3) {
					Identifier id = Identifier.parse(keep.get(i));
					if (inst.getModifier(id) != null) continue;
					inst.addPermanentModifier(new AttributeModifier(id, Double.parseDouble(keep.get(i + 1)), AttributeModifier.Operation.valueOf(keep.get(i + 2))));
				}
			} catch (RuntimeException ex) {
				NiceControlCenter.LOGGER.warn("Fabled Roots: {}'s {} couldn't be restored: {}", p.getGameProfile().name(), e.getKey(), ex.toString());
			}
		}
		save();
	}

	private static void save() {
		if (file == null) return;
		try {
			Files.writeString(file, Json.GSON.toJson(saved));
		} catch (IOException e) {
			NiceControlCenter.LOGGER.warn("Fabled Roots: paused players' attributes couldn't be saved: {}", e.toString());
		}
	}
}
