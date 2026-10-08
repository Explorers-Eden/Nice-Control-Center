package eu.explorerseden.nicecontrolcenter.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.serialization.DataResult;

import net.minecraft.locale.Language;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;

import eu.explorerseden.nicecontrolcenter.NiceControlCenter;

/** Shows and changes gamerules. Changes apply at once, like /gamerule. Server thread only. */
public final class GameRuleEditor {
	/** One rule for the dashboard. */
	public record Rule(String id, String name, String description, String category, String type, String value, String defaultValue,
			Integer min, Integer max) {
	}

	private GameRuleEditor() {
	}

	public static List<Rule> rules(MinecraftServer server) {
		GameRules rules = server.overworld().getGameRules();
		Language language = Language.getInstance();
		List<Rule> result = new ArrayList<>();
		rules.availableRules().forEach(rule -> result.add(describe(rules, rule, language)));
		result.sort(Comparator.comparing(Rule::category).thenComparing(Rule::name, String.CASE_INSENSITIVE_ORDER));
		return result;
	}

	private static <T> Rule describe(GameRules rules, GameRule<T> rule, Language language) {
		String key = rule.getDescriptionId();
		String name = language.getOrDefault(key, rule.id());
		String description = language.getOrDefault(key + ".description", "");
		String type = rule.valueClass() == Boolean.class ? "bool" : rule.valueClass() == Integer.class ? "number" : "text";
		Integer min = null;
		Integer max = null;
		if (rule.argument() instanceof IntegerArgumentType integer) {
			min = integer.getMinimum();
			max = integer.getMaximum();
		}
		return new Rule(rule.id(), name, description, rule.category().label().getString(), type, rules.getAsString(rule),
				rule.serialize(rule.defaultValue()), min, max);
	}

	/** Applies changes (rule id → text value). Returns an error or null; nothing changes on an error. */
	public static String save(MinecraftServer server, Map<String, String> changes, String who) {
		GameRules rules = server.overworld().getGameRules();
		Map<String, GameRule<?>> byId = new LinkedHashMap<>();
		rules.availableRules().forEach(rule -> byId.put(rule.id(), rule));
		List<Runnable> apply = new ArrayList<>();
		for (Map.Entry<String, String> change : changes.entrySet()) {
			GameRule<?> rule = byId.get(change.getKey());
			if (rule == null) {
				return "Unknown gamerule " + change.getKey() + ".";
			}
			String error = prepare(server, rules, rule, change.getValue().trim(), apply);
			if (error != null) {
				return change.getKey() + ": " + error;
			}
		}
		apply.forEach(Runnable::run);
		if (!changes.isEmpty()) {
			NiceControlCenter.LOGGER.info("Gamerules changed by {}: {}", who, changes);
		}
		return null;
	}

	private static <T> String prepare(MinecraftServer server, GameRules rules, GameRule<T> rule, String text, List<Runnable> apply) {
		DataResult<T> parsed = rule.deserialize(text);
		if (parsed.error().isPresent()) {
			if (rule.valueClass() == Boolean.class) {
				return "must be true or false.";
			}
			if (rule.argument() instanceof IntegerArgumentType range) {
				return "must be a whole number between " + range.getMinimum() + " and " + range.getMaximum() + ".";
			}
			return parsed.error().get().message();
		}
		T value = parsed.getOrThrow();
		if (value instanceof Integer number && rule.argument() instanceof IntegerArgumentType range
				&& (number < range.getMinimum() || number > range.getMaximum())) {
			return "must be between " + range.getMinimum() + " and " + range.getMaximum() + ".";
		}
		apply.add(() -> rules.set(rule, value, server));
		return null;
	}
}
