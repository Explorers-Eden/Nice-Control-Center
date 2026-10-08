package eu.explorerseden.nicecontrolcenter.players;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * What a player's game says about itself: the client brand it sends when joining, and the network
 * channels its mods register. Mods that never talk to the server (Sodium, shaders, most cheat
 * clients) can't be seen; the brand is self-reported.
 */
public final class ClientInfo {
	private static final Map<UUID, String> brands = new ConcurrentHashMap<>();

	/** Channel namespaces of well-known mods, with a readable name. */
	private static final Map<String, String> KNOWN = Map.ofEntries(
			Map.entry("voicechat", "Simple Voice Chat"), Map.entry("plasmovoice", "Plasmo Voice"),
			Map.entry("xaerominimap", "Xaero's Minimap"), Map.entry("xaeroworldmap", "Xaero's World Map"),
			Map.entry("xaero_minimap", "Xaero's Minimap"), Map.entry("xaero_worldmap", "Xaero's World Map"),
			Map.entry("journeymap", "JourneyMap"), Map.entry("voxelmap", "VoxelMap"), Map.entry("ftbchunks", "FTB Chunks"),
			Map.entry("jei", "JEI"), Map.entry("roughlyenoughitems", "REI"), Map.entry("rei", "REI"), Map.entry("emi", "EMI"),
			Map.entry("appleskin", "AppleSkin"), Map.entry("jade", "Jade"), Map.entry("wthit", "WTHIT"), Map.entry("waila", "WAILA"),
			Map.entry("distanthorizons", "Distant Horizons"), Map.entry("axiom", "Axiom"), Map.entry("worldedit", "WorldEdit CUI"),
			Map.entry("litematica", "Litematica"), Map.entry("servux", "Servux / MiniHUD"), Map.entry("minihud", "MiniHUD"),
			Map.entry("tweakeroo", "Tweakeroo"), Map.entry("itemscroller", "Item Scroller"), Map.entry("carpet", "Carpet"),
			Map.entry("figura", "Figura"), Map.entry("emotecraft", "Emotecraft"), Map.entry("essential", "Essential"),
			Map.entry("worldhost", "World Host"), Map.entry("e4mc", "e4mc"), Map.entry("noxesium", "Noxesium"),
			Map.entry("bobby", "Bobby"), Map.entry("chunky", "Chunky"), Map.entry("inventoryprofilesnext", "Inventory Profiles Next"),
			Map.entry("mousetweaks", "Mouse Tweaks"), Map.entry("modmenu", "Mod Menu"), Map.entry("sodium", "Sodium"),
			Map.entry("iris", "Iris"), Map.entry("lithium", "Lithium"), Map.entry("c2me", "C2ME"), Map.entry("krypton", "Krypton"),
			Map.entry("polymer", "Polymer"), Map.entry("geyser", "Geyser"), Map.entry("floodgate", "Floodgate"),
			Map.entry("viafabric", "ViaFabric"), Map.entry("viafabricplus", "ViaFabricPlus"), Map.entry("replaymod", "ReplayMod"),
			Map.entry("flashback", "Flashback"), Map.entry("simple_voice_chat", "Simple Voice Chat"));
	/** Channels the game or the mod loader itself registers; they say nothing about the player's mods. */
	private static final Set<String> IGNORED = Set.of("minecraft", "c", "fabric", "fabricloader", "fabric-api", "quilt", "neoforge",
			"forge", "nicecontrolcenter");

	private ClientInfo() {
	}

	public static void brand(UUID player, String brand) {
		if (player != null && brand != null) {
			brands.put(player, brand.length() > 64 ? brand.substring(0, 64) : brand);
		}
	}

	public static void forget(UUID player) {
		brands.remove(player);
	}

	/** "Fabric", "Vanilla", "Lunar Client", … or null if the client didn't say. */
	public static String brandLabel(UUID player) {
		String raw = brands.get(player);
		if (raw == null) {
			return null;
		}
		String b = raw.toLowerCase(Locale.ROOT);
		if (b.equals("vanilla")) {
			return "Vanilla";
		}
		if (b.contains("lunar")) {
			return "Lunar Client";
		}
		if (b.contains("feather")) {
			return "Feather";
		}
		if (b.contains("badlion")) {
			return "Badlion";
		}
		if (b.contains("labymod")) {
			return "LabyMod";
		}
		if (b.contains("neoforge")) {
			return "NeoForge";
		}
		if (b.contains("forge")) {
			return "Forge";
		}
		if (b.contains("quilt")) {
			return "Quilt";
		}
		if (b.contains("fabric")) {
			return "Fabric";
		}
		return raw;
	}

	/** Brand plus the mods recognised from network channels. Server thread. */
	public static Map<String, Object> describe(ServerPlayer player) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("brand", brandLabel(player.getUUID()));
		result.put("brandRaw", brands.get(player.getUUID()));
		Set<String> known = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		Set<String> unknown = new TreeSet<>();
		Set<Identifier> channels;
		try {
			channels = ServerPlayNetworking.getSendable(player);
		} catch (RuntimeException e) {
			channels = Set.of();
		}
		for (Identifier channel : channels) {
			String ns = channel.getNamespace();
			if (IGNORED.contains(ns) || ns.startsWith("fabric-") || ns.startsWith("fabric_")) {
				continue;
			}
			String name = KNOWN.get(ns);
			if (name != null) {
				known.add(name);
			} else {
				unknown.add(ns);
			}
		}
		result.put("mods", new ArrayList<>(known));
		result.put("other", new ArrayList<>(unknown));
		return result;
	}

}
