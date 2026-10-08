package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import eu.explorerseden.nicecontrolcenter.Json;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Webhook;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Discord bridge with exactly four jobs: players must link a Discord account to play (a code sent to
 * the bot by direct message), chat is relayed both ways (chat only, never commands), game events are
 * posted, and the bot's status shows whether the server is up and how many play.
 */
public final class Discord {
	public static final class Settings {
		public boolean enabled = false;
		public String token = "";
		public String guildId = "";
		public String channelId = "";
		public boolean requireLink = true;
		/** Linked players must also still be on the Discord server. */
		public boolean requireMember = false;
		public boolean opsBypass = true;
		public List<String> bypass = new ArrayList<>();
		public boolean chatToDiscord = true;
		public boolean chatToGame = true;
		public boolean presence = true;
		public Map<String, Boolean> events = new LinkedHashMap<>(Map.of("join", true, "leave", true, "death", true, "advancement", true,
				"start", true, "stop", true, "crash", true));
		public Map<String, String> templates = new LinkedHashMap<>(Map.of(
				"join", "➡️ **{player}** joined the server",
				"leave", "⬅️ **{player}** left the server",
				"death", "💀 {message}",
				"advancement", "🏆 **{player}** has made the advancement **{advancement}**",
				"start", "🟢 The server is online",
				"stop", "🔴 The server is offline",
				"crash", "💥 The server crashed"));
		public String kickMessage = "Link your Discord account to play on this server.\n\nSend this code to {bot} as a direct message on Discord:\n\n{code}\n\nThe code works for 15 minutes.";
		public String notMemberMessage = "Your linked Discord account isn't on our Discord server anymore. Join it again to play.";
	}

	public record Link(String uuid, String name, String discordId, String discordName, long linkedAt) {
	}

	private record Pending(String uuid, String name, long expires) {
	}

	private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

	private final Path settingsFile;
	private final Path linksFile;
	private final Supervisor server;
	private final Audit audit;
	private final SecureRandom random = new SecureRandom();
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "discord");
		t.setDaemon(true);
		return t;
	});
	private volatile Settings settings;
	private volatile List<Link> links;
	private final Map<String, Pending> pending = new ConcurrentHashMap<>();
	private final Map<String, Long> lastRelay = new ConcurrentHashMap<>();
	/** discordId → (member?, checked at) */
	private final Map<String, long[]> memberCache = new ConcurrentHashMap<>();
	private volatile JDA jda;
	private volatile String status = "Off";
	private volatile String webhookUrl;
	private volatile int players;
	private volatile int maxPlayers;
	private volatile Supervisor.State lastState;
	private volatile String lastPresence = "";

	public Discord(Path settingsFile, Path linksFile, Supervisor server, Audit audit) {
		this.settingsFile = settingsFile;
		this.linksFile = linksFile;
		this.server = server;
		this.audit = audit;
		Settings s = null;
		List<Link> l = null;
		try {
			if (Files.exists(settingsFile)) s = Json.GSON.fromJson(Files.readString(settingsFile), Settings.class);
			if (Files.exists(linksFile)) l = Json.GSON.fromJson(Files.readString(linksFile), new TypeToken<List<Link>>() { }.getType());
		} catch (IOException | RuntimeException e) {
			System.err.println("Could not read the Discord settings: " + e.getMessage());
		}
		settings = s != null ? s : new Settings();
		links = l != null ? new ArrayList<>(l) : new ArrayList<>();
	}

	public void start() {
		connect();
		// Server start/stop/crash and the bot's status follow the supervisor.
		timer.scheduleAtFixedRate(this::watch, 3, 5, TimeUnit.SECONDS);
	}

	// ── Settings and links ─────────────────────────────────────────────────

	public Settings settings() {
		return settings;
	}

	/** The token from DISCORD_TOKEN wins over the one saved in the panel. */
	private String token() {
		String env = Panel.env("DISCORD_TOKEN", "");
		return env.isEmpty() ? settings.token : env;
	}

	public boolean tokenFromEnv() {
		return !Panel.env("DISCORD_TOKEN", "").isEmpty();
	}

	public String status() {
		return status;
	}

	public synchronized String save(Settings next) throws IOException {
		if (next.token == null || next.token.isBlank() || next.token.equals("********")) next.token = settings.token;
		if (next.guildId != null) next.guildId = next.guildId.strip();
		if (next.channelId != null) next.channelId = next.channelId.strip();
		if (next.enabled && (next.guildId == null || !next.guildId.matches("\\d{5,25}"))) return "The Discord server ID is a long number (Developer Mode → right-click the server → Copy Server ID).";
		if (next.enabled && (next.channelId == null || !next.channelId.matches("\\d{5,25}"))) return "The channel ID is a long number (right-click the channel → Copy Channel ID).";
		if (next.bypass == null) next.bypass = new ArrayList<>();
		next.bypass = next.bypass.stream().map(String::strip).filter(b -> !b.isEmpty()).map(b -> b.toLowerCase(Locale.ROOT)).distinct().toList();
		if (next.events == null) next.events = new Settings().events;
		if (next.templates == null) next.templates = new Settings().templates;
		write(settingsFile, Json.GSON.toJson(next));
		boolean reconnect = !next.token.equals(settings.token) || next.enabled != settings.enabled;
		settings = next;
		webhookUrl = null;
		if (reconnect || jda == null) connect();
		else lastPresence = "";
		return null;
	}

	public List<Link> links() {
		return List.copyOf(links);
	}

	public synchronized boolean unlink(String uuid) throws IOException {
		boolean removed = links.removeIf(l -> l.uuid().equals(uuid));
		if (removed) write(linksFile, Json.GSON.toJson(links));
		return removed;
	}

	private synchronized void link(Link link) throws IOException {
		links.removeIf(l -> l.uuid().equals(link.uuid()));
		links.add(link);
		write(linksFile, Json.GSON.toJson(links));
	}

	private static void write(Path file, String text) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, text, StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	// ── Bot ────────────────────────────────────────────────────────────────

	private synchronized void connect() {
		if (jda != null) {
			jda.shutdown();
			jda = null;
		}
		if (!settings.enabled) {
			status = "Off";
			return;
		}
		if (token().isBlank()) {
			status = "No bot token yet";
			return;
		}
		status = "Connecting…";
		try {
			jda = JDABuilder.createLight(token(), EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.DIRECT_MESSAGES, GatewayIntent.MESSAGE_CONTENT))
					.setMemberCachePolicy(MemberCachePolicy.NONE)
					.addEventListeners(new Listener())
					.build();
			JDA bot = jda;
			Thread.ofVirtual().start(() -> {
				try {
					bot.awaitReady();
					status = "Connected as " + bot.getSelfUser().getName();
					lastPresence = "";
					watch();
				} catch (Exception e) {
					status = "Couldn't connect: " + e.getMessage();
				}
			});
		} catch (RuntimeException e) {
			status = "Couldn't connect: " + e.getMessage();
			jda = null;
		}
	}

	/** Container stop: post "offline" (and wait for it), then disconnect. */
	public void stop() {
		JDA bot = jda;
		if (bot == null) return;
		TextChannel channel = channel();
		if (channel != null && settings.enabled && Boolean.TRUE.equals(settings.events.get("stop")) && lastState != Supervisor.State.STOPPED) {
			try {
				channel.sendMessage(settings.templates.getOrDefault("stop", "")).setAllowedMentions(List.of()).complete();
			} catch (RuntimeException e) {
				// Shutting down anyway.
			}
		}
		lastState = Supervisor.State.STOPPED;
		bot.shutdown();
	}

	private TextChannel channel() {
		JDA bot = jda;
		if (bot == null || bot.getStatus() != JDA.Status.CONNECTED) return null;
		return bot.getTextChannelById(settings.channelId);
	}

	private String botName() {
		JDA bot = jda;
		return bot != null && bot.getStatus() == JDA.Status.CONNECTED ? bot.getSelfUser().getName() : "our Discord bot";
	}

	private final class Listener extends ListenerAdapter {
		@Override
		public void onMessageReceived(MessageReceivedEvent event) {
			if (event.getAuthor().isBot() || event.isWebhookMessage()) return;
			if (event.isFromType(ChannelType.PRIVATE)) {
				direct(event.getMessage());
			} else if (event.getChannel().getId().equals(settings.channelId) && settings.chatToGame) {
				toGame(event.getMessage());
			}
		}
	}

	/** Direct messages: a linking code, or "unlink". */
	private void direct(Message message) {
		String text = message.getContentRaw().strip().toUpperCase(Locale.ROOT);
		String userId = message.getAuthor().getId();
		if (text.equals("UNLINK")) {
			List<Link> mine = links.stream().filter(l -> l.discordId().equals(userId)).toList();
			for (Link l : mine) {
				try {
					unlink(l.uuid());
				} catch (IOException e) {
					// Next time.
				}
				audit.log(message.getAuthor().getName(), "discord.unlink", l.name() + " (by the player)", null);
			}
			message.getChannel().sendMessage(mine.isEmpty() ? "No Minecraft account is linked to you." : "Unlinked " + String.join(", ", mine.stream().map(Link::name).toList()) + ".").queue();
			return;
		}
		Pending p = pending.get(text.replaceAll("[^A-Z0-9]", ""));
		if (p == null || p.expires() < System.currentTimeMillis()) {
			message.getChannel().sendMessage("Send me the code shown when you join the Minecraft server. (Send `unlink` to remove your link.)").queue();
			return;
		}
		if (settings.requireMember && !isMember(userId, true)) {
			message.getChannel().sendMessage("Join our Discord server first, then send the code again.").queue();
			return;
		}
		pending.values().removeIf(x -> x.uuid().equals(p.uuid()));
		try {
			link(new Link(p.uuid(), p.name(), userId, message.getAuthor().getName(), System.currentTimeMillis()));
		} catch (IOException e) {
			message.getChannel().sendMessage("Something went wrong while saving; try again in a moment.").queue();
			return;
		}
		audit.log(message.getAuthor().getName(), "discord.link", p.name() + " ↔ " + message.getAuthor().getName(), null);
		message.getChannel().sendMessage("Linked! **" + p.name() + "** can join the server now.").queue();
	}

	/** Discord → game as tellraw text; nothing is ever run as a command. */
	private void toGame(Message message) {
		if (!server.running()) return;
		long now = System.currentTimeMillis();
		Long last = lastRelay.put(message.getAuthor().getId(), now);
		if (last != null && now - last < 1500) return;
		String text = message.getContentDisplay().replaceAll("[\\r\\n]+", " ").strip();
		if (!message.getAttachments().isEmpty()) text = (text + " [" + message.getAttachments().size() + " attachment" + (message.getAttachments().size() == 1 ? "" : "s") + "]").strip();
		if (text.isEmpty()) return;
		if (text.length() > 256) text = text.substring(0, 256) + "…";
		String name = message.getMember() != null ? message.getMember().getEffectiveName() : message.getAuthor().getEffectiveName();
		JsonArray parts = new JsonArray();
		parts.add("");
		parts.add(part("[Discord] ", "#7289da"));
		parts.add(part(name, "white"));
		parts.add(part(": " + text, "gray"));
		server.quietCommand("tellraw @a " + parts);
	}

	private static JsonObject part(String text, String color) {
		JsonObject o = new JsonObject();
		o.addProperty("text", text);
		o.addProperty("color", color);
		return o;
	}

	private boolean isMember(String discordId, boolean fresh) {
		long[] cached = memberCache.get(discordId);
		long now = System.currentTimeMillis();
		if (!fresh && cached != null && now - cached[1] < 5 * 60_000) return cached[0] == 1;
		JDA bot = jda;
		Guild guild = bot == null ? null : bot.getGuildById(settings.guildId);
		if (guild == null) return cached == null || cached[0] == 1;
		try {
			guild.retrieveMemberById(discordId).complete();
			memberCache.put(discordId, new long[] { 1, now });
			return true;
		} catch (net.dv8tion.jda.api.exceptions.ErrorResponseException e) {
			memberCache.put(discordId, new long[] { 0, now });
			return false;
		} catch (RuntimeException e) {
			// Discord unreachable: go with what we knew.
			return cached == null || cached[0] == 1;
		}
	}

	// ── Login check (from the mod) ─────────────────────────────────────────

	public record LoginAnswer(boolean allowed, String message) {
	}

	public LoginAnswer login(String uuid, String name, boolean op) {
		Settings s = settings;
		if (!s.enabled || !s.requireLink) return new LoginAnswer(true, null);
		if (op && s.opsBypass) return new LoginAnswer(true, null);
		if (name != null && s.bypass.contains(name.toLowerCase(Locale.ROOT))) return new LoginAnswer(true, null);
		JDA bot = jda;
		if (bot == null || bot.getStatus() != JDA.Status.CONNECTED) {
			// Without a working bot nobody could link; don't lock everyone out.
			server.note("Discord: the bot isn't connected, so " + name + " joins without the link check");
			return new LoginAnswer(true, null);
		}
		Link link = links.stream().filter(l -> l.uuid().equals(uuid)).findFirst().orElse(null);
		if (link == null) {
			String code = pending.entrySet().stream().filter(e -> e.getValue().uuid().equals(uuid) && e.getValue().expires() > System.currentTimeMillis())
					.map(Map.Entry::getKey).findFirst().orElse(null);
			if (code == null) {
				pending.values().removeIf(p -> p.expires() < System.currentTimeMillis());
				StringBuilder sb = new StringBuilder();
				for (int i = 0; i < 6; i++) sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
				code = sb.toString();
				pending.put(code, new Pending(uuid, name, System.currentTimeMillis() + 15 * 60_000));
			}
			return new LoginAnswer(false, s.kickMessage.replace("{bot}", botName()).replace("{code}", code).replace("{player}", name == null ? "" : name));
		}
		if (s.requireMember && !isMember(link.discordId(), false)) return new LoginAnswer(false, s.notMemberMessage);
		if (name != null && !name.equals(link.name())) {
			try {
				link(new Link(link.uuid(), name, link.discordId(), link.discordName(), link.linkedAt()));
			} catch (IOException e) {
				// The old name stays.
			}
		}
		return new LoginAnswer(true, null);
	}

	// ── Events (from the mod) and server state ─────────────────────────────

	public void event(JsonObject e) {
		String type = e.has("type") ? e.get("type").getAsString() : "";
		if (e.has("players")) players = Math.max(0, e.get("players").getAsInt());
		if (e.has("maxPlayers")) maxPlayers = e.get("maxPlayers").getAsInt();
		String name = e.has("name") ? e.get("name").getAsString() : "";
		if (type.equals("chat")) {
			if (settings.enabled && settings.chatToDiscord) chat(e.has("uuid") ? e.get("uuid").getAsString() : null, name, e.has("text") ? e.get("text").getAsString() : "");
			return;
		}
		Map<String, String> values = new LinkedHashMap<>();
		values.put("player", name);
		values.put("message", e.has("text") ? e.get("text").getAsString() : "");
		values.put("advancement", e.has("advancement") ? e.get("advancement").getAsString() : "");
		post(type, values);
		lastPresence = "";
	}

	private void watch() {
		Supervisor.State state = server.state();
		Supervisor.State before = lastState;
		lastState = state;
		if (before != null && state != before) {
			if (state == Supervisor.State.RUNNING) post("start", Map.of());
			else if (state == Supervisor.State.CRASHED) post("crash", Map.of());
			else if (state == Supervisor.State.STOPPED && before != Supervisor.State.CRASHED) post("stop", Map.of());
			if (state != Supervisor.State.RUNNING) players = 0;
		}
		presence(state);
	}

	private void presence(Supervisor.State state) {
		JDA bot = jda;
		if (bot == null || bot.getStatus() != JDA.Status.CONNECTED || !settings.presence) return;
		String text = state == Supervisor.State.RUNNING ? players + (maxPlayers > 0 ? "/" + maxPlayers : "") + " players online"
				: state == Supervisor.State.STARTING ? "Server starting…" : "Server offline";
		if (text.equals(lastPresence)) return;
		lastPresence = text;
		bot.getPresence().setPresence(state == Supervisor.State.RUNNING ? OnlineStatus.ONLINE : OnlineStatus.DO_NOT_DISTURB, Activity.customStatus(text));
	}

	private void post(String type, Map<String, String> values) {
		if (!settings.enabled || !Boolean.TRUE.equals(settings.events.get(type))) return;
		TextChannel channel = channel();
		if (channel == null) return;
		String text = settings.templates.getOrDefault(type, "");
		for (Map.Entry<String, String> v : values.entrySet()) text = text.replace("{" + v.getKey() + "}", escape(v.getValue()));
		if (text.isBlank()) return;
		channel.sendMessage(text).setAllowedMentions(List.of()).queue(ok -> { }, err -> status = "Can't post in the channel: " + err.getMessage());
	}

	/** Game → Discord through a webhook, so each message shows the player's name and head. */
	private void chat(String uuid, String name, String text) {
		TextChannel channel = channel();
		if (channel == null || text.isBlank()) return;
		String url = webhook(channel);
		if (url == null) {
			channel.sendMessage("**" + escape(name) + "**: " + escape(text)).setAllowedMentions(List.of()).queue();
			return;
		}
		JsonObject body = new JsonObject();
		body.addProperty("content", text.length() > 1900 ? text.substring(0, 1900) + "…" : text);
		body.addProperty("username", name);
		if (uuid != null) body.addProperty("avatar_url", "https://mc-heads.net/avatar/" + uuid + "/64");
		JsonObject allowed = new JsonObject();
		allowed.add("parse", new JsonArray());
		body.add("allowed_mentions", allowed);
		http.sendAsync(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.discarding());
	}

	private String webhook(TextChannel channel) {
		String url = webhookUrl;
		if (url != null) return url;
		try {
			Webhook hook = channel.retrieveWebhooks().complete().stream().filter(w -> "Nice Control Center".equals(w.getName())).findFirst()
					.orElseGet(() -> channel.createWebhook("Nice Control Center").complete());
			webhookUrl = hook.getUrl();
			return webhookUrl;
		} catch (RuntimeException e) {
			// No "Manage Webhooks" permission: plain bot messages instead.
			return null;
		}
	}

	/** Player names and messages can't format or ping in Discord. */
	private static String escape(String text) {
		return text == null ? "" : text.replaceAll("([*_~`|>\\\\])", "\\\\$1").replace("@", "@​");
	}
}
