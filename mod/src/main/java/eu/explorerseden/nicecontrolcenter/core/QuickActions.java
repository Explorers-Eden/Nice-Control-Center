package eu.explorerseden.nicecontrolcenter.core;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import eu.explorerseden.nicecontrolcenter.Claims;
import eu.explorerseden.nicecontrolcenter.Dungeons;
import eu.explorerseden.nicecontrolcenter.Games;
import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.Market;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.Pets;
import eu.explorerseden.nicecontrolcenter.Tweaks;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagNetworkSerialization;

/**
 * The Nice Actions data pack's quick actions menu (the G key, dialog nice_actions:main) gets buttons for the claim
 * menu, pets, the market and the inbox: added to its JSON while the server loads it, so the data pack itself stays
 * as it is. Only for what's switched on when the server starts (dialogs are sent to players as loaded).
 * <p>
 * The game worlds get menus of their own, built from the same file while the server loads: one for Flying Fair (and
 * the game hub) and one for the dungeons, with a Party button and without what doesn't work there (homes where
 * they can't be set; the teleports, claims, pets, market and inbox in the dungeons) or what the admin left out. The
 * key opens whatever the dialog tag minecraft:quick_actions holds, and the client takes that tag from the server:
 * players in a game world are sent that tag with their menu in it, and the usual one again when they leave.
 */
public final class QuickActions {
	private static final Identifier MENU = Identifier.fromNamespaceAndPath("nice_actions", "main");
	private static final Identifier FAIR = Identifier.fromNamespaceAndPath("nicecontrolcenter", "fair_actions");
	private static final Identifier DUNGEON = Identifier.fromNamespaceAndPath("nicecontrolcenter", "dungeon_actions");
	private static final Identifier TAG = Identifier.withDefaultNamespace("quick_actions");
	/** What can be left out in the game worlds (written for the panel when the server starts). */
	public static final String BUTTONS = "nicecontrolcenter-quick-actions.json";
	/** Always left out in the dungeons (every player there is in a run, where they're blocked). */
	private static final Set<String> NOT_IN_DUNGEONS = Set.of("rtp", "tpspawn", "sethome", "tphome", "teleport_back", "claim", "pets", "market", "inbox");

	/** The resources the dialogs are loaded from (to read Nice Actions' menu for the game worlds' menus). */
	private static volatile ResourceManager resources;
	/** player → the menu their client has now (absent: the usual one). */
	private static final Map<UUID, Identifier> sent = new HashMap<>();

	private QuickActions() {
	}

	/** From the registry loader, before the dialogs are read. */
	public static void loading(ResourceManager manager) {
		resources = manager;
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 10 == 0) players(server);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> sent.remove(handler.player.getUUID()));
		// A reload sends everyone the data packs' tags again (the usual menu).
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, manager, ok) -> sent.clear());
	}

	/** From the registry loader: the dialog's JSON, with our buttons (or the game worlds' menus made from it). */
	public static JsonElement patch(ResourceKey<?> key, JsonElement json) {
		if (!key.isFor(Registries.DIALOG)) return json;
		Identifier id = key.identifier();
		if (id.equals(MENU) && json instanceof JsonObject menu && menu.get("actions") instanceof JsonArray actions) {
			try {
				int added = ours(actions);
				if (added > 0) NiceControlCenter.LOGGER.info("Nice Actions: added {} button(s) to the Nice Actions menu", added);
				writeButtons(actions);
			} catch (RuntimeException e) {
				NiceControlCenter.LOGGER.warn("Nice Actions: couldn't add buttons to the Nice Actions menu: {}", e.toString());
			}
			return json;
		}
		if (id.equals(FAIR) || id.equals(DUNGEON)) {
			try {
				JsonElement made = gameMenu(id.equals(DUNGEON));
				if (made != null) return made;
			} catch (RuntimeException e) {
				NiceControlCenter.LOGGER.warn("Nice Actions: couldn't make the {} menu: {}", id.getPath(), e.toString());
			}
		}
		return json;
	}

	/** Claims, pets, market and inbox (what's on). */
	private static int ours(JsonArray actions) {
		Path config = FabricLoader.getInstance().getConfigDir();
		int added = 0;
		if (Claims.read(config.resolve(Claims.FILE)).enabled) added += add(actions, "item/golden_shovel", "Land Claims", "Claim land, see your claims and who may build there.", "claim");
		if (Pets.read(config.resolve(Pets.FILE)).enabled) added += add(actions, "item/lead", "Pets", "Your pets and the pet shop.", "pets");
		if (Market.read(config.resolve(Market.FILE)).enabled) {
			added += add(actions, "item/diamond", "Market", "Buy from other players and sell your own items.", "market");
			added += add(actions, "item/bundle", "Inbox", "Items waiting for you: market sales, purchases, rewards and things from game areas.", "inbox");
		}
		return added;
	}

	/** A button's id: Nice Actions' own name (option.nice_actions.sethome → sethome), else the command it runs. */
	static String idOf(JsonObject button) {
		if (button.get("label") instanceof JsonObject l && l.has("translate")) {
			String t = l.get("translate").getAsString();
			if (t.startsWith("option.nice_actions.")) return t.substring("option.nice_actions.".length());
		}
		if (button.get("action") instanceof JsonObject a) {
			if (a.has("command")) return a.get("command").getAsString().replaceFirst("^/", "").split(" ")[0];
			if (a.get("dialog") instanceof JsonObject d && d.get("title") instanceof JsonObject t && t.has("text")) return t.get("text").getAsString().toLowerCase(java.util.Locale.ROOT);
		}
		return "";
	}

	private static String labelOf(JsonObject button) {
		JsonElement l = button.get("label");
		if (l instanceof JsonObject o) return o.has("fallback") ? o.get("fallback").getAsString() : o.has("text") ? o.get("text").getAsString() : idOf(button);
		if (l instanceof JsonArray a) for (JsonElement e : a) if (e instanceof JsonObject o && o.has("text") && !o.get("text").getAsString().isBlank()) return o.get("text").getAsString();
		return idOf(button);
	}

	/** The buttons the menu has (id and label) for the panel's list. */
	private static void writeButtons(JsonArray actions) {
		List<Map<String, String>> out = new ArrayList<>();
		for (JsonElement e : actions) {
			if (!(e instanceof JsonObject b)) continue;
			Map<String, String> m = new LinkedHashMap<>();
			m.put("id", idOf(b));
			m.put("label", labelOf(b));
			if (!m.get("id").isEmpty()) out.add(m);
		}
		try {
			Files.writeString(FabricLoader.getInstance().getConfigDir().resolve(BUTTONS), Json.GSON.toJson(out));
		} catch (java.io.IOException | RuntimeException e) {
			// Only the panel's list.
		}
	}

	/** Nice Actions' menu read again, with our buttons and Merl's, then the game world's changes; null without Nice Actions. */
	private static JsonElement gameMenu(boolean dungeon) throws RuntimeException {
		ResourceManager rm = resources;
		if (rm == null) return null;
		var res = rm.getResource(Identifier.fromNamespaceAndPath("nice_actions", "dialog/main.json")).orElse(null);
		if (res == null) return null;
		JsonElement json;
		try (Reader r = res.openAsReader()) {
			json = JsonParser.parseReader(r);
		} catch (java.io.IOException e) {
			return null;
		}
		if (!(json instanceof JsonObject menu) || !(menu.get("actions") instanceof JsonArray actions)) return null;
		ours(actions);
		merl(json);
		Path config = FabricLoader.getInstance().getConfigDir();
		Games.QuickMenu q = Games.read(config.resolve(Games.FILE)).quickMenu;
		java.util.Set<String> hide = new java.util.HashSet<>(q.hide);
		String dim = dungeon ? Dungeons.DIMENSION : "eden:flying_fair";
		if (dungeon) hide.addAll(NOT_IN_DUNGEONS);
		var homes = Tweaks.read(config.resolve(Tweaks.FILE)).homes.dimensions;
		if (!homes.isEmpty() && !homes.contains(dim)) hide.add("sethome");
		Claims.Settings claims = Claims.read(config.resolve(Claims.FILE));
		if (!claims.dimensions.contains(dim)) hide.add("claim");
		JsonArray kept = new JsonArray();
		int gone = 0;
		for (JsonElement e : actions) {
			if (e instanceof JsonObject b && hide.contains(idOf(b))) gone++;
			else kept.add(e);
		}
		if (q.party) add(kept, "item/cake", "Party", "Play together: invite players, see your party, leave it. The leader starts dungeons and wave fights for everyone.", "party");
		menu.add("actions", kept);
		NiceControlCenter.LOGGER.info("Nice Actions: the {} menu has {} buttons ({} left out there)", dungeon ? "dungeon" : "Flying Fair", kept.size(), gone);
		return json;
	}

	/** Merl's button too (the Nice Merl mod adds it to the usual menu the same way), when that mod is there. */
	private static void merl(JsonElement json) {
		try {
			Class<?> c = Class.forName("eu.explorerseden.nicemerl.MerlQuickActions");
			c.getMethod("patch", ResourceKey.class, JsonElement.class).invoke(null, ResourceKey.create(Registries.DIALOG, MENU), json);
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			// Not installed.
		}
	}

	// ---------------------------------------------------------------- which menu each player's key opens

	private static Identifier wanted(MinecraftServer server, ServerPlayer p) {
		String dim = p.level().dimension().identifier().toString();
		if (dim.equals(Dungeons.DIMENSION)) return DUNGEON;
		if (eu.explorerseden.nicecontrolcenter.games.Parties.here(p)) return FAIR;
		return MENU;
	}

	private static void players(MinecraftServer server) {
		Registry<Dialog> dialogs = server.registryAccess().lookupOrThrow(Registries.DIALOG);
		if (dialogs.getValue(MENU) == null) return;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Identifier want = wanted(server, p);
			if (want.equals(sent.getOrDefault(p.getUUID(), MENU))) continue;
			if (dialogs.getValue(want) == null) want = MENU;
			if (send(server, dialogs, p, want)) sent.put(p.getUUID(), want);
		}
	}

	/** The dialog tags with minecraft:quick_actions holding the menu (Nice Actions' replaced, others kept). */
	@SuppressWarnings("unchecked")
	private static boolean send(MinecraftServer server, Registry<Dialog> dialogs, ServerPlayer p, Identifier menu) {
		try {
			TagNetworkSerialization.NetworkPayload all = TagNetworkSerialization.serializeTagsToNetwork(server.registries()).get(Registries.DIALOG);
			if (all == null) return false;
			java.lang.reflect.Field f = TagNetworkSerialization.NetworkPayload.class.getDeclaredField("tags");
			f.setAccessible(true);
			Map<Identifier, IntList> tags = new HashMap<>((Map<Identifier, IntList>) f.get(all));
			int main = dialogs.getId(dialogs.getValue(MENU)), mine = dialogs.getId(dialogs.getValue(menu));
			IntList quick = new IntArrayList();
			for (int i : tags.getOrDefault(TAG, new IntArrayList())) if (i != main && i != dialogs.getId(dialogs.getValue(FAIR)) && i != dialogs.getId(dialogs.getValue(DUNGEON))) quick.add(i);
			quick.add(0, mine);
			tags.put(TAG, quick);
			var ctor = TagNetworkSerialization.NetworkPayload.class.getDeclaredConstructor(Map.class);
			ctor.setAccessible(true);
			p.connection.send(new ClientboundUpdateTagsPacket(Map.of(Registries.DIALOG, ctor.newInstance(tags))));
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			NiceControlCenter.LOGGER.warn("Nice Actions: couldn't switch {}'s menu: {}", p.getGameProfile().name(), e.toString());
			return false;
		}
	}

	/** One button (an item icon in front of the label) running the command as the player; none if the menu already has one for it. */
	private static int add(JsonArray actions, String icon, String label, String tooltip, String command) {
		for (JsonElement e : actions) {
			if (e instanceof JsonObject a && a.get("action") instanceof JsonObject act && act.has("command")
					&& act.get("command").getAsString().replaceFirst("^/", "").equals(command)) {
				return 0;
			}
		}
		JsonObject button = new JsonObject();
		JsonObject sprite = new JsonObject();
		sprite.addProperty("atlas", "minecraft:items");
		sprite.addProperty("sprite", icon);
		JsonObject text = new JsonObject();
		text.addProperty("text", label);
		JsonArray full = new JsonArray();
		full.add("");
		full.add(sprite);
		full.add(" ");
		full.add(text);
		button.add("label", full);
		JsonObject tip = new JsonObject();
		tip.addProperty("text", tooltip);
		button.add("tooltip", tip);
		JsonObject action = new JsonObject();
		action.addProperty("type", "run_command");
		action.addProperty("command", command);
		button.add("action", action);
		actions.add(button);
		return 1;
	}
}
