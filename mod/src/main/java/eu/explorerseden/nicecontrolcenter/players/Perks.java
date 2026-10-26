package eu.explorerseden.nicecontrolcenter.players;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import eu.explorerseden.nicecontrolcenter.Claims;
import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.core.PlayerActivity;

/**
 * Non-item rewards a player holds (see /reward): timed perks whose time only runs while they work, shop coupons,
 * the market spotlight and the right to a custom join message. Kept in the
 * world folder (nicecontrolcenter-perks.json); checked every quarter second.
 */
public final class Perks {
	/** A timed perk: what it does, how its clock runs, how its level reads. */
	public enum Kind {
		XP("XP boost", Clock.ACTIVE, true),
		HASTE("Haste in your claims", Clock.IN_CLAIM, false),
		BLOCKBOOST("Claim-block boost", Clock.ACTIVE, true),
		BONDBOOST("Pet bond boost", Clock.ACTIVE, true),
		TOOLCARE("Tool care", Clock.ACTIVE, false),
		SPOTLIGHT("Market spotlight", Clock.LISTING, false),
		JOINMESSAGE("Own join message", Clock.REAL, false);

		public final String label;
		final Clock clock;
		/** The level is a multiplier ×10 (20 = ×2), else a plain level. */
		final boolean multiplier;

		Kind(String label, Clock clock, boolean multiplier) {
			this.label = label;
			this.clock = clock;
			this.multiplier = multiplier;
		}

		public static Kind of(String id) {
			for (Kind k : values()) if (k.name().equalsIgnoreCase(id)) return k;
			return null;
		}
	}

	/** When a perk's time runs down. */
	enum Clock {
		/** Online and not AFK. */
		ACTIVE,
		/** Online, not AFK and inside a claim where they're at least a Builder. */
		IN_CLAIM,
		/** While they have a market listing up (online or not). */
		LISTING,
		/** Real time. */
		REAL
	}

	public static final class Perk {
		/** Milliseconds left; -1 = for good. */
		public long left;
		public int level;

		public Perk() {
		}

		Perk(long left, int level) {
			this.left = left;
			this.level = level;
		}
	}

	public static final class Holder {
		public String name;
		public Map<String, Perk> perks = new LinkedHashMap<>();
		/** Coupons: worth (currency items off one purchase) → how many. */
		public Map<Integer, Integer> coupons = new TreeMap<>();
		public String joinText;
	}

	private static final Map<String, Holder> holders = new ConcurrentHashMap<>();
	private static Path file;
	private static long lastTick;
	private static long lastSave;
	private static boolean dirty;
	private Perks() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(Perks::load);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> save());
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 5 == 0) tick(server, server.getTickCount() % 20 == 0);
		});
	}

	// ---------------------------------------------------------------- reading (for the hooks)

	public static Holder holder(String uuid) {
		return holders.get(uuid);
	}

	private static Perk active(String uuid, Kind k) {
		Holder h = holders.get(uuid);
		Perk p = h == null ? null : h.perks.get(k.name());
		return p != null && (p.left > 0 || p.left == -1) ? p : null;
	}

	/** The multiplier of a multiplier perk (1 when none). */
	public static double factor(String uuid, Kind k) {
		Perk p = active(uuid, k);
		return p == null ? 1.0 : p.level / 10.0;
	}

	public static boolean has(String uuid, Kind k) {
		return active(uuid, k) != null;
	}

	/** Their join line (with {player} filled in), when they have the right and set a text. */
	public static String joinLine(String uuid, String name) {
		Holder h = holders.get(uuid);
		if (h == null || h.joinText == null || h.joinText.isBlank() || active(uuid, Kind.JOINMESSAGE) == null) return null;
		return h.joinText.replace("{player}", name);
	}

	/**
	 * The coupon to use on a price: worth the most without going over it, else the smallest one (a coupon worth
	 * more than the price makes it free). 0 when they have none.
	 */
	public static int bestCoupon(String uuid, long price) {
		Holder h = holders.get(uuid);
		if (h == null) return 0;
		int under = 0;
		int smallest = 0;
		for (Map.Entry<Integer, Integer> e : h.coupons.entrySet()) {
			if (e.getValue() <= 0) continue;
			int v = e.getKey();
			if (v <= price && v > under) under = v;
			if (smallest == 0 || v < smallest) smallest = v;
		}
		return under > 0 ? under : smallest;
	}

	public static boolean hasCoupon(String uuid, int value) {
		Holder h = holders.get(uuid);
		return h != null && h.coupons.getOrDefault(value, 0) > 0;
	}

	public static int couponCount(String uuid) {
		Holder h = holders.get(uuid);
		return h == null ? 0 : h.coupons.values().stream().mapToInt(Integer::intValue).sum();
	}

	/** Uses one coupon worth that much; false when there's none. */
	public static synchronized boolean useCoupon(String uuid, int value) {
		Holder h = holders.get(uuid);
		if (h == null) return false;
		Integer n = h.coupons.get(value);
		if (n == null || n <= 0) return false;
		if (n == 1) h.coupons.remove(value);
		else h.coupons.put(value, n - 1);
		changed();
		return true;
	}

	/** The price with a coupon: whole items off, never below 0 (a big coupon makes it free). */
	public static long discounted(long price, int value) {
		return value <= 0 ? price : Math.max(0, price - value);
	}

	/** "a 3-diamond coupon" in the market's currency. */
	public static String couponName(int value) {
		String plural = eu.explorerseden.nicecontrolcenter.market.MarketStore.currencyName();
		String one = plural.endsWith("s") ? plural.substring(0, plural.length() - 1) : plural;
		return value + "-" + one + " coupon";
	}

	// ---------------------------------------------------------------- giving

	private static Holder holderFor(String uuid, String name) {
		Holder h = holders.computeIfAbsent(uuid, k -> new Holder());
		if (name != null) h.name = name;
		return h;
	}

	/** Adds time (ms, -1 = for good) to a perk and keeps the higher level. Returns the time left now. */
	public static synchronized long add(String uuid, String name, Kind k, int level, long ms) {
		Holder h = holderFor(uuid, name);
		Perk p = h.perks.get(k.name());
		if (p == null || (p.left <= 0 && p.left != -1)) p = new Perk(0, level);
		if (ms == -1 || p.left == -1) p.left = -1;
		else p.left += ms;
		p.level = Math.max(p.level, level);
		h.perks.put(k.name(), p);
		changed();
		return p.left;
	}

	public static synchronized boolean take(String uuid, Kind k) {
		Holder h = holders.get(uuid);
		if (h == null || h.perks.remove(k.name()) == null) return false;
		changed();
		return true;
	}

	public static synchronized void addCoupons(String uuid, String name, int value, int count) {
		Holder h = holderFor(uuid, name);
		h.coupons.merge(value, count, Integer::sum);
		h.coupons.values().removeIf(n -> n <= 0);
		changed();
	}

	public static synchronized boolean takeCoupons(String uuid) {
		Holder h = holders.get(uuid);
		if (h == null || h.coupons.isEmpty()) return false;
		h.coupons.clear();
		changed();
		return true;
	}

	public static synchronized void setJoinText(String uuid, String name, String text) {
		holderFor(uuid, name).joinText = text;
		changed();
	}

	public static void changed() {
		dirty = true;
	}

	// ---------------------------------------------------------------- the clock

	private static void tick(MinecraftServer server, boolean second) {
		long now = System.currentTimeMillis();
		long elapsed = lastTick == 0 ? 0 : Math.min(5_000, now - lastTick);
		lastTick = now;
		if (holders.isEmpty()) return;
		Map<String, ServerPlayer> online = new java.util.HashMap<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) online.put(p.getStringUUID(), p);
		for (Map.Entry<String, Holder> e : holders.entrySet()) {
			String uuid = e.getKey();
			Holder h = e.getValue();
			ServerPlayer p = online.get(uuid);
			for (Map.Entry<String, Perk> pe : List.copyOf(h.perks.entrySet())) {
				Kind k = Kind.of(pe.getKey());
				Perk perk = pe.getValue();
				if (k == null || perk.left == -1) {
					if (k == Kind.HASTE && p != null) haste(p, perk.level);
					continue;
				}
				boolean runs = switch (k.clock) {
					case ACTIVE -> p != null && !PlayerActivity.isAfk(p);
					case IN_CLAIM -> p != null && !PlayerActivity.isAfk(p) && inOwnClaim(p);
					case LISTING -> eu.explorerseden.nicecontrolcenter.market.MarketStore.data().of(uuid).size() > 0;
					case REAL -> true;
				};
				if (k == Kind.HASTE && runs) haste(p, perk.level);
				if (!runs) continue;
				perk.left -= elapsed;
				if (perk.left <= 0) {
					h.perks.remove(pe.getKey());
					changed();
					if (p != null) p.sendSystemMessage(Component.literal("Your " + k.label.toLowerCase(Locale.ROOT) + " has run out.").withStyle(ChatFormatting.YELLOW));
					continue;
				}
				if (second && p != null && k.clock != Clock.REAL && k.clock != Clock.LISTING) {
					long s = perk.left / 1000;
					if (s == 60 || s == 10) p.sendOverlayMessage(Component.literal(k.label + ": " + s + " s left").withStyle(ChatFormatting.YELLOW));
				}
			}
		}
		if (second) syncSpotlight();
		if (dirty && now - lastSave > 30_000) save();
	}

	/** Marks the listings of sellers with a spotlight, so /market, the panel and the website show them first. */
	private static void syncSpotlight() {
		var data = eu.explorerseden.nicecontrolcenter.market.MarketStore.data();
		boolean any = false;
		for (var l : data.listings.values()) {
			boolean want = has(l.seller, Kind.SPOTLIGHT);
			if (l.spotlight == want) continue;
			l.spotlight = want;
			l.rev = data.bump();
			any = true;
		}
		if (any) eu.explorerseden.nicecontrolcenter.market.MarketStore.saveNow();
	}

	private static void haste(ServerPlayer p, int level) {
		if (!inOwnClaim(p)) return;
		MobEffectInstance cur = p.getEffect(MobEffects.HASTE);
		if (cur == null || cur.getDuration() < 40 || cur.getAmplifier() < level - 1) {
			p.addEffect(new MobEffectInstance(MobEffects.HASTE, 100, Math.max(0, level - 1), true, false, true));
		}
	}

	/** Inside a claim (or subclaim) the player owns or is at least a Builder in. */
	public static boolean inOwnClaim(ServerPlayer p) {
		return inOwnClaim(p.level(), p.blockPosition(), p.getStringUUID());
	}

	public static boolean inOwnClaim(Level level, BlockPos pos, String uuid) {
		var claim = eu.explorerseden.nicecontrolcenter.claims.ClaimStore.claimAt(level, pos);
		if (claim == null) return false;
		var role = eu.explorerseden.nicecontrolcenter.claims.ClaimStore.data().roleOf(claim, uuid);
		return role != null && role.includes(Claims.Role.BUILD);
	}

	/** "1 h 20 min", "45 s", "for good". */
	public static String human(long ms) {
		if (ms == -1) return "for good";
		long s = Math.max(0, ms) / 1000;
		if (s < 120) return s + " s";
		long m = s / 60;
		if (m < 120) return m + " min";
		long h = m / 60;
		if (h < 48) return h + " h" + (m % 60 > 0 ? " " + m % 60 + " min" : "");
		return h / 24 + " days" + (h % 24 > 0 ? " " + h % 24 + " h" : "");
	}

	/** The level as players read it: "×2" or "II". */
	public static String levelText(Kind k, int level) {
		if (k.multiplier) {
			double f = level / 10.0;
			return "×" + (f == Math.floor(f) ? String.valueOf((int) f) : String.valueOf(f));
		}
		return k == Kind.HASTE ? new String[] { "", "I", "II", "III" }[Math.max(1, Math.min(3, level))] : "";
	}

	// ---------------------------------------------------------------- storage

	private static void load(MinecraftServer server) {
		file = server.getWorldPath(LevelResource.ROOT).resolve("nicecontrolcenter-perks.json");
		holders.clear();
		lastTick = 0;
		try {
			if (Files.exists(file)) {
				Map<String, Holder> saved = Json.GSON.fromJson(Files.readString(file), new com.google.gson.reflect.TypeToken<Map<String, Holder>>() { }.getType());
				if (saved != null) saved.forEach((k, v) -> {
					if (v.perks == null) v.perks = new LinkedHashMap<>();
					if (v.coupons == null) v.coupons = new TreeMap<>();
					holders.put(k, v);
				});
			}
		} catch (IOException | RuntimeException e) {
			NiceControlCenter.LOGGER.warn("Could not read the players' perks: {}", e.getMessage());
		}
	}

	public static synchronized void save() {
		lastSave = System.currentTimeMillis();
		dirty = false;
		if (file == null) return;
		holders.values().removeIf(h -> h.perks.isEmpty() && h.coupons.isEmpty() && (h.joinText == null || h.joinText.isBlank()));
		try {
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, Json.GSON.toJson(holders), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			NiceControlCenter.LOGGER.warn("Could not save the players' perks: {}", e.getMessage());
		}
	}
}
