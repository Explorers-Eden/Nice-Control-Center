package eu.explorerseden.nicecontrolcenter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The server's own rules in plain words for players: the switched-on performance tweaks, the world
 * borders (permanent area and edge) and how land claims work. The panel's public info page shows them,
 * and Merl (the in-game helper mod) answers questions with them, calling {@link #all(Path)} by reflection.
 * Minecraft-free. Each entry: {id, title, icon, description, rows: [{label, value, description}]}.
 */
public final class ServerFacts {
	private ServerFacts() {
	}

	/** Everything from the config folder (performance, borders, claims), for Merl. */
	public static List<Map<String, Object>> all(Path configDir) {
		List<Map<String, Object>> out = new ArrayList<>(performance(Performance.read(configDir.resolve(Performance.FILE))));
		out.addAll(borders(Borders.read(configDir.resolve(Borders.FILE))));
		out.addAll(claims(Claims.read(configDir.resolve(Claims.FILE))));
		out.addAll(tweaks(Tweaks.read(configDir.resolve(Tweaks.FILE))));
		out.addAll(market(Market.read(configDir.resolve(Market.FILE))));
		return out;
	}

	/** How the player market works (nothing while it's closed). */
	public static List<Map<String, Object>> market(Market.Settings m) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (!m.enabled) return out;
		String currency = humanize(m.currency.replaceFirst("^minecraft:", ""));
		out.add(tweak("market", "Market", "bi-shop",
				"Type /market to sell items for " + currency.toLowerCase(Locale.ROOT) + "s (any amount, more than a stack is fine) or buy what others sell. "
						+ "Your earnings and unsold items wait in your market mailbox.",
				List.of(line("Paid with", currency, ""), line("Listings last", m.listingDays + " days", "Then unsold items come back to your mailbox."),
						line("Listings per player", String.valueOf(m.maxListings), ""))));
		return out;
	}

	private static String sneak(Tweaks.Sneak s, String whole, String one) {
		return switch (s) {
			case DISABLES -> "sneak to break just " + one;
			case ENABLES -> "sneak to break " + whole + "; without sneaking just " + one;
			case IGNORED -> "always " + whole;
		};
	}

	private static String wear(double m, String what) {
		if (m <= 0) return "none";
		if (m == 1) return "one use per " + what;
		return String.format(Locale.ROOT, "%s uses per %s", java.math.BigDecimal.valueOf(m).stripTrailingZeros().toPlainString(), what);
	}

	/** The switched-on gameplay tweaks, for players. */
	public static List<Map<String, Object>> tweaks(Tweaks.Settings t) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (t.anvil.noTooExpensive || t.anvil.cheapRename) {
			List<Map<String, String>> rows = new ArrayList<>();
			if (t.anvil.noTooExpensive) rows.add(line("Too Expensive", "never", "Repairs and enchantments of 40 levels or more work too, at their full price. The anvil shows 39; the real price appears above your hotbar."));
			if (t.anvil.cheapRename) rows.add(line("Renaming costs", t.anvil.renameCost + (t.anvil.renameCost == 1 ? " level" : " levels"), "Renaming alone, however often the item went through an anvil."));
			out.add(tweak("anvil", "Anvil", "bi-hammer", "The anvil is kinder than in vanilla.", rows));
		}
		if (t.portals.anyShape) {
			out.add(tweak("portals", "Nether portals in any shape", "bi-circle", "Any closed portal frame (obsidian, plus what the server adds as frame blocks) lights as a nether portal: circles, arches, hearts... not just rectangles.",
					List.of(line("Biggest portal", t.portals.maxBlocks + " portal blocks", "Bigger frames don't light."))));
		}
		if (t.felling.enabled) {
			List<Map<String, String>> rows = new ArrayList<>();
			rows.add(line("Sneaking", sneak(t.felling.sneak, "the whole tree", "the one log"), ""));
			rows.add(line("Biggest tree", t.felling.maxLogs + " logs", "cutUpTo".equals(t.felling.tooBig) ? "Bigger trees: the first " + t.felling.maxLogs + " logs fall." : "Bigger trees only lose the one log."));
			rows.add(line("Axe wear", wear(t.felling.durability, "log"), t.felling.preserveTool ? "It stops before your axe would break." : ""));
			if (t.felling.fastLeafDecay) rows.add(line("Leaves", "fall within a few seconds", ""));
			out.add(tweak("felling", "Tree felling", "bi-tree",
					"Chop one log with an axe and the whole tree comes down" + ("shiftDown".equals(t.felling.breakMode) ? " one log at a time from the top" : "")
							+ ". Logs you placed yourself (without leaves around) stay single blocks.", rows));
		}
		if (t.veins.enabled) {
			out.add(tweak("veins", "Vein mining", "bi-gem", "Mine one ore with a pickaxe and the whole vein (up to " + t.veins.maxBlocks + " blocks) comes out.",
					List.of(line("Sneaking", sneak(t.veins.sneak, "the whole vein", "the one ore"), ""),
							line("Pickaxe wear", wear(t.veins.durability, "block"), t.veins.preserveTool ? "It stops before your pickaxe would break." : ""))));
		}
		if (t.grindstone.booksEnabled) {
			String per = t.grindstone.levelsPerEnchantLevel == 1 ? "one level per enchantment level"
					: java.math.BigDecimal.valueOf(t.grindstone.levelsPerEnchantLevel).stripTrailingZeros().toPlainString() + " levels per enchantment level";
			out.add(tweak("grindstone", "Grindstone to books", "bi-book", "Put an enchanted item and a book into a grindstone: the enchantments move onto an enchanted book and the item keeps only its curses.",
					List.of(line("Costs", per, "At least " + t.grindstone.minLevels + ", at most " + t.grindstone.maxLevels + " levels. The price appears above your hotbar."))));
		}
		if (t.spawners.silkTouch) {
			List<Map<String, String>> rows = new ArrayList<>();
			double m = t.spawners.durability;
			rows.add(line("Pickaxe wear", m <= 0 ? "none" : m == 1 ? "like mining any block" : java.math.BigDecimal.valueOf(m).stripTrailingZeros().toPlainString() + " × the normal wear", ""));
			if (!t.spawners.deniedMobs.isEmpty()) {
				List<String> mobs = new ArrayList<>();
				for (String mob : t.spawners.deniedMobs) mobs.add(humanize(mob.replaceFirst("^minecraft:", "")));
				rows.add(line("Can't be picked up", String.join(", ", mobs), ""));
			}
			List<String> tools = new ArrayList<>();
			for (String tl : t.spawners.tools) tools.add(humanize(tl.replaceFirst("^#?minecraft:", "")));
			rows.add(0, line("With", String.join(", ", tools) + (t.spawners.requireSilkTouch ? " with Silk Touch" : ""), ""));
			out.add(tweak("spawners", "Silk touch spawners", "bi-box-seam", "Mine a spawner with the right tool to take it with you; it keeps its mob when you place it again.", rows));
		}
		if (t.saplings.replant) {
			out.add(tweak("saplings", "Saplings plant themselves", "bi-flower1", "Saplings lying on the ground plant themselves after " + t.saplings.delaySeconds + " seconds, if they can grow there"
					+ (t.saplings.inClaims ? "" : " and it isn't claimed land") + ".", List.of()));
		}
		{
			Tweaks.BoneMeal b = t.boneMeal;
			List<String> grows = new ArrayList<>();
			if (b.sugarCane) grows.add("sugar cane");
			if (b.cactus) grows.add("cactus");
			if (b.netherWart) grows.add("nether wart");
			if (b.lilyPads) grows.add("lily pads");
			if (b.vines) grows.add("vines");
			if (b.flowers) grows.add("small flowers");
			if (!grows.isEmpty()) {
				out.add(tweak("bonemeal", "More bone meal", "bi-stars", "Bone meal also works on " + String.join(", ", grows) + ".",
						b.sugarCane || b.cactus ? List.of(line("Sugar cane and cactus", "up to " + b.maxHeight + " blocks tall", "")) : List.of()));
			}
		}
		if (t.tridents.voidReturn) {
			out.add(tweak("tridents", "Tridents come back from the void", "bi-arrow-return-left", "A Loyalty trident thrown into the void comes back to you instead of being lost.", List.of()));
		}
		if (t.chat.mentions) {
			out.add(tweak("mentions", "@Mentions", "bi-at", "Write @Name in chat and that player hears a little plop and sees who mentioned them"
					+ (t.chat.discordPing ? "; players who aren't online get pinged on Discord if they linked their account." : "."), List.of()));
		}
		if (t.afk.enabled) {
			out.add(tweak("afk", "AFK", "bi-moon", "Players who haven't moved for " + t.afk.minutes + " minutes get \"" + t.afk.tag + "\" next to their name in the tab list.", List.of()));
		}
		return out;
	}

	/** The permanent area and the edge of the world per dimension. */
	public static List<Map<String, Object>> borders(Map<String, Borders.Border> borders) {
		List<Map<String, Object>> out = new ArrayList<>();
		borders.forEach((dim, b) -> {
			if (b.soft <= 0 && b.hard <= 0) return;
			String name = humanize(dim.replaceFirst("^minecraft:", "").replaceFirst("^the_", ""));
			List<Map<String, String>> rows = new ArrayList<>();
			if (b.soft > 0) rows.add(line("Permanent area", size(b, b.soft),
					"Builds inside are kept. Land outside it (the Outer Wilds) is reset to fresh terrain from time to time."));
			rows.add(line("Edge of the world", b.hard > 0 ? size(b, b.hard) : "none", b.hard > 0 ? "You can't go further than this." : ""));
			out.add(tweak("border-" + dim, name + " borders", "bi-bounding-box-circles", "The world borders of " + name + ", around " + b.x + ", " + b.z + ".", rows));
		});
		return out;
	}

	private static String size(Borders.Border b, int r) {
		return b.circle() ? String.format(Locale.ROOT, "a circle %,d blocks across", r * 2)
				: String.format(Locale.ROOT, "from -%,d to %,d (X and Z)", r, r);
	}

	/** How claiming works here (nothing while claims are off). */
	public static List<Map<String, Object>> claims(Claims.Settings s) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (!s.enabled) return out;
		List<Map<String, String>> rows = new ArrayList<>();
		rows.add(line("Claim blocks for new players", String.valueOf(s.startingBlocks), "Each claimed block costs one claim block; subclaims are free."));
		rows.add(line("Claim blocks per hour of play", String.valueOf(s.blocksPerHour), s.afkMinutes > 0 ? "Not while AFK for more than " + s.afkMinutes + " minutes." : ""));
		rows.add(line("Most claim blocks from playing", String.valueOf(s.maxAccruedBlocks), ""));
		List<String> dims = new ArrayList<>();
		for (String d : s.dimensions) dims.add(humanize(d.replaceFirst("^minecraft:", "").replaceFirst("^the_", "")));
		rows.add(line("Where you can claim", dims.isEmpty() ? "nowhere" : String.join(", ", dims),
				s.onlyPermanentArea ? "Only inside the permanent area." : ""));
		rows.add(line("Smallest claim", s.minWidth + " blocks wide, " + s.minArea + " blocks in all", ""));
		if (s.maxClaims > 0) rows.add(line("Claims per player", String.valueOf(s.maxClaims), ""));
		if (s.inactiveDays > 0) rows.add(line("Inactive players", "claims go after " + s.inactiveDays + " days away", ""));
		if (s.shopEnabled) rows.add(line("Buy claim blocks", s.shopBlocks + " for " + s.shopPrice + " " + humanize(s.shopItem.replaceFirst("^minecraft:", "")),
				"In the /claim menu" + (s.shopMaxBlocks > 0 ? ", up to " + s.shopMaxBlocks + " in total." : ".")));
		out.add(tweak("claims", "Land claims", "bi-shield-check",
				"Type /claim mode to get the claim tool and right-click two opposite corners of your land: everything in between, from bedrock to the sky, is protected. "
						+ "Right-click a corner to move it; sneak + right-click or /claim opens the claim menu, where you add members, choose what everyone else may do, "
						+ "name and colour the claim and make subclaims.", rows));
		List<Map<String, String>> roles = new ArrayList<>();
		for (Claims.Role r : Claims.Role.values()) if (r != Claims.Role.OWNER) roles.add(line(r.label, "", r.description));
		out.add(tweak("claim-roles", "Claim member roles", "bi-people", "What members of a claim may do; each role includes the ones before it.", roles));
		return out;
	}

	/** "fire_charge" → "Fire charge". */
	static String humanize(String key) {
		String t = key.replaceAll("[-_.]+", " ").replaceAll("([a-z])([A-Z])", "$1 $2").trim().toLowerCase(Locale.ROOT);
		return t.isEmpty() ? key : Character.toUpperCase(t.charAt(0)) + t.substring(1);
	}

	static Map<String, Object> tweak(String id, String title, String icon, String desc, List<Map<String, String>> rows) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id);
		m.put("title", title);
		m.put("icon", icon);
		m.put("description", desc);
		m.put("rows", rows);
		return m;
	}

	static Map<String, String> line(String label, String value, String desc) {
		return Map.of("label", label, "value", value, "description", desc);
	}

	private static String blocks(double v) {
		return (v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v)) + (v == 1 ? " block" : " blocks");
	}

	/** The switched-on performance tweaks, explained for players (what changes for them, compared to vanilla). */
	public static List<Map<String, Object>> performance(Performance.Settings p) {
		List<Map<String, Object>> out = new ArrayList<>();
		Performance.Dynamic d = p.dynamic;
		if (d.enabled) {
			List<Map<String, String>> rows = new ArrayList<>();
			rows.add(line("Starts lowering at", d.targetMspt + " ms per tick", "A tick should take at most 50 ms; above this the server starts saving work."));
			rows.add(line("Back to normal below", d.recoverMspt + " ms per tick", "After a minute below this, everything goes back up step by step."));
			rows.add(line("Lowest simulation distance", d.simulationMin + " chunks", "How far around players crops grow, mobs move and redstone runs."));
			rows.add(line("Lowest mob cap", d.mobCapMin + " %", "Fewer mobs spawn naturally while it's lowered (100 % = vanilla)."));
			if (d.extraSteps) rows.add(line("Then, before the view distance", "true", "Mobs far from players move less often, then they show up from a bit less far away."));
			rows.add(line("View distance lowered too", d.view ? "true" : "false", d.view ? "As a last step, down to " + d.viewMin + " chunks." : "How far you can see always stays the same."));
			out.add(tweak("dynamic", "Lag protection", "bi-activity",
					"When the server can't keep up, it lowers a few distances one step at a time instead of lagging, and raises them again once it's fine.", rows));
		}
		Performance.Activation a = p.activation;
		if (a.enabled) {
			String tick = "Farther away they move and think every " + a.every + " ticks instead of every tick.";
			List<Map<String, String>> rows = new ArrayList<>();
			rows.add(line("Monsters", blocks(a.monsters), "Zombies, skeletons, creepers…"));
			rows.add(line("Animals", blocks(a.animals), "Cows, sheep, chickens…"));
			rows.add(line("Villagers", blocks(a.villagers), ""));
			rows.add(line("Everything else", blocks(a.other), ""));
			out.add(tweak("activation", "Mobs far from players", "bi-broadcast",
					"Mobs that are far from every player are updated less often. " + tick
							+ " Items, projectiles, vehicles, bosses, mobs on a lead and mobs that were just hurt always update normally.", rows));
		}
		Performance.Breeding b = p.breeding;
		if (b.enabled) {
			out.add(tweak("breeding", "Breeding limits", "bi-heart",
					"Animals and villagers can't breed while too many of their kind live close together. Spread big farms out to keep breeding.",
					List.of(line("Animals", "up to " + b.animals + " per kind", "Within " + blocks(b.radius) + "; feeding them does nothing above this."),
							line("Villagers", "up to " + b.villagers + " villagers", "Within " + blocks(b.radius) + "."))));
		}
		Performance.Merge m = p.merge;
		if (m.enabled) {
			out.add(tweak("merge", "Item and XP merging", "bi-intersect",
					"Dropped items of the same kind and experience orbs merge from farther away, so fewer of them lie around.",
					List.of(line("Dropped items", "within " + blocks(m.items), "Vanilla: 0.5 blocks."),
							line("Experience orbs", "within " + blocks(m.xp), "The XP isn't lost, it's added together."))));
		}
		Performance.Ai ai = p.ai;
		if (ai.enabled) {
			out.add(tweak("ai", "Lighter mob AI", "bi-cpu",
					"Mobs far from every player decide what to do next less often; what they're doing keeps running. While the server lags, only so many mobs can look for a new path at once, the others try again a moment later.",
					List.of(line("Far from players means", "more than " + blocks(ai.farDistance), ""),
							line("Far mobs decide every", ai.farGoalEvery + " ticks", "Vanilla: every tick or two."))));
		}
		if (p.collisions.enabled) {
			out.add(tweak("collisions", "Mob pushing", "bi-arrows-collapse",
					"In crowded pens a mob only pushes a few others per tick. You still push and get pushed as usual, and too many mobs in one spot still take cramming damage.",
					List.of(line("Pushes per mob and tick", "up to " + p.collisions.max, "Paper servers use 8."))));
		}
		Performance.Despawn dsp = p.despawn;
		if (dsp.enabled) {
			List<String> names = new ArrayList<>();
			for (String id : dsp.items) names.add(humanize(id.replaceFirst("^minecraft:", "")));
			out.add(tweak("despawn", "Junk despawns sooner", "bi-hourglass-split",
					"These dropped items disappear sooner than the usual 5 minutes. Everything else stays the full 5 minutes. Pick up what you need quickly!",
					List.of(line("Junk items go after", dsp.itemSeconds + " seconds", String.join(", ", names)),
							line("Arrows shot by mobs go after", dsp.arrowSeconds + " seconds", "Your own arrows stay the usual minute."))));
		}
		if (p.saveLimits.enabled && !p.saveLimits.limits.isEmpty()) {
			List<Map<String, String>> rows = new ArrayList<>();
			p.saveLimits.limits.forEach((id, n) -> rows.add(line(id.equals("minecraft:item") ? "Dropped item stacks"
					: id.equals("minecraft:experience_orb") ? "XP orbs" : humanize(id.replaceFirst("^minecraft:", "")), "up to " + n, "")));
			out.add(tweak("savelimits", "Save limits per chunk", "bi-floppy",
					"When an area is saved, only this many of each kind are kept; the rest is gone when you come back. Keep valuables in chests, not on the floor.", rows));
		}
		if (!p.rules.isEmpty() && p.autoCaps.enabled) {
			List<Map<String, String>> rows = new ArrayList<>();
			for (Performance.MobRule r : p.rules) rows.add(line(mobName(r.type), "up to " + r.perChunk + " per chunk", ""));
			if (p.autoCaps.items) rows.add(line("Dropped items", "up to " + p.autoCaps.itemsPerChunk + " per chunk", "The oldest go first; anything dropped in the last 10 seconds stays."));
			out.add(tweak("caps", "Mob limits per chunk", "bi-funnel",
					"Extra mobs over these limits are removed every " + (p.autoCaps.intervalSeconds == 60 ? "minute" : p.autoCaps.intervalSeconds % 60 == 0
							? p.autoCaps.intervalSeconds / 60 + " minutes" : p.autoCaps.intervalSeconds + " seconds") + ". Named, tamed, leashed and persistent mobs are never removed: put a name tag on the ones you want to keep for sure.", rows));
		}
		if (p.hoppers.enabled) {
			out.add(tweak("hoppers", "Hoppers", "bi-inboxes",
					"A hopper that has nothing to move waits a moment before looking again. Hoppers that are moving items work at full speed, and redstone timing stays the same.",
					List.of(line("Idle hoppers wait", p.hoppers.idleCooldown + " ticks", "Vanilla: they look every tick."))));
		}
		if (p.decor.armorStands) {
			out.add(tweak("armorstands", "Armor stands", "bi-person-standing",
					"Armor stands that stand still update only once a second. Hitting, pushing or moving one makes it react as usual.", List.of()));
		}
		if (p.villagers.lobotomize) {
			out.add(tweak("villagers", "Villagers in trading halls", "bi-shop",
					"Villagers that can't move (in 1×1 trading cells) think less often. Trading and restocking still work.",
					List.of(line("They think every", p.villagers.every + " ticks", "Vanilla: every tick (20 times a second)."))));
		}
		return out;
	}

	/** "minecraft:cow" → "Cow"; "#animals" → "All animals"; "*" → "Any other mob". */
	private static String mobName(String type) {
		return switch (type) {
			case "*" -> "Any other mob";
			case "#animals" -> "All animals";
			case "#monsters" -> "All monsters";
			case "#villagers" -> "All villagers";
			case "#water" -> "All water mobs";
			case "#ambient" -> "Bats and other ambient mobs";
			default -> humanize(type.replaceFirst("^minecraft:", ""));
		};
	}
}
