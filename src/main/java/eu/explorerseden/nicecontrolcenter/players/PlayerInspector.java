package eu.explorerseden.nicecontrolcenter.players;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;

import eu.explorerseden.nicecontrolcenter.ControlCenterConfig;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;

/**
 * Live view of players: online players straight from the server, offline players from their save
 * file. Adds the Explorer's Eden player database (homes, graves, waypoints) from command storage and
 * Get Off My Lawn claims when they exist; on other servers those sections just stay empty.
 */
public final class PlayerInspector {
	private static final List<String> RACES = List.of("aetherian", "dunesworn", "endling", "frostborne", "moonshroud", "netherian",
			"oakhearted", "orebringer", "palehearted", "turtlekin");
	private static final List<String> CLASSES = List.of("archer", "bard", "builder", "cleric", "fighter", "hermit", "miner", "rancher",
			"scout", "survivor");
	private static final Map<String, String> DIMENSIONS = Map.of("minecraft:overworld", "Overworld", "minecraft:the_nether", "The Nether",
			"minecraft:the_end", "The End");
	private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	/** Offline save files, read once per change. */
	private record Cached(long modified, CompoundTag data) {
	}

	private static final Map<Path, Cached> files = new ConcurrentHashMap<>();

	/** What only the server thread may read: online players and the command storage. */
	public record Live(List<Map<String, Object>> online, Map<String, CompoundTag> storageByName, CompoundTag hubs,
			Map<String, Map<String, Object>> bans) {
	}

	private PlayerInspector() {
	}

	// ── Server thread ───────────────────────────────────────────────────────

	/**
	 * Online players and the player database. With a {@code detailKey} (UUID or "name:<name>"), that
	 * one player also gets full live details and a copy of their database entry. Server thread.
	 */
	public static Live live(MinecraftServer server, String storageId, String detailKey) {
		UUID detailUuid = null;
		String detailName = null;
		if (detailKey != null && detailKey.startsWith("name:")) {
			detailName = detailKey.substring(5).toLowerCase(Locale.ROOT);
		} else if (detailKey != null) {
			try {
				detailUuid = UUID.fromString(detailKey);
				detailName = name(server, detailUuid).map(n -> n.toLowerCase(Locale.ROOT)).orElse(null);
			} catch (IllegalArgumentException e) {
				// Unknown key; no details.
			}
		}
		List<Map<String, Object>> online = new ArrayList<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			boolean detail = player.getUUID().equals(detailUuid);
			if (detail) {
				detailName = player.getGameProfile().name().toLowerCase(Locale.ROOT);
			}
			online.add(detail ? onlineDetail(server, player) : onlineSummary(player));
		}
		Map<String, CompoundTag> byName = new HashMap<>();
		CompoundTag hubs = new CompoundTag();
		Identifier id = Identifier.tryParse(storageId == null ? "" : storageId);
		if (id != null) {
			CompoundTag database = server.getCommandStorage().get(id);
			CompoundTag players = database.getCompoundOrEmpty("player");
			for (String key : players.keySet()) {
				CompoundTag entry = players.getCompoundOrEmpty(key);
				String name = entry.getStringOr("displayname", "");
				if (name.isEmpty()) {
					continue;
				}
				String lower = name.toLowerCase(Locale.ROOT);
				if (lower.equals(detailName)) {
					byName.put(lower, entry.copy());
				} else {
					CompoundTag minimal = new CompoundTag();
					minimal.putString("displayname", name);
					byName.put(lower, minimal);
				}
			}
			if (detailName != null) {
				hubs = database.getCompoundOrEmpty("waypoints").getCompoundOrEmpty("hubs").copy();
			}
		}
		// Ban entries of everyone who has played (keyed by UUID).
		Map<String, Map<String, Object>> bans = new HashMap<>();
		net.minecraft.server.players.UserBanList banList = server.getPlayerList().getBans();
		if (!banList.isEmpty()) {
			List<UUID> known = new ArrayList<>();
			server.getPlayerList().getPlayers().forEach(pl -> known.add(pl.getUUID()));
			for (Path file : saveFiles(server)) {
				String fileName = file.getFileName().toString();
				try {
					known.add(UUID.fromString(fileName.substring(0, fileName.length() - 4)));
				} catch (IllegalArgumentException e) {
					// Not a player file.
				}
			}
			for (UUID playerId : known) {
				var entry = banList.get(new NameAndId(playerId, name(server, playerId).orElse("")));
				if (entry != null && !entry.hasExpired()) {
					Map<String, Object> ban = new LinkedHashMap<>();
					ban.put("reason", entry.getReason());
					ban.put("by", entry.getSource());
					ban.put("since", entry.getCreated() == null ? null : entry.getCreated().getTime());
					ban.put("until", entry.getExpires() == null ? null : entry.getExpires().getTime());
					bans.put(playerId.toString(), ban);
				}
			}
		}
		return new Live(online, byName, hubs, bans);
	}

	/** The x-ray check for a list or card entry: live counts for online players, the saved file otherwise. */
	private static Map<String, Object> mining(MinecraftServer server, Map<String, Object> p) {
		Object live = p.get("_counts");
		XrayCheck.Counts counts = live instanceof XrayCheck.Counts c ? c
				: p.get("uuid") == null ? null : XrayCheck.counts(server, UUID.fromString((String) p.get("uuid")));
		return XrayCheck.judge(server, counts);
	}

	private static Map<String, Object> onlineSummary(ServerPlayer player) {
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("uuid", player.getUUID().toString());
		p.put("name", player.getGameProfile().name());
		p.put("online", true);
		raceAndClass(player.entityTags(), p);
		p.put("dimension", dimension(player.level().dimension().identifier().toString()));
		p.put("ping", player.connection.latency());
		p.put("client", ClientInfo.brandLabel(player.getUUID()));
		// Live mining statistics; judged later off the server thread (the comparison reads files).
		p.put("_counts", XrayCheck.counts(player));
		return p;
	}

	private static Map<String, Object> onlineDetail(MinecraftServer server, ServerPlayer player) {
		Map<String, Object> p = onlineSummary(player);
		Map<String, Object> status = new LinkedHashMap<>();
		BlockPos pos = player.blockPosition();
		status.put("x", pos.getX());
		status.put("y", pos.getY());
		status.put("z", pos.getZ());
		status.put("dimension", p.get("dimension"));
		status.put("dimensionId", player.level().dimension().identifier().toString());
		status.put("health", Math.round(player.getHealth()));
		status.put("food", player.getFoodData().getFoodLevel());
		status.put("xpLevel", player.experienceLevel);
		status.put("gameMode", player.gameMode().getName());
		status.put("ping", player.connection.latency());
		p.put("status", status);
		p.put("tags", List.copyOf(player.entityTags()));
		p.put("clientInfo", ClientInfo.describe(player));

		Inventory inventory = player.getInventory();
		List<Map<String, Object>> items = new ArrayList<>();
		for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
			addItem(items, inventory.getNonEquipmentItems().get(slot), slot);
		}
		p.put("inventory", items);
		List<Map<String, Object>> armor = new ArrayList<>();
		for (EquipmentSlot slot : ARMOR) {
			addItem(armor, player.getItemBySlot(slot), null);
		}
		addItem(armor, player.getItemBySlot(EquipmentSlot.OFFHAND), null);
		p.put("equipment", armor);
		List<Map<String, Object>> ender = new ArrayList<>();
		for (int slot = 0; slot < player.getEnderChestInventory().getContainerSize(); slot++) {
			addItem(ender, player.getEnderChestInventory().getItem(slot), slot);
		}
		p.put("enderChest", ender);
		player.getLastDeathLocation().ifPresent(death -> p.put("vanillaDeath",
				loc(death.pos().getX(), death.pos().getY(), death.pos().getZ(), death.dimension().identifier().toString())));
		return p;
	}

	// ── Any thread ──────────────────────────────────────────────────────────

	/** Everyone who has played (online first), plus database entries without a save file. */
	public static List<Map<String, Object>> list(MinecraftServer server, Live live) {
		Map<String, Map<String, Object>> byUuid = new LinkedHashMap<>();
		for (Map<String, Object> p : live.online()) {
			byUuid.put((String) p.get("uuid"), p);
		}
		List<Map<String, Object>> offline = new ArrayList<>();
		for (Path file : saveFiles(server)) {
			String name = file.getFileName().toString();
			String uuid = name.substring(0, name.length() - 4);
			if (byUuid.containsKey(uuid)) {
				continue;
			}
			UUID id;
			try {
				id = UUID.fromString(uuid);
			} catch (IllegalArgumentException e) {
				continue;
			}
			Map<String, Object> p = new LinkedHashMap<>();
			p.put("uuid", uuid);
			p.put("name", name(server, id).orElse(uuid.substring(0, 8)));
			p.put("online", false);
			CompoundTag data = read(file);
			if (data != null) {
				List<String> tags = new ArrayList<>();
				data.getListOrEmpty("Tags").forEach(t -> t.asString().ifPresent(tags::add));
				raceAndClass(tags, p);
				p.put("dimension", dimension(data.getStringOr("Dimension", "")));
			}
			try {
				p.put("lastSeen", Files.getLastModifiedTime(file).toMillis());
			} catch (IOException e) {
				// Unknown.
			}
			offline.add(p);
		}
		offline.sort(Comparator.comparing((Map<String, Object> p) -> ((String) p.get("name")).toLowerCase(Locale.ROOT)));
		List<Map<String, Object>> result = new ArrayList<>(byUuid.values());
		result.sort(Comparator.comparing((Map<String, Object> p) -> ((String) p.get("name")).toLowerCase(Locale.ROOT)));
		result.addAll(offline);
		// Mark who has Explorer's Eden data, and add database entries nobody's save file matches.
		java.util.Set<String> names = new java.util.HashSet<>();
		for (Map<String, Object> p : result) {
			String lower = ((String) p.get("name")).toLowerCase(Locale.ROOT);
			names.add(lower);
			p.put("hasData", live.storageByName().containsKey(lower));
			p.put("banned", p.get("uuid") != null && live.bans().containsKey((String) p.get("uuid")));
			p.put("unread", p.get("uuid") != null ? Conversations.unread(UUID.fromString((String) p.get("uuid"))) : 0);
			Map<String, Object> mining = mining(server, p);
			p.put("xray", mining == null ? List.of() : mining.get("flags"));
			p.remove("_counts");
		}
		live.storageByName().forEach((lower, entry) -> {
			if (!names.contains(lower)) {
				Map<String, Object> p = new LinkedHashMap<>();
				p.put("uuid", null);
				p.put("key", "name:" + entry.getStringOr("displayname", lower));
				p.put("name", entry.getStringOr("displayname", lower));
				p.put("online", false);
				p.put("hasData", true);
				result.add(p);
			}
		});
		return result;
	}

	/** Full card for one player: {@code key} is a UUID or "name:<name>". */
	public static Map<String, Object> detail(MinecraftServer server, String key, Live live) {
		UUID uuid = null;
		String name = null;
		if (key.startsWith("name:")) {
			name = key.substring(5);
			// Only what the server already knows: looking a name up by itself would ask Mojang every refresh.
			String wanted = name;
			uuid = live.online().stream().filter(o -> wanted.equalsIgnoreCase((String) o.get("name"))).findFirst()
					.map(o -> UUID.fromString((String) o.get("uuid"))).orElse(null);
		} else {
			try {
				uuid = UUID.fromString(key);
			} catch (IllegalArgumentException e) {
				return null;
			}
		}
		Map<String, Object> p = null;
		if (uuid != null) {
			String uuidText = uuid.toString();
			p = live.online().stream().filter(o -> uuidText.equals(o.get("uuid"))).findFirst().map(LinkedHashMap::new).orElse(null);
		}
		RegistryOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
		if (p == null) {
			p = new LinkedHashMap<>();
			p.put("uuid", uuid == null ? null : uuid.toString());
			p.put("online", false);
			String resolved = uuid == null ? null : name(server, uuid).orElse(null);
			p.put("name", resolved != null ? resolved : name != null ? name : uuid == null ? "Unknown" : uuid.toString().substring(0, 8));
			Path file = uuid == null ? null : server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(uuid + ".dat");
			CompoundTag data = file == null ? null : read(file);
			if (data != null) {
				offlineDetail(data, p, ops);
				try {
					p.put("lastSeen", Files.getLastModifiedTime(file).toMillis());
				} catch (IOException e) {
					// Unknown.
				}
			}
		}
		Map<String, Object> card = p;
		String lower = ((String) p.get("name")).toLowerCase(Locale.ROOT);
		CompoundTag entry = live.storageByName().get(lower);
		if (entry != null) {
			p.put("home", storedLoc(entry.getCompound("home")));
			p.put("back", storedLoc(entry.getCompound("back")));
			p.put("lastSafePos", storedLoc(entry.getCompound("last_safe_pos")));
			p.put("lastDeath", storedLoc(entry.getCompound("last_death_loc")));
			entry.getCompound("last_grave").ifPresent(grave -> {
				Map<String, Object> g = storedLoc(Optional.of(grave));
				g.put("removed", grave.getBooleanOr("removed", false));
				g.put("openedBy", grave.getCompoundOrEmpty("opened_by").getString("name").orElse(null));
				g.put("contents", items(grave.getListOrEmpty("contents"), ops));
				card.put("grave", g);
			});
		}
		if (p.get("lastDeath") == null && p.get("vanillaDeath") != null) {
			p.put("lastDeath", p.get("vanillaDeath"));
		}
		p.remove("vanillaDeath");
		p.put("waypoints", waypoints(live.hubs(), (String) p.get("uuid"), lower));
		if (p.get("uuid") != null) {
			p.put("claims", Claims.forOwner(server, UUID.fromString((String) p.get("uuid"))));
			p.put("ban", live.bans().get((String) p.get("uuid")));
			UUID who = UUID.fromString((String) p.get("uuid"));
			Conversations.markRead(who);
			p.put("messages", Conversations.thread(who));
			p.put("mining", mining(server, p));
		}
		p.remove("_counts");
		return p;
	}

	private static void offlineDetail(CompoundTag data, Map<String, Object> p, RegistryOps<Tag> ops) {
		List<String> tags = new ArrayList<>();
		data.getListOrEmpty("Tags").forEach(t -> t.asString().ifPresent(tags::add));
		raceAndClass(tags, p);
		p.put("tags", tags);
		ListTag pos = data.getListOrEmpty("Pos");
		Map<String, Object> status = new LinkedHashMap<>();
		if (pos.size() == 3) {
			status.put("x", (int) Math.floor(pos.getDoubleOr(0, 0)));
			status.put("y", (int) Math.floor(pos.getDoubleOr(1, 0)));
			status.put("z", (int) Math.floor(pos.getDoubleOr(2, 0)));
		}
		String dimensionId = data.getStringOr("Dimension", "minecraft:overworld");
		status.put("dimension", dimension(dimensionId));
		status.put("dimensionId", dimensionId);
		status.put("health", Math.round(data.getFloatOr("Health", 20)));
		status.put("food", data.getIntOr("foodLevel", 20));
		status.put("xpLevel", data.getIntOr("XpLevel", 0));
		status.put("gameMode", GameType.byId(data.getIntOr("playerGameType", 0)).getName());
		status.put("lastSave", true);
		p.put("status", status);
		p.put("dimension", status.get("dimension"));
		p.put("inventory", items(data.getListOrEmpty("Inventory"), ops));
		CompoundTag equipment = data.getCompoundOrEmpty("equipment");
		List<Map<String, Object>> armor = new ArrayList<>();
		for (String slot : List.of("head", "chest", "legs", "feet", "offhand")) {
			equipment.getCompound(slot).ifPresent(tag -> addItem(armor, parse(tag, ops), null));
		}
		p.put("equipment", armor);
		p.put("enderChest", items(data.getListOrEmpty("EnderItems"), ops));
		data.getCompound("LastDeathLocation").ifPresent(death -> death.getIntArray("pos").filter(a -> a.length == 3)
				.ifPresent(a -> p.put("vanillaDeath", loc(a[0], a[1], a[2], death.getStringOr("dimension", "minecraft:overworld")))));
	}

	// ── Pieces ──────────────────────────────────────────────────────────────

	private static List<Path> saveFiles(MinecraftServer server) {
		Path dir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> stream = Files.list(dir)) {
			return stream.filter(f -> f.getFileName().toString().endsWith(".dat")).toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	/** A save file, cached until it changes. Null if missing or unreadable. */
	static CompoundTag read(Path file) {
		try {
			long modified = Files.getLastModifiedTime(file).toMillis();
			Cached cached = files.get(file);
			if (cached != null && cached.modified() == modified) {
				return cached.data();
			}
			CompoundTag data = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
			files.put(file, new Cached(modified, data));
			return data;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	static Optional<String> name(MinecraftServer server, UUID uuid) {
		return server.services().nameToIdCache().get(uuid).map(NameAndId::name);
	}

	private static void raceAndClass(Iterable<String> tags, Map<String, Object> p) {
		for (String tag : tags) {
			if (!tag.startsWith("fabled_roots.")) {
				continue;
			}
			String value = tag.substring("fabled_roots.".length());
			if (RACES.contains(value)) {
				p.put("race", title(value));
			} else if (CLASSES.contains(value)) {
				p.put("class", title(value));
			}
		}
	}

	private static List<Map<String, Object>> items(ListTag list, RegistryOps<Tag> ops) {
		List<Map<String, Object>> result = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) {
			CompoundTag tag = list.getCompoundOrEmpty(i);
			Integer slot = tag.contains("Slot") ? (int) tag.getByteOr("Slot", (byte) 0) : null;
			addItem(result, parse(tag, ops), slot);
		}
		return result;
	}

	private static ItemStack parse(CompoundTag tag, RegistryOps<Tag> ops) {
		CompoundTag clean = tag.copy();
		clean.remove("Slot");
		return ItemStack.CODEC.parse(ops, clean).result().orElse(ItemStack.EMPTY);
	}

	private static void addItem(List<Map<String, Object>> out, ItemStack stack, Integer slot) {
		if (stack.isEmpty()) {
			return;
		}
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("slot", slot);
		item.put("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		item.put("name", stack.getHoverName().getString());
		item.put("count", stack.getCount());
		List<String> enchantments = new ArrayList<>();
		enchantNames(stack.getEnchantments(), enchantments);
		enchantNames(stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY), enchantments);
		item.put("enchantments", enchantments);
		item.put("head", stack.getItem() == net.minecraft.world.item.Items.PLAYER_HEAD);
		out.add(item);
	}

	private static void enchantNames(ItemEnchantments enchantments, List<String> out) {
		for (var entry : enchantments.entrySet()) {
			Holder<Enchantment> holder = entry.getKey();
			out.add(Enchantment.getFullname(holder, entry.getIntValue()).getString());
		}
	}

	private static Map<String, Object> storedLoc(Optional<CompoundTag> tag) {
		if (tag.isEmpty() || !tag.get().contains("x")) {
			return null;
		}
		CompoundTag t = tag.get();
		return loc((int) Math.floor(number(t.get("x"))), (int) Math.floor(number(t.get("y"))), (int) Math.floor(number(t.get("z"))),
				t.getStringOr("dimension", "minecraft:overworld"));
	}

	private static double number(Tag tag) {
		return tag == null ? 0 : tag.asNumber().map(Number::doubleValue).orElse(0.0);
	}

	static Map<String, Object> loc(int x, int y, int z, String dimensionId) {
		Map<String, Object> l = new LinkedHashMap<>();
		l.put("x", x);
		l.put("y", y);
		l.put("z", z);
		l.put("dimension", dimension(dimensionId));
		l.put("dimensionId", dimensionId);
		return l;
	}

	private static List<Map<String, Object>> waypoints(CompoundTag hubs, String uuid, String lowerName) {
		List<Map<String, Object>> result = new ArrayList<>();
		for (String key : hubs.keySet()) {
			CompoundTag hub = hubs.getCompoundOrEmpty(key);
			CompoundTag profile = hub.getCompoundOrEmpty("profile");
			String owner = profile.getIntArray("id").filter(a -> a.length == 4).map(a -> UUIDUtil.uuidFromIntArray(a).toString()).orElse(null);
			boolean mine = (uuid != null && uuid.equals(owner)) || profile.getStringOr("name", "").toLowerCase(Locale.ROOT).equals(lowerName);
			if (!mine) {
				continue;
			}
			CompoundTag pos = hub.getCompoundOrEmpty("pos");
			Map<String, Object> w = loc((int) Math.floor(number(pos.get("x"))), (int) Math.floor(number(pos.get("y"))),
					(int) Math.floor(number(pos.get("z"))), pos.getStringOr("dimension", "minecraft:overworld"));
			w.put("name", hub.getStringOr("waypoint_name", "Unnamed Waypoint"));
			w.put("description", hub.getStringOr("waypoint_description", "").trim());
			w.put("access", hub.getStringOr("access", "private"));
			String shown = hub.getStringOr("dimension_name", "");
			if (!shown.isEmpty()) {
				w.put("dimension", shown);
			}
			result.add(w);
		}
		result.sort(Comparator.comparing(w -> ((String) w.get("name")).toLowerCase(Locale.ROOT)));
		return result;
	}

	static String dimension(String id) {
		if (id == null || id.isEmpty()) {
			return "Unknown";
		}
		String known = DIMENSIONS.get(id);
		if (known != null) {
			return known;
		}
		String bare = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		StringBuilder out = new StringBuilder();
		for (String word : bare.split("[_\\-/]+")) {
			if (!word.isEmpty()) {
				out.append(out.isEmpty() ? "" : " ").append(title(word));
			}
		}
		return out.toString();
	}

	private static String title(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	/** Get Off My Lawn claims, read from each dimension's saved component file (as fresh as the last save). */
	static final class Claims {
		private Claims() {
		}

		static List<Map<String, Object>> forOwner(MinecraftServer server, UUID owner) {
			List<Map<String, Object>> result = new ArrayList<>();
			if (!FabricLoader.getInstance().isModLoaded("goml")) {
				return result;
			}
			Path root = server.getWorldPath(LevelResource.ROOT);
			for (ServerLevel level : server.getAllLevels()) {
				Identifier dim = level.dimension().identifier();
				Path file = root.resolve("dimensions").resolve(dim.getNamespace()).resolve(dim.getPath()).resolve("data")
						.resolve("cardinal-components").resolve("world.dat");
				CompoundTag data = Files.exists(file) ? read(file) : null;
				if (data == null) {
					continue;
				}
				ListTag claims = data.getCompoundOrEmpty("data").getCompoundOrEmpty("cardinal_components").getCompoundOrEmpty("goml:claims")
						.getListOrEmpty("Claims");
				for (int i = 0; i < claims.size(); i++) {
					CompoundTag claim = claims.getCompoundOrEmpty(i);
					if (!uuids(claim.getListOrEmpty("Owners")).contains(owner)) {
						continue;
					}
					BlockPos origin = BlockPos.of(claim.getCompoundOrEmpty("Box").getLongOr("OriginPos", 0));
					Map<String, Object> c = loc(origin.getX(), origin.getY(), origin.getZ(), dim.toString());
					String type = claim.getStringOr("Type", "");
					c.put("anchor", dimension(type.isEmpty() ? "unknown_anchor" : type));
					List<String> trusted = new ArrayList<>();
					for (UUID id : uuids(claim.getListOrEmpty("Trusted"))) {
						trusted.add(name(server, id).orElse(id.toString().substring(0, 8)));
					}
					c.put("trusted", trusted);
					result.add(c);
				}
			}
			return result;
		}

		private static List<UUID> uuids(ListTag list) {
			List<UUID> result = new ArrayList<>();
			for (int i = 0; i < list.size(); i++) {
				list.getIntArray(i).filter(a -> a.length == 4).ifPresent(a -> result.add(UUIDUtil.uuidFromIntArray(a)));
			}
			return result;
		}
	}

	/** kick | message (online players), ban | unban (anyone). Returns an error or null. Server thread. */
	public static String action(MinecraftServer server, String uuid, String action, String text) {
		UUID id;
		try {
			id = UUID.fromString(uuid);
		} catch (IllegalArgumentException e) {
			return "Unknown player.";
		}
		ServerPlayer player = server.getPlayerList().getPlayer(id);
		String name = player != null ? player.getGameProfile().name() : name(server, id).orElse(null);
		if (name == null || !name.matches("[A-Za-z0-9_]{1,16}")) {
			return "This player's name isn't known to the server.";
		}
		String clean = text == null ? "" : text.replaceAll("[\\r\\n]+", " ").strip();
		if (clean.length() > 256) {
			clean = clean.substring(0, 256);
		}
		boolean banned = server.getPlayerList().getBans().isBanned(new NameAndId(id, name));
		switch (action) {
			case "kick", "message" -> {
				if (player == null) {
					return "That player isn't online.";
				}
				if (action.equals("kick")) {
					eu.explorerseden.nicecontrolcenter.log.ConsoleRunner.run(server, "kick " + name + (clean.isEmpty() ? "" : " " + clean));
				} else {
					if (clean.isEmpty()) {
						return "Type a message first.";
					}
					com.google.gson.JsonArray json = new com.google.gson.JsonArray();
					com.google.gson.JsonObject prefix = new com.google.gson.JsonObject();
					var config = NiceControlCenter.config();
					String label = config == null || config.message_prefix == null ? "[Admin]" : config.message_prefix.strip();
					String color = config == null ? "gold" : ControlCenterConfig.color(config.message_color);
					if (color == null) {
						NiceControlCenter.LOGGER.warn("message_color \"{}\" isn't a color; using gold. Use a Minecraft color name or #RRGGBB.",
								config.message_color);
						color = "gold";
					}
					prefix.addProperty("text", label.isEmpty() ? "" : label + " ");
					prefix.addProperty("color", color);
					json.add(prefix);
					com.google.gson.JsonObject body = new com.google.gson.JsonObject();
					body.addProperty("text", clean + " ");
					json.add(body);
					// A button that fills in /nccreply, so the player can answer without public chat.
					com.google.gson.JsonObject answer = new com.google.gson.JsonObject();
					answer.addProperty("text", "[Answer]");
					answer.addProperty("color", "aqua");
					answer.addProperty("underlined", true);
					com.google.gson.JsonObject click = new com.google.gson.JsonObject();
					click.addProperty("action", "suggest_command");
					click.addProperty("command", "/nccreply ");
					answer.add("click_event", click);
					com.google.gson.JsonObject hover = new com.google.gson.JsonObject();
					hover.addProperty("action", "show_text");
					hover.addProperty("value", "Answer privately; only the admins see it");
					answer.add("hover_event", hover);
					json.add(answer);
					eu.explorerseden.nicecontrolcenter.log.ConsoleRunner.run(server, "tellraw " + name + " " + json);
					Conversations.fromAdmin(id, clean);
				}
			}
			case "ban" -> {
				if (banned) {
					return name + " is already banned.";
				}
				eu.explorerseden.nicecontrolcenter.log.ConsoleRunner.run(server, "ban " + name + (clean.isEmpty() ? "" : " " + clean));
				if (!server.getPlayerList().getBans().isBanned(new NameAndId(id, name))) {
					return "The ban didn't go through; see the console.";
				}
			}
			case "unban" -> {
				if (!banned) {
					return name + " isn't banned.";
				}
				eu.explorerseden.nicecontrolcenter.log.ConsoleRunner.run(server, "pardon " + name);
				if (server.getPlayerList().getBans().isBanned(new NameAndId(id, name))) {
					return "The unban didn't go through; see the console.";
				}
			}
			default -> {
				return "Unknown action.";
			}
		}
		NiceControlCenter.LOGGER.info("Players: {} {} from the dashboard", action, name);
		return null;
	}
}
