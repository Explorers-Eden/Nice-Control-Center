package eu.explorerseden.nicecontrolcenter.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Talks to the Nice Control Center Panel when it runs this server: asks at login whether a player may
 * join (Discord account linking) and reports chat, joins, leaves, deaths and advancements for the
 * Discord bridge. Uses the same messages vanilla announces, so its game rules are respected.
 */
public final class PanelBridge {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
	private static final ExecutorService SENDER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Nice Control Center Panel bridge");
		t.setDaemon(true);
		return t;
	});
	/** Recent "may join" answers, so a reconnect doesn't wait for the panel again. */
	private static final Map<UUID, Long> allowedUntil = new ConcurrentHashMap<>();

	private PanelBridge() {
	}

	public static boolean active() {
		return PanelMode.active() && !PanelMode.api().isEmpty();
	}

	public static void register() {
		if (!active()) {
			return;
		}
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			JsonObject event = new JsonObject();
			event.addProperty("type", "chat");
			event.addProperty("uuid", sender.getUUID().toString());
			event.addProperty("name", sender.getGameProfile().name());
			event.addProperty("text", message.signedContent());
			send(event);
		});
		ServerMessageEvents.GAME_MESSAGE.register((server, message, overlay) -> {
			if (overlay || !(message.getContents() instanceof TranslatableContents tr)) {
				return;
			}
			String key = tr.getKey();
			String type = key.equals("multiplayer.player.joined") || key.equals("multiplayer.player.joined.renamed") ? "join"
					: key.equals("multiplayer.player.left") ? "leave"
					: key.startsWith("death.") ? "death"
					: key.startsWith("chat.type.advancement.") ? "advancement"
					: null;
			if (type == null) {
				return;
			}
			JsonObject event = new JsonObject();
			event.addProperty("type", type);
			Object[] args = tr.getArgs();
			event.addProperty("name", args.length > 0 && args[0] instanceof Component c ? c.getString() : String.valueOf(args.length > 0 ? args[0] : ""));
			if (type.equals("advancement") && args.length > 1) {
				event.addProperty("advancement", args[1] instanceof Component c ? c.getString().replaceAll("^\\[|\\]$", "") : String.valueOf(args[1]));
			}
			event.addProperty("text", message.getString());
			event.addProperty("players", server.getPlayerCount() + (type.equals("leave") ? -1 : 0));
			event.addProperty("maxPlayers", server.getMaxPlayers());
			send(event);
		});
	}

	/**
	 * Called from PlayerList.canPlayerLogin after vanilla's own checks passed. Returns the kick message,
	 * or null to let the player in. If the panel can't answer, the player is let in (with a warning),
	 * so a panel hiccup can't lock everyone out.
	 */
	public static Component checkLogin(MinecraftServer server, NameAndId player) {
		if (!active()) {
			return null;
		}
		Long until = allowedUntil.get(player.id());
		if (until != null && until > System.currentTimeMillis()) {
			return null;
		}
		try {
			JsonObject body = new JsonObject();
			body.addProperty("uuid", player.id().toString());
			body.addProperty("name", player.name());
			body.addProperty("op", server.getPlayerList().isOp(player));
			HttpResponse<String> response = HTTP.send(request("/internal/login").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
					HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				throw new IllegalStateException("HTTP " + response.statusCode());
			}
			JsonObject answer = JsonParser.parseString(response.body()).getAsJsonObject();
			if (answer.get("allowed").getAsBoolean()) {
				allowedUntil.put(player.id(), System.currentTimeMillis() + 60_000);
				return null;
			}
			return Component.literal(answer.has("message") ? answer.get("message").getAsString() : "You can't join right now.");
		} catch (Exception e) {
			NiceControlCenter.LOGGER.warn("The panel didn't answer the login check for {} ({}); letting them in", player.name(), e.toString());
			return null;
		}
	}

	private static void send(JsonObject event) {
		SENDER.submit(() -> {
			try {
				HTTP.send(request("/internal/event").POST(HttpRequest.BodyPublishers.ofString(event.toString())).build(), HttpResponse.BodyHandlers.discarding());
			} catch (Exception e) {
				// The panel restarts or isn't listening: this event is skipped.
			}
		});
	}

	private static HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder(URI.create(PanelMode.api() + path)).timeout(Duration.ofSeconds(3))
				.header("Content-Type", "application/json").header("X-NCC-Secret", PanelMode.secret());
	}
}
