package eu.explorerseden.nicecontrolcenter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pets: floating player heads that follow their owner (/pets), only for looks. The panel puts pets in the
 * pet shop (a price in the market's currency, how long a bought pet lasts, how many can be sold); players
 * buy them from /market or /pets and pick how theirs moves.
 *
 * Settings in config/nicecontrolcenter-pets.json (written by the panel), who owns what in the world folder
 * (nicecontrolcenter-pets.json). Minecraft-free.
 */
public final class Pets {
	public static final String FILE = "nicecontrolcenter-pets.json";
	public static final String DATA_FILE = "nicecontrolcenter-pets.json";
	private static final int PURCHASES = 1000;
	private static final Pattern HASH = Pattern.compile("[0-9a-f]{32,128}");
	private static final Pattern TEXTURE_URL = Pattern.compile("^https?://textures\\.minecraft\\.net/texture/([0-9a-fA-F]{32,128})$");

	private Pets() {
	}

	/** How a pet moves. */
	public enum Behavior {
		FLOAT("Float", "Hovers next to your shoulder"),
		ORBIT("Orbit", "Circles around your head"),
		BOUNCE("Bounce", "Hops up and down next to you"),
		SPIN("Spin", "Spins above your head"),
		FOLLOW("Follow", "Trails behind you");

		public final String label;
		public final String description;

		Behavior(String label, String description) {
			this.label = label;
			this.description = description;
		}

		public static Behavior of(String name) {
			if (name != null) for (Behavior b : values()) if (b.name().equalsIgnoreCase(name)) return b;
			return FLOAT;
		}
	}

	/** A pet in the shop. */
	public static final class Pet {
		public String id;
		public String name;
		/** The head's skin: the base64 "textures" value (like on a player head). */
		public String texture;
		/** In the market's currency; 0 is free. */
		public int price;
		/** How long a bought pet lasts; 0 is forever. */
		public int days;
		/** How many can be sold in all; 0 is no limit. */
		public int stock;
		/** Off keeps the pet (and the ones players own) but stops selling it. */
		public boolean forSale = true;
	}

	public static final class Settings {
		public boolean enabled = false;
		/** Head size (1 = half a block, like a player head). */
		public double size = 0.8;
		public List<Pet> pets = new ArrayList<>();

		public Settings fill() {
			if (pets == null) pets = new ArrayList<>();
			pets.removeIf(p -> p == null);
			for (Pet p : pets) {
				if (p.id == null || p.id.isBlank()) p.id = UUID.randomUUID().toString().substring(0, 8);
				if (p.name != null) p.name = p.name.strip();
				String t = texture(p.texture);
				if (t != null) p.texture = t;
			}
			return this;
		}

		public Pet pet(String id) {
			for (Pet p : pets) if (p.id.equals(id)) return p;
			return null;
		}
	}

	public static String validate(Settings s) {
		if (s == null) return "No settings.";
		s.fill();
		if (!(s.size >= 0.3 && s.size <= 2)) return "The head size is 0.3 to 2.";
		if (s.pets.size() > 200) return "At most 200 pets.";
		Set<String> ids = new HashSet<>();
		for (Pet p : s.pets) {
			if (!p.id.matches("[A-Za-z0-9_-]{1,40}") || !ids.add(p.id)) return "A pet has a bad or doubled id.";
			if (p.name == null || p.name.isEmpty() || p.name.length() > 32) return "Every pet needs a name of 1 to 32 characters.";
			if (texture(p.texture) == null) return "The head texture of " + p.name + " isn't a skin value (base64 from a head generator) or a textures.minecraft.net link.";
			if (p.price < 0 || p.price > 10_000_000) return "The price of " + p.name + " is 0 to 10,000,000.";
			if (p.days < 0 || p.days > 3650) return "A pet lasts 1 to 3650 days, or 0 for forever.";
			if (p.stock < 0 || p.stock > 1_000_000) return "The amount of " + p.name + " is 0 (no limit) to 1,000,000.";
		}
		return null;
	}

	/**
	 * A head texture in its stored form (the base64 value), from that value, a textures.minecraft.net link
	 * or the bare skin hash. Null when it's none of these.
	 */
	public static String texture(String in) {
		if (in == null) return null;
		String t = in.strip();
		if (t.isEmpty()) return null;
		Matcher url = TEXTURE_URL.matcher(t);
		String hash = url.matches() ? url.group(1).toLowerCase(Locale.ROOT) : HASH.matcher(t.toLowerCase(Locale.ROOT)).matches() ? t.toLowerCase(Locale.ROOT) : null;
		if (hash != null) {
			String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + hash + "\"}}}";
			return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
		}
		return skinHash(t) == null ? null : t;
	}

	/** The skin's hash on textures.minecraft.net from the base64 value, or null. */
	public static String skinHash(String texture) {
		if (texture == null) return null;
		try {
			String json = new String(Base64.getDecoder().decode(texture.strip()), StandardCharsets.UTF_8);
			JsonObject o = JsonParser.parseString(json).getAsJsonObject();
			String url = o.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString();
			Matcher m = TEXTURE_URL.matcher(url.strip());
			return m.matches() ? m.group(1).toLowerCase(Locale.ROOT) : null;
		} catch (RuntimeException e) {
			// Not base64, not JSON or no skin in it.
			return null;
		}
	}

	public static Settings read(Path file) {
		try {
			if (!Files.exists(file)) return new Settings().fill();
			Settings s = Json.GSON.fromJson(Files.readString(file), Settings.class);
			if (s == null || validate(s) != null) return new Settings().fill();
			return s;
		} catch (IOException | RuntimeException e) {
			return new Settings().fill();
		}
	}

	public static void write(Path file, Settings settings) throws IOException {
		writeJson(file, Json.GSON.newBuilder().setPrettyPrinting().create().toJson(settings));
	}

	/** "forever", "1 day", "30 days". */
	public static String duration(int days) {
		return days <= 0 ? "forever" : days == 1 ? "1 day" : days + " days";
	}

	// ---------------------------------------------------------------- who owns what

	/** A pet a player has: a copy of its name and head, so it stays even if the shop drops it. */
	public static final class Owned {
		public String pet;
		public String name;
		public String texture;
		public long since;
		/** 0 = forever. */
		public long expires;
		public String behavior = Behavior.FLOAT.name();
	}

	public static final class Owner {
		public String name;
		public List<Owned> pets = new ArrayList<>();
		/** The pet that's out, or null. */
		public String active;

		public Owned owned(String pet) {
			for (Owned o : pets) if (o.pet.equals(pet)) return o;
			return null;
		}
	}

	public static final class Purchase {
		public String pet;
		public String petName;
		public String buyer;
		public String buyerName;
		public int price;
		public int days;
		public long time;
	}

	/** A pet that ran out. */
	public record Expired(String owner, String pet, String name, boolean wasActive) {
	}

	public static final class Data {
		/** Pets sold so far, per pet id (for the shop's amount). */
		public Map<String, Integer> sold = new LinkedHashMap<>();
		public Map<String, Owner> owners = new LinkedHashMap<>();
		public List<Purchase> purchases = new ArrayList<>();

		public Data fill() {
			if (sold == null) sold = new LinkedHashMap<>();
			if (owners == null) owners = new LinkedHashMap<>();
			if (purchases == null) purchases = new ArrayList<>();
			owners.values().removeIf(o -> o == null);
			for (Owner o : owners.values()) {
				if (o.pets == null) o.pets = new ArrayList<>();
				o.pets.removeIf(p -> p == null || p.pet == null || p.texture == null);
			}
			return this;
		}

		public Owner owner(String uuid, String name) {
			Owner o = owners.computeIfAbsent(uuid, k -> new Owner());
			if (name != null) o.name = name;
			return o;
		}

		public int sold(String pet) {
			return sold.getOrDefault(pet, 0);
		}

		/** How many are left to buy, or -1 for no limit. */
		public int left(Pet p) {
			return p.stock <= 0 ? -1 : Math.max(0, p.stock - sold(p.id));
		}

		/** Why the player can't buy the pet now (apart from paying), or null. */
		public String cannotBuy(String uuid, Pet p) {
			if (!p.forSale) return "That pet isn't for sale any more.";
			if (left(p) == 0) return "That pet is sold out.";
			Owner o = owners.get(uuid);
			Owned have = o == null ? null : o.owned(p.id);
			if (have != null && have.expires == 0) return "You already have " + p.name + ".";
			return null;
		}

		/** A purchase (already paid): the pet is the player's, or lasts longer if they had it. */
		public void buy(String uuid, String name, Pet p, long now) {
			Owner o = owner(uuid, name);
			Owned have = o.owned(p.id);
			if (have == null) {
				have = new Owned();
				have.pet = p.id;
				have.since = now;
				o.pets.add(have);
			}
			have.name = p.name;
			have.texture = p.texture;
			if (p.days == 0) have.expires = 0;
			else have.expires = Math.max(now, have.expires) + p.days * 86_400_000L;
			sold.merge(p.id, 1, Integer::sum);
			Purchase b = new Purchase();
			b.pet = p.id;
			b.petName = p.name;
			b.buyer = uuid;
			b.buyerName = name;
			b.price = p.price;
			b.days = p.days;
			b.time = now;
			purchases.add(b);
			while (purchases.size() > PURCHASES) purchases.remove(0);
		}

		/** Takes away pets whose time is up. */
		public List<Expired> expire(long now) {
			List<Expired> out = new ArrayList<>();
			owners.forEach((uuid, o) -> o.pets.removeIf(p -> {
				if (p.expires == 0 || p.expires > now) return false;
				boolean active = p.pet.equals(o.active);
				if (active) o.active = null;
				out.add(new Expired(uuid, p.pet, p.name, active));
				return true;
			}));
			return out;
		}
	}

	public static Data readData(Path file) throws IOException {
		if (!Files.exists(file)) return new Data().fill();
		Data d = Json.GSON.fromJson(Files.readString(file), Data.class);
		return d == null ? new Data().fill() : d.fill();
	}

	public static void writeData(Path file, Data data) throws IOException {
		writeJson(file, Json.GSON.toJson(data));
	}

	private static void writeJson(Path file, String json) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, json, StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
