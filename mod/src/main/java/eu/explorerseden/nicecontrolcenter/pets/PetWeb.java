package eu.explorerseden.nicecontrolcenter.pets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.server.MinecraftServer;

import eu.explorerseden.nicecontrolcenter.Claims;
import eu.explorerseden.nicecontrolcenter.Pets;

/**
 * Pets on the website (through the panel, which already checked who the player is): the player's pets and their
 * /pets settings, and the same changes the /pets menu makes (which pet is out, how it moves, its particles, a
 * random pet, perks, reactions, the name above it, a pet's name and colour). Same rules as the menu. Server thread.
 */
public final class PetWeb {
	private PetWeb() {
	}

	public static Map<String, Object> list(MinecraftServer server, String uuid) {
		Pets.Settings s = PetStore.settings();
		Pets.Owner o = PetStore.data().owners.get(uuid);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("enabled", s.enabled);
		out.put("names", s.names);
		out.put("particles", s.particles);
		out.put("perksEnabled", s.perks.enabled);
		out.put("nameColors", s.nameColors);
		List<Map<String, Object>> owned = new ArrayList<>();
		if (o != null) for (Pets.Owned p : o.pets) {
			Map<String, Object> m = new LinkedHashMap<>();
			int level = Pets.level(p.bond, s.perks);
			m.put("id", p.pet);
			m.put("name", PetStore.name(p));
			m.put("nickname", p.nickname);
			m.put("nameColor", p.nameColor);
			m.put("level", level);
			m.put("maxLevel", s.perks.maxLevel);
			m.put("nextLevelMinutes", level >= s.perks.maxLevel ? 0 : Math.max(1, Pets.minutesFor(level + 1, s.perks) - p.bond / 1200));
			m.put("since", p.since);
			m.put("active", p.pet.equals(o.active));
			m.put("alsoOut", o.alsoOut != null && o.alsoOut.contains(p.pet));
			m.put("head", Pets.skinHash(PetStore.texture(p)));
			Pets.Pet shop = s.pet(p.pet);
			List<Map<String, Object>> perks = new ArrayList<>();
			if (shop != null) for (int i = 0; i < shop.perks.size() && i < 2; i++) {
				Pets.Perk perk = Pets.Perk.of(shop.perks.get(i));
				if (perk == null) continue;
				int at = Pets.slotLevel(i, s.perks);
				perks.add(Map.of("id", perk.name(), "label", perk.label, "description", perk.describe(s.perks), "level", at,
						"unlocked", level >= at, "off", o.perksOff.contains(perk.name())));
			}
			m.put("perks", perks);
			owned.add(m);
		}
		out.put("pets", owned);
		out.put("behavior", Pets.Behavior.of(o == null ? null : o.behavior).name());
		out.put("trail", Pets.Trail.of(o == null ? null : o.trail).name());
		out.put("random", o != null && o.random);
		// Extra pets out at once (a reward): how many may come along with the main one.
		out.put("slots", o == null ? 0 : o.slots);
		out.put("reactions", o == null || o.reactions);
		out.put("showName", o == null || o.showName);
		List<Map<String, Object>> moves = new ArrayList<>();
		for (Pets.Behavior b : Pets.Behavior.values()) moves.add(Map.of("id", b.name(), "label", b.label));
		out.put("behaviors", moves);
		List<Map<String, Object>> trails = new ArrayList<>();
		for (Pets.Trail t : Pets.Trail.values()) if (PetFollowers.available(t)) trails.add(Map.of("id", t.name(), "label", t.label));
		out.put("trails", trails);
		return out;
	}

	/** One change, like a click in /pets. Answers ok + message, or error. */
	public static Map<String, Object> edit(MinecraftServer server, JsonObject in) {
		String uuid = str(in, "uuid");
		Pets.Settings s = PetStore.settings();
		if (!s.enabled) return Map.of("error", "Pets are switched off.");
		Pets.Owner o = PetStore.data().owners.get(uuid);
		if (o == null || o.pets.isEmpty()) return Map.of("error", "You don't have a pet yet.");
		Pets.Owned owned = in.has("pet") ? o.owned(str(in, "pet")) : null;
		if (in.has("pet") && owned == null) return Map.of("error", "You don't have that pet any more.");
		String message;
		switch (str(in, "change")) {
			case "out" -> {
				if (owned == null) return Map.of("error", "Pick a pet.");
				o.active = owned.pet;
				// Picking one pet means that one, not a random one.
				o.random = false;
				message = PetStore.name(owned) + " is out.";
			}
			case "away" -> {
				o.putAway();
				message = "Your pet is put away.";
			}
			case "also" -> {
				if (owned == null) return Map.of("error", "Pick a pet.");
				boolean on = bool(in, "on", true);
				if (on && o.active == null) return Map.of("error", "Take a pet out first; the others come along with it.");
				if (!o.alsoOut(owned.pet, on) && on) return Map.of("error", o.slots == 0 ? "Only one pet can be out at a time." : "No free slot: put another pet back first.");
				message = PetStore.name(owned) + (on ? " comes along too." : " stays home.");
			}
			case "random" -> {
				if (o.pets.size() < 2) return Map.of("error", "A random pet needs at least two pets.");
				o.random = bool(in, "on", !o.random);
				if (o.random && o.active == null) o.takeOut();
				message = o.random ? "Random pet: a different one every time you teleport, die or take it out." : "Random pet is off.";
			}
			case "move" -> {
				Pets.Behavior b = Pets.Behavior.of(str(in, "how"));
				o.behavior = b.name();
				message = "Your pet moves like this now: " + b.label + ".";
			}
			case "trail" -> {
				if (!s.particles) return Map.of("error", "Particle trails are switched off on this server.");
				Pets.Trail t = Pets.Trail.of(str(in, "how"));
				if (!PetFollowers.available(t)) return Map.of("error", "That trail isn't available.");
				o.trail = t.name();
				message = t == Pets.Trail.OFF ? "No particles." : "Particles: " + t.label + ".";
			}
			case "perk" -> {
				Pets.Perk perk = Pets.Perk.of(str(in, "perk"));
				if (perk == null) return Map.of("error", "Unknown perk.");
				boolean off = !bool(in, "on", o.perksOff.contains(perk.name()));
				if (off) o.perksOff.add(perk.name());
				else o.perksOff.remove(perk.name());
				message = perk.label + (off ? " is off." : " is on.");
			}
			case "reactions" -> {
				o.reactions = bool(in, "on", !o.reactions);
				message = o.reactions ? "Your pets react to what happens again." : "Your pets don't react any more.";
			}
			case "showname" -> {
				if (!s.names) return Map.of("error", "Pet names are switched off on this server.");
				o.showName = bool(in, "on", !o.showName);
				message = o.showName ? "Your pet's name shows above it." : "Your pet's name is hidden.";
			}
			case "name" -> {
				if (!s.names) return Map.of("error", "Pet names are switched off on this server.");
				if (owned == null) return Map.of("error", "Pick a pet.");
				int rgb = Claims.parseColor(str(in, "color"));
				if (rgb < 0) return Map.of("error", "\"" + str(in, "color") + "\" isn't a colour like #3FC9E8.");
				owned.nickname = Pets.nickname(str(in, "nickname"));
				owned.nameColor = Claims.hex(rgb);
				message = owned.nickname.isEmpty() ? PetStore.name(owned) + " has no name now." : PetStore.name(owned) + " is called " + owned.nickname + " now.";
			}
			default -> {
				return Map.of("error", "Unknown change.");
			}
		}
		PetStore.changed();
		return Map.of("ok", true, "message", message);
	}

	private static String str(JsonObject o, String key) {
		JsonElement v = o.get(key);
		return v == null || v.isJsonNull() ? "" : v.getAsString().strip();
	}

	private static boolean bool(JsonObject o, String key, boolean otherwise) {
		JsonElement v = o.get(key);
		return v == null || v.isJsonNull() ? otherwise : v.getAsBoolean();
	}
}
