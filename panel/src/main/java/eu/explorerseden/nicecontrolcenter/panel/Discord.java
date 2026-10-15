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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Discord bridge with exactly four jobs: players must link a Discord account to play (a code posted in
 * the link channel, where the bot removes it and answers privately, or sent to the bot by direct message), chat is relayed both ways (chat only, never commands), game events are
 * posted, and the bot's status shows whether the server is up and how many play.
 */
public final class Discord implements Notifier {
	public static final class Settings {
		public boolean enabled = false;
		public String token = "";
		public String guildId = "";
		public String channelId = "";
		/** Warnings (lag, crashes, failures, disk, memory) go here; empty = the chat channel. */
		public String alertChannelId = "";
		/** Where players post their linking code (removed at once, answered privately); empty = direct messages only. */
		public String linkChannelId = "";
		/** Discord role every linked player gets; empty = none. */
		public String linkedRoleId = "";
		/** Fabled Roots race (lower case) → Discord role, given on top of the linked role; the bot swaps it when the race changes. */
		public Map<String, String> raceRoles = new LinkedHashMap<>();
		/** Per event: "chat" or "alerts". Events not listed use their default (warnings to alerts, the rest to chat). */
		public Map<String, String> eventChannels = new LinkedHashMap<>();
		public boolean requireLink = true;
		/** Linked players must also still be on the Discord server. */
		public boolean requireMember = false;
		public boolean opsBypass = true;
		public List<String> bypass = new ArrayList<>();
		public boolean chatToDiscord = true;
		public boolean chatToGame = true;
		/** How Discord messages look in game (DiscordJustSync style tags, %user% and %msg%). */
		public String gameFormat = GameFormat.DEFAULT;
		/** The player's name on Discord for game chat: {player} and {race} (Fabled Roots; left out with its brackets when there's none). */
		public String nameFormat = "{race} ┃ {player}";
		public boolean presence = true;
		public Map<String, Boolean> events = new LinkedHashMap<>(Map.of("join", true, "leave", true, "death", true, "advancement", true,
				"start", true, "stop", true, "crash", true));

		/** Fills in events and templates newer than the saved settings (Gson replaces the whole maps). */
		void addDefaults() {
			Settings d = new Settings();
			if (events == null) events = new LinkedHashMap<>();
			if (templates == null) templates = new LinkedHashMap<>();
			d.events.forEach(events::putIfAbsent);
			d.templates.forEach(templates::putIfAbsent);
			if (alertChannelId == null) alertChannelId = "";
			if (linkChannelId == null) linkChannelId = "";
			if (linkedRoleId == null) linkedRoleId = "";
			linkedRoleId = linkedRoleId.strip();
			Map<String, String> roles = new LinkedHashMap<>();
			if (raceRoles != null) raceRoles.forEach((race, id) -> {
				if (race != null && id != null && !id.isBlank()) roles.put(race.strip().toLowerCase(Locale.ROOT), id.strip());
			});
			raceRoles = roles;
			if (kickMessage == null || kickMessage.isBlank()) kickMessage = d.kickMessage;
			// Messages from before the link channel (the default, or an edited one) tell players to DM the bot: {where} names the link channel instead.
			kickMessage = OLD_WHERE.matcher(kickMessage).replaceAll("Post this code {where}:");
			if (gameFormat == null || gameFormat.isBlank()) gameFormat = GameFormat.DEFAULT;
			// "[{race}] {player}" was the first default; the line matches the admin and Discord prefixes in game.
			if (nameFormat == null || nameFormat.isBlank() || !nameFormat.contains("{player}") || nameFormat.equals("[{race}] {player}")) nameFormat = d.nameFormat;
			if (eventChannels == null) eventChannels = new LinkedHashMap<>();
			eventChannels.keySet().retainAll(events.keySet());
			eventChannels.replaceAll((k, v) -> "alerts".equals(v) ? "alerts" : "chat");
			for (String k : events.keySet()) eventChannels.putIfAbsent(k, ALERTS.contains(k) ? "alerts" : "chat");
		}
		public Map<String, String> templates = new LinkedHashMap<>(Map.of(
				"join", "➡️ **{player}** joined the server",
				"leave", "⬅️ **{player}** left the server",
				"death", "💀 {message}",
				"advancement", "🏆 **{player}** has made the advancement **{advancement}**",
				"start", "🟢 The server is online",
				"stop", "🔴 The server is offline",
				"crash", "💥 The server crashed{mod}: {message}"));
		{
			events.putAll(Map.of("lag", true, "lagOver", true, "taskFailed", true, "backupFailed", true, "trimDone", true, "diskLow", true, "memory", true,
					"perf", false, "maintenance", true));
			templates.putAll(Map.of(
					"lag", "🐢 Lag: {message}",
					"lagOver", "✅ The lag is over ({message})",
					"taskFailed", "⚠️ Scheduled task **{name}** stopped: {message}",
					"backupFailed", "⚠️ The backup failed: {message}",
					"trimDone", "🌲 The outer wilds were reset: {message}",
					"diskLow", "💾 The disk is almost full: {message}",
					"memory", "🧠 The server is short of memory: {message}"));
			templates.put("perf", "⚙️ {message}");
			templates.put("maintenance", "🛠️ Maintenance mode is {message}");
			// /tellraw @a from data packs and /say: off by default, they can be chatty.
			events.put("broadcast", false);
			templates.put("broadcast", "📢 {message}");
		}
		public String kickMessage = "Link your Discord account to play on this server.\n\nPost this code {where}:\n\n{code}\n\nThe code works for 15 minutes.";
		/** "Send this code to {bot} as a direct message on Discord:" from messages before the link channel. */
		private static final java.util.regex.Pattern OLD_WHERE = java.util.regex.Pattern.compile("(?i)send (?:this|the|your) code to \\S.*? as an? (?:direct message|DM)(?: on Discord)?:?");
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
	/** When the webhook couldn't be set up (no permission): plain messages until then, instead of asking on every line. */
	private volatile long webhookRetryAt;
	private volatile int players;
	private volatile int maxPlayers;
	private volatile Supervisor.State lastState;
	private volatile String lastPresence = "";
	/** Hands Discord messages to the mod (Chat tab and log); set once the dashboard proxy exists. */
	private volatile DashboardProxy dashboard;

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
		settings.addDefaults();
		links = l != null ? new ArrayList<>(l) : new ArrayList<>();
	}

	/** Where the links are copied for a website (discord_links); null without a database. */
	private volatile Db db;
	/** The copy in the database is behind the file. */
	private volatile boolean linksDirty = true;

	void useDb(Db db) {
		this.db = db;
	}

	/** Copies all links into the database (every 30 s while it's behind, and right after a change). */
	private void copyLinks() {
		Db d = db;
		if (d == null || !linksDirty || !d.ready()) return;
		List<Link> all = links();
		try (java.sql.Connection c = d.connection()) {
			c.setAutoCommit(false);
			try (java.sql.PreparedStatement del = c.prepareStatement("DELETE FROM discord_links");
					java.sql.PreparedStatement ins = c.prepareStatement("INSERT INTO discord_links (player_uuid, player_name, discord_id, discord_name, linked_at) VALUES (?, ?, ?, ?, ?)")) {
				del.executeUpdate();
				for (Link l : all) {
					ins.setObject(1, java.util.UUID.fromString(l.uuid()));
					ins.setString(2, l.name());
					ins.setString(3, l.discordId());
					ins.setString(4, l.discordName());
					ins.setTimestamp(5, l.linkedAt() > 0 ? new java.sql.Timestamp(l.linkedAt()) : null);
					ins.addBatch();
				}
				ins.executeBatch();
				c.commit();
				linksDirty = false;
			} catch (java.sql.SQLException | RuntimeException e) {
				c.rollback();
				throw e;
			}
		} catch (java.sql.SQLException | RuntimeException e) {
			System.err.println("Could not copy the Discord links to the database: " + e.getMessage());
		}
	}

	private void linksChanged() {
		linksDirty = true;
		timer.execute(this::copyLinks);
		timer.execute(this::syncRoles);
	}

	// ── Roles for linked players ───────────────────────────────────────────

	/** Each player's Fabled Roots race (lower case, "" for none), as the mod last reported it; unknown players aren't in it. */
	private final Map<String, String> races = new ConcurrentHashMap<>();
	/** discordId → the managed roles last given, so unchanged players aren't looked up again. */
	private final Map<String, Set<String>> appliedRoles = new ConcurrentHashMap<>();
	/** Discord accounts that were unlinked: their managed roles go. */
	private final Set<String> unlinkedRoles = ConcurrentHashMap.newKeySet();

	/** Races from the mod's player list (online and offline players, from their Fabled Roots tags). */
	private void refreshRaces() {
		DashboardProxy mod = dashboard;
		if (mod == null || !server.running()) return;
		JsonObject answer = mod.get("/api/players");
		if (answer == null || !answer.has("players") || !answer.get("players").isJsonArray()) return;
		for (com.google.gson.JsonElement e : answer.getAsJsonArray("players")) {
			if (!e.isJsonObject() || !e.getAsJsonObject().has("uuid")) continue;
			JsonObject p = e.getAsJsonObject();
			races.put(p.get("uuid").getAsString(), p.has("race") && !p.get("race").isJsonNull() ? p.get("race").getAsString().toLowerCase(Locale.ROOT) : "");
		}
	}

	/** Gives linked players the linked role and their race's role, and takes away the ones that no longer fit. */
	private final Object rolesLock = new Object();

	private void syncRoles() {
		synchronized (rolesLock) {
			syncRolesNow();
		}
	}

	private void syncRolesNow() {
		Settings s = settings;
		JDA bot = jda;
		if (!s.enabled || bot == null || bot.getStatus() != JDA.Status.CONNECTED) return;
		Set<String> raceRoleIds = new java.util.HashSet<>(s.raceRoles.values());
		if (s.linkedRoleId.isEmpty() && raceRoleIds.isEmpty()) return;
		Guild guild = bot.getGuildById(s.guildId);
		if (guild == null) return;
		if (!raceRoleIds.isEmpty()) refreshRaces();
		Map<String, Set<String>> wanted = new LinkedHashMap<>();
		Map<String, Boolean> raceKnown = new LinkedHashMap<>();
		for (Link l : links()) {
			Set<String> roles = wanted.computeIfAbsent(l.discordId(), k -> new java.util.HashSet<>());
			if (!s.linkedRoleId.isEmpty()) roles.add(s.linkedRoleId);
			String race = races.get(l.uuid());
			if (race != null) {
				raceKnown.put(l.discordId(), true);
				if (s.raceRoles.containsKey(race)) roles.add(s.raceRoles.get(race));
			} else {
				raceKnown.putIfAbsent(l.discordId(), false);
			}
		}
		for (String gone : unlinkedRoles) {
			if (!wanted.containsKey(gone)) {
				wanted.put(gone, Set.of());
				raceKnown.put(gone, true);
			}
		}
		unlinkedRoles.clear();
		for (Map.Entry<String, Set<String>> w : wanted.entrySet()) {
			String id = w.getKey();
			if (w.getValue().equals(appliedRoles.get(id))) continue;
			// Without a known race (server off since the panel started), race roles are left as they are.
			Set<String> managed = new java.util.HashSet<>();
			if (!s.linkedRoleId.isEmpty()) managed.add(s.linkedRoleId);
			if (Boolean.TRUE.equals(raceKnown.get(id))) managed.addAll(raceRoleIds);
			applyRoles(guild, id, w.getValue(), managed);
		}
	}

	private void applyRoles(Guild guild, String discordId, Set<String> wanted, Set<String> managed) {
		guild.retrieveMemberById(discordId).queue(member -> {
			boolean ok = true;
			for (String roleId : managed) {
				net.dv8tion.jda.api.entities.Role role = guild.getRoleById(roleId);
				if (role == null || !guild.getSelfMember().canInteract(role)) {
					ok = false;
					continue;
				}
				boolean has = member.getRoles().contains(role);
				if (wanted.contains(roleId) && !has) guild.addRoleToMember(member, role).reason("Linked Minecraft account").queue(x -> { }, err -> appliedRoles.remove(discordId));
				else if (!wanted.contains(roleId) && has) guild.removeRoleFromMember(member, role).reason("Linked Minecraft account").queue(x -> { }, err -> appliedRoles.remove(discordId));
			}
			// Roles it can't give are tried again next time (the Discord page says why).
			if (ok) appliedRoles.put(discordId, Set.copyOf(wanted));
			else appliedRoles.remove(discordId);
		}, err -> {
			// Not on the Discord server (anymore).
			appliedRoles.remove(discordId);
		});
	}

	void useDashboard(DashboardProxy dashboard) {
		this.dashboard = dashboard;
	}

	public void start() {
		connect();
		// Server start/stop/crash and the bot's status follow the supervisor.
		timer.scheduleAtFixedRate(this::watch, 3, 5, TimeUnit.SECONDS);
		timer.scheduleWithFixedDelay(this::copyLinks, 20, 30, TimeUnit.SECONDS);
		// Races change in game: roles follow every 5 minutes.
		timer.scheduleWithFixedDelay(() -> {
			try {
				syncRoles();
			} catch (RuntimeException e) {
				System.err.println("Could not update the Discord roles: " + e.getMessage());
			}
		}, 60, 300, TimeUnit.SECONDS);
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
		if (next.alertChannelId != null) next.alertChannelId = next.alertChannelId.strip();
		if (next.alertChannelId != null && !next.alertChannelId.isEmpty() && !next.alertChannelId.matches("\\d{5,25}")) return "The alerts channel ID is a long number, or empty for the chat channel.";
		if (next.linkChannelId != null) next.linkChannelId = next.linkChannelId.strip();
		if (next.linkChannelId != null && !next.linkChannelId.isEmpty() && !next.linkChannelId.matches("\\d{5,25}")) return "The link channel ID is a long number, or empty for direct messages only.";
		next.addDefaults();
		if (next.nameFormat != null && !next.nameFormat.isBlank() && !next.nameFormat.contains("{player}")) return "The name on Discord needs {player}.";
		if (next.nameFormat != null && next.nameFormat.length() > 60) return "The name on Discord is 60 characters at most.";
		if (!next.linkedRoleId.isEmpty() && !next.linkedRoleId.matches("\\d{5,25}")) return "The role for linked players is a long number (right-click the role → Copy Role ID), or empty.";
		for (Map.Entry<String, String> r : next.raceRoles.entrySet()) {
			if (!r.getValue().matches("\\d{5,25}")) return "The role for " + r.getKey() + " is a long number (right-click the role → Copy Role ID), or empty.";
		}
		String formatProblem = GameFormat.check(next.gameFormat);
		if (formatProblem != null) return formatProblem;
		write(settingsFile, Json.GSON.toJson(next));
		boolean reconnect = !next.token.equals(settings.token) || next.enabled != settings.enabled;
		settings = next;
		webhookUrl = null;
		webhookRetryAt = 0;
		// Roles are checked again for everyone with the new settings.
		appliedRoles.clear();
		timer.execute(this::syncRoles);
		if (reconnect || jda == null) connect();
		else lastPresence = "";
		return null;
	}

	public List<Link> links() {
		return List.copyOf(links);
	}

	public synchronized boolean unlink(String uuid) throws IOException {
		links.stream().filter(l -> l.uuid().equals(uuid)).forEach(l -> unlinkedRoles.add(l.discordId()));
		boolean removed = links.removeIf(l -> l.uuid().equals(uuid));
		if (removed) {
			write(linksFile, Json.GSON.toJson(links));
			linksChanged();
		}
		return removed;
	}

	private synchronized void link(Link link) throws IOException {
		links.removeIf(l -> l.uuid().equals(link.uuid()));
		links.add(link);
		write(linksFile, Json.GSON.toJson(links));
		linksChanged();
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
		TextChannel channel = "alerts".equals(settings.eventChannels.get("stop")) ? alertChannel() : channel();
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

	private TextChannel alertChannel() {
		JDA bot = jda;
		if (bot == null || bot.getStatus() != JDA.Status.CONNECTED) return null;
		TextChannel admin = settings.alertChannelId == null || settings.alertChannelId.isBlank() ? null : bot.getTextChannelById(settings.alertChannelId);
		return admin != null ? admin : channel();
	}

	private String botName() {
		JDA bot = jda;
		return bot != null && bot.getStatus() == JDA.Status.CONNECTED ? bot.getSelfUser().getName() : "our Discord bot";
	}

	private static final net.dv8tion.jda.api.Permission[] NEEDED = { net.dv8tion.jda.api.Permission.VIEW_CHANNEL, net.dv8tion.jda.api.Permission.MESSAGE_SEND,
			net.dv8tion.jda.api.Permission.MESSAGE_HISTORY, net.dv8tion.jda.api.Permission.MANAGE_WEBHOOKS, net.dv8tion.jda.api.Permission.MESSAGE_MANAGE,
			net.dv8tion.jda.api.Permission.MANAGE_ROLES };

	/** The link that adds the bot to a Discord server with every permission it needs; null while it isn't connected. */
	public String inviteUrl() {
		JDA bot = jda;
		return bot == null || bot.getStatus() != JDA.Status.CONNECTED ? null : bot.getInviteUrl(NEEDED);
	}

	/** What keeps the bot from doing its job on the set server and channels, for the Discord page. */
	public List<String> problems() {
		JDA bot = jda;
		Settings s = settings;
		if (bot == null || bot.getStatus() != JDA.Status.CONNECTED) return List.of();
		Guild guild = s.guildId.isBlank() ? null : bot.getGuildById(s.guildId);
		if (guild == null) return List.of("The bot isn't on the Discord server " + s.guildId + ": add it with the invite link (or check the server ID). Until then it can't post, relay chat or take codes in the link channel.");
		List<String> out = new ArrayList<>();
		problems(out, guild, s.channelId, "chat channel", NEEDED[0], NEEDED[1], NEEDED[2], NEEDED[3]);
		if (!s.alertChannelId.isBlank()) problems(out, guild, s.alertChannelId, "alerts channel", NEEDED[0], NEEDED[1]);
		if (!s.linkChannelId.isBlank()) problems(out, guild, s.linkChannelId, "link channel", NEEDED[0], NEEDED[1], NEEDED[2], NEEDED[4]);
		Map<String, String> roles = new LinkedHashMap<>();
		if (!s.linkedRoleId.isEmpty()) roles.put(s.linkedRoleId, "linked players");
		s.raceRoles.forEach((race, id) -> roles.putIfAbsent(id, race.substring(0, 1).toUpperCase(Locale.ROOT) + race.substring(1)));
		if (!roles.isEmpty() && !guild.getSelfMember().hasPermission(net.dv8tion.jda.api.Permission.MANAGE_ROLES)) out.add("The bot needs Manage Roles to give linked players their roles.");
		roles.forEach((id, what) -> {
			net.dv8tion.jda.api.entities.Role role = guild.getRoleById(id);
			if (role == null) out.add("The role for " + what + " (" + id + ") isn't on that server.");
			else if (!guild.getSelfMember().canInteract(role)) out.add("The bot can't give @" + role.getName() + " (" + what + "): drag the bot's own role above it in Server Settings → Roles.");
		});
		return out;
	}

	private static void problems(List<String> out, Guild guild, String id, String what, net.dv8tion.jda.api.Permission... needed) {
		TextChannel channel = guild.getTextChannelById(id);
		if (channel == null) {
			out.add("The " + what + " (" + id + ") isn't a text channel on that server, or the bot can't see it.");
			return;
		}
		List<String> missing = java.util.Arrays.stream(needed).filter(p -> !guild.getSelfMember().hasPermission(channel, p)).map(net.dv8tion.jda.api.Permission::getName).toList();
		if (!missing.isEmpty()) out.add("In #" + channel.getName() + " (" + what + ") the bot is missing: " + String.join(", ", missing) + ".");
	}

	/** Where the join screen tells players to post their code: the link channel, or the bot's direct messages. */
	private String where() {
		JDA bot = jda;
		TextChannel link = bot == null || bot.getStatus() != JDA.Status.CONNECTED || settings.linkChannelId.isBlank() ? null : bot.getTextChannelById(settings.linkChannelId);
		return link != null ? "in #" + link.getName() + " on our Discord server" : "to " + botName() + " as a direct message on Discord";
	}

	private final class Listener extends ListenerAdapter {
		@Override
		public void onMessageReceived(MessageReceivedEvent event) {
			if (event.getAuthor().isBot() || event.isWebhookMessage()) return;
			if (event.isFromType(ChannelType.PRIVATE)) {
				Message m = event.getMessage();
				direct(m.getAuthor(), m.getContentRaw(), text -> m.getChannel().sendMessage(text).queue());
			} else if (!settings.linkChannelId.isBlank() && event.getChannel().getId().equals(settings.linkChannelId)) {
				linkChannel(event.getMessage());
			} else if (event.getChannel().getId().equals(settings.channelId) && settings.chatToGame) {
				toGame(event.getMessage());
			}
		}
	}

	/**
	 * The link channel: every message is removed at once and answered privately, so codes stay secret. When it's
	 * also the chat channel, only codes and "unlink" are taken; everything else is chat.
	 */
	private void linkChannel(Message message) {
		boolean shared = settings.linkChannelId.equals(settings.channelId);
		String text = message.getContentRaw().strip().toUpperCase(Locale.ROOT);
		if (shared && !text.equals("UNLINK") && !pending.containsKey(text.replaceAll("[^A-Z0-9]", ""))) {
			if (settings.chatToGame) toGame(message);
			return;
		}
		message.delete().queue(ok -> { }, err -> status = "Can't remove messages in the link channel (needs Manage Messages): " + err.getMessage());
		direct(message.getAuthor(), message.getContentRaw(), reply -> privately(message, reply));
	}

	/** A direct message; when the player's DMs are closed, a mention in the channel that removes itself after 15 s. */
	private void privately(Message message, String text) {
		message.getAuthor().openPrivateChannel().flatMap(dm -> dm.sendMessage(text)).queue(ok -> { }, err ->
				message.getChannel().sendMessage(message.getAuthor().getAsMention() + " " + text).setAllowedMentions(List.of())
						.mentionUsers(message.getAuthor().getId())
						.queue(sent -> sent.delete().queueAfter(15, TimeUnit.SECONDS, null, gone -> { }), gone -> { }));
	}

	/** A linking code or "unlink", from a direct message or the link channel; {@code reply} answers privately. */
	private void direct(net.dv8tion.jda.api.entities.User author, String raw, java.util.function.Consumer<String> reply) {
		String text = raw.strip().toUpperCase(Locale.ROOT);
		String userId = author.getId();
		if (text.equals("UNLINK")) {
			List<Link> mine = links.stream().filter(l -> l.discordId().equals(userId)).toList();
			for (Link l : mine) {
				try {
					unlink(l.uuid());
				} catch (IOException e) {
					// Next time.
				}
				audit.log(author.getName(), "discord.unlink", l.name() + " (by the player)", null);
			}
			reply.accept(mine.isEmpty() ? "No Minecraft account is linked to you." : "Unlinked " + String.join(", ", mine.stream().map(Link::name).toList()) + ".");
			return;
		}
		Pending p = pending.get(text.replaceAll("[^A-Z0-9]", ""));
		if (p == null || p.expires() < System.currentTimeMillis()) {
			reply.accept("Send me the code shown when you join the Minecraft server. (Send `unlink` to remove your link.)");
			return;
		}
		if (settings.requireMember && !isMember(userId, true)) {
			reply.accept("Join our Discord server first, then send the code again.");
			return;
		}
		pending.values().removeIf(x -> x.uuid().equals(p.uuid()));
		try {
			link(new Link(p.uuid(), p.name(), userId, author.getName(), System.currentTimeMillis()));
		} catch (IOException e) {
			reply.accept("Something went wrong while saving; try again in a moment.");
			return;
		}
		audit.log(author.getName(), "discord.link", p.name() + " ↔ " + author.getName(), null);
		reply.accept("Linked! **" + p.name() + "** can join the server now.");
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
		JsonArray parts = GameFormat.render(settings.gameFormat, name, text);
		DashboardProxy mod = dashboard;
		if (mod != null && mod.relayChat(name, text, parts)) return;
		// Mods before 1.4.0 can't take it: tellraw, which players see but the log and Chat tab don't.
		server.quietCommand("tellraw @a " + parts);
	}


	private boolean isMember(String discordId, boolean fresh) {
		long[] cached = memberCache.get(discordId);
		long now = System.currentTimeMillis();
		if (!fresh && cached != null && now - cached[1] < 5 * 60_000) return cached[0] == 1;
		JDA bot = jda;
		Guild guild = bot == null ? null : bot.getGuildById(settings.guildId);
		if (guild == null) return cached == null || cached[0] == 1;
		try {
			// The login check waits on this on the Minecraft server's main thread: never long.
			guild.retrieveMemberById(discordId).submit().get(1500, TimeUnit.MILLISECONDS);
			memberCache.put(discordId, new long[] { 1, now });
			return true;
		} catch (java.util.concurrent.ExecutionException e) {
			if (e.getCause() instanceof net.dv8tion.jda.api.exceptions.ErrorResponseException) {
				memberCache.put(discordId, new long[] { 0, now });
				return false;
			}
			return cached == null || cached[0] == 1;
		} catch (java.util.concurrent.TimeoutException | RuntimeException e) {
			// Discord slow or unreachable: go with what we knew.
			return cached == null || cached[0] == 1;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
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
			return new LoginAnswer(false, s.kickMessage.replace("{where}", where()).replace("{bot}", botName()).replace("{code}", code).replace("{player}", name == null ? "" : name));
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
		// Who's online, for @mentions (offline players get pinged on Discord).
		if (type.equals("join") && !name.isEmpty()) online.add(name.toLowerCase(java.util.Locale.ROOT));
		if (type.equals("leave")) online.remove(name.toLowerCase(java.util.Locale.ROOT));
		if (type.equals("start") || type.equals("stop")) online.clear();
		if (type.equals("chat")) {
			if (e.has("uuid")) races.put(e.get("uuid").getAsString(), e.has("race") ? e.get("race").getAsString().toLowerCase(Locale.ROOT) : "");
			if (settings.enabled && settings.chatToDiscord) chat(e.has("uuid") ? e.get("uuid").getAsString() : null,
					chatName(name, e.has("race") ? e.get("race").getAsString() : ""), e.has("text") ? e.get("text").getAsString() : "");
			return;
		}
		if (type.equals("lag") || type.equals("lagOver") || type.equals("perf")) {
			alerts.alert(type, Map.of("message", e.has("text") ? e.get("text").getAsString() : ""));
			return;
		}
		if (type.equals("broadcast") && !broadcastAllowed(e.has("text") ? e.get("text").getAsString() : "")) return;
		Map<String, String> values = new LinkedHashMap<>();
		values.put("player", name);
		values.put("message", e.has("text") ? e.get("text").getAsString() : "");
		values.put("advancement", e.has("advancement") ? e.get("advancement").getAsString() : "");
		post(type, values);
		lastPresence = "";
	}

	/** Recent broadcasts: the same text again within a minute is skipped, and at most 20 a minute go out. */
	private final java.util.ArrayDeque<long[]> broadcastTimes = new java.util.ArrayDeque<>();
	private final Map<String, Long> broadcastTexts = new java.util.HashMap<>();

	private synchronized boolean broadcastAllowed(String text) {
		long now = System.currentTimeMillis();
		broadcastTexts.values().removeIf(t -> now - t > 60_000);
		while (!broadcastTimes.isEmpty() && now - broadcastTimes.peekFirst()[0] > 60_000) broadcastTimes.removeFirst();
		if (broadcastTexts.containsKey(text) || broadcastTimes.size() >= 20) return false;
		broadcastTexts.put(text, now);
		broadcastTimes.addLast(new long[] { now });
		return true;
	}

	private void watch() {
		Supervisor.State state = server.state();
		Supervisor.State before = lastState;
		lastState = state;
		if (before != null && state != before) {
			if (state == Supervisor.State.RUNNING) post("start", Map.of());
			else if (state == Supervisor.State.CRASHED) crashed();
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

	/** Crash message with what happened and the mod that probably caused it. */
	private void crashed() {
		Path report = server.lastCrashReport();
		CrashReports.Summary s = report == null ? new CrashReports.Summary("", null) : CrashReports.read(report, server.serverDir().resolve("mods"));
		Map<String, String> values = new LinkedHashMap<>();
		values.put("mod", s.mod() == null ? "" : " (probably " + s.mod() + ")");
		values.put("message", s.what().isEmpty() ? "no crash report" : s.what());
		server.note("Crash" + values.get("mod") + ": " + values.get("message"));
		System.out.println("[alert] Crash" + values.get("mod") + ": " + values.get("message"));
		post("crash", values);
	}

	/** Where alerts from the mod go: this bridge, or the panel's combined one (Discord + stats markers). */
	private volatile Notifier alerts = this;

	void routeAlerts(Notifier n) {
		alerts = n;
	}

	/** When each alert type was last sent, for the cooldowns. */
	private final Map<String, Long> alertedAt = new java.util.concurrent.ConcurrentHashMap<>();
	private static final Map<String, Long> COOLDOWN = Map.of("lag", 15 * 60_000L, "diskLow", 6 * 3600_000L, "memory", 6 * 3600_000L);

	/** Admin alerts: always noted in the panel's console, posted to the alerts channel when switched on. */
	@Override
	public void alert(String type, Map<String, String> values) {
		long now = System.currentTimeMillis();
		Long last = alertedAt.get(type);
		long cooldown = COOLDOWN.getOrDefault(type, 0L);
		if (last != null && now - last < cooldown) return;
		alertedAt.put(type, now);
		String text = render(type, values);
		String line = "Alert: " + (text.isBlank() ? type + " " + values : text);
		server.note(line);
		System.out.println("[alert] " + line);
		post(type, values);
	}

	private String render(String type, Map<String, String> values) {
		String text = settings.templates.getOrDefault(type, "");
		for (Map.Entry<String, String> v : values.entrySet()) text = text.replace("{" + v.getKey() + "}", escape(v.getValue()));
		return text;
	}

	static final java.util.Set<String> ALERTS = java.util.Set.of("lag", "lagOver", "taskFailed", "backupFailed", "trimDone", "diskLow", "memory", "crash", "perf");

	private void post(String type, Map<String, String> values) {
		if (!settings.enabled || !Boolean.TRUE.equals(settings.events.get(type))) return;
		TextChannel channel = "alerts".equals(settings.eventChannels.get(type)) ? alertChannel() : channel();
		if (channel == null) return;
		String text = render(type, values);
		if (text.isBlank()) return;
		channel.sendMessage(text).setAllowedMentions(List.of()).queue(ok -> { }, err -> status = "Can't post in the channel: " + err.getMessage());
	}

	/** Players online in game (lower case), from join and leave events. */
	private final Set<String> online = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static final java.util.regex.Pattern MENTION = java.util.regex.Pattern.compile("@\u200b?((?:[A-Za-z0-9_]|\\\\_){3,32})");

	/**
	 * @mentions (Settings → Gameplay → Chat & tab list): "@Name" of a linked player who isn't in game becomes a Discord ping.
	 * Returns the Discord ids to allow pinging; text[0] gets the pings written in.
	 */
	private List<String> pings(String[] text) {
		if (!text[0].contains("@")) return List.of();
		eu.explorerseden.nicecontrolcenter.Tweaks.Chat c = eu.explorerseden.nicecontrolcenter.Tweaks
				.read(server.serverDir().resolve("config").resolve(eu.explorerseden.nicecontrolcenter.Tweaks.FILE)).chat;
		if (!c.mentions || !c.discordPing) return List.of();
		List<String> ids = new ArrayList<>();
		java.util.regex.Matcher m = MENTION.matcher(text[0]);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			String who = m.group(1).replace("\\_", "_").toLowerCase(java.util.Locale.ROOT);
			Link link = online.contains(who) ? null : links.stream().filter(l -> l.name() != null && l.name().equalsIgnoreCase(who)).findFirst().orElse(null);
			if (link == null) continue;
			m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement("<@" + link.discordId() + ">"));
			if (!ids.contains(link.discordId())) ids.add(link.discordId());
		}
		m.appendTail(out);
		text[0] = out.toString();
		return ids;
	}

	/** "Oakhearted ┃ NiceRon" from the name format; without a race the {race} part goes, with its brackets. */
	String chatName(String player, String race) {
		String format = settings.nameFormat;
		String out = race == null || race.isBlank()
				? format.replaceAll("[\\[(<{]?\\{race}[\\])>}]?", "").replace("{player}", player)
				: format.replace("{race}", race).replace("{player}", player);
		out = out.replaceAll("\\s+", " ").strip().replaceAll("^[·•|┃\\-:]\\s*|\\s*[·•|┃\\-:]$", "").strip();
		// Webhook names: at most 80 characters, and never empty.
		return out.isEmpty() ? player : out.length() > 80 ? out.substring(0, 80) : out;
	}

	/** A message an admin sent to the game from the panel (Chat tab or "say"): to Discord like player chat, as "Admin ┃ <account>". */
	public void adminChat(String user, String text) {
		if (!settings.enabled || !settings.chatToDiscord) return;
		String clean = text == null ? "" : text.replaceAll("[\\r\\n]+", " ").strip();
		if (clean.isEmpty()) return;
		timer.execute(() -> chat(null, adminLabel(user), clean));
	}

	/** The mod's message_prefix ("Admin ┃ {user}:") with the account filled in, without the colon. */
	private String adminLabel(String user) {
		String template = "Admin ┃ {user}:";
		try {
			Path config = server.serverDir().resolve("config").resolve("nicecontrolcenter.json");
			if (Files.exists(config)) {
				JsonObject o = com.google.gson.JsonParser.parseString(Files.readString(config)).getAsJsonObject();
				if (o.has("message_prefix") && o.get("message_prefix").isJsonPrimitive() && !o.get("message_prefix").getAsString().isBlank()) {
					template = o.get("message_prefix").getAsString().strip();
				}
			}
		} catch (IOException | RuntimeException e) {
			// The default.
		}
		String label = user == null || user.isBlank() ? template.replaceAll("\\s*[┃|•·\\-]?\\s*\\{user\\}", "") : template.replace("{user}", user.strip());
		label = label.strip();
		if (label.endsWith(":")) label = label.substring(0, label.length() - 1).strip();
		return label.isEmpty() ? "Admin" : label;
	}

	/** Game → Discord through a webhook, so each message shows the player's name and head. */
	private void chat(String uuid, String name, String text) {
		TextChannel channel = channel();
		if (channel == null || text.isBlank()) return;
		String url = webhook(channel);
		if (url == null) {
			String[] plain = { escape(text) };
			List<String> ids = pings(plain);
			channel.sendMessage("**" + escape(name) + "**: " + plain[0]).setAllowedMentions(List.of())
					.mentionUsers(ids.toArray(String[]::new)).queue();
			return;
		}
		String[] content = { text };
		List<String> ids = pings(content);
		text = content[0];
		JsonObject body = new JsonObject();
		body.addProperty("content", text.length() > 1900 ? text.substring(0, 1900) + "…" : text);
		body.addProperty("username", name);
		// The player's head; without a player (admins from the panel) the bot's own picture.
		JDA bot = jda;
		String avatar = uuid != null ? "https://mc-heads.net/avatar/" + uuid + "/64"
				: bot != null && bot.getStatus() == JDA.Status.CONNECTED ? bot.getSelfUser().getEffectiveAvatarUrl() : null;
		if (avatar != null) body.addProperty("avatar_url", avatar);
		JsonObject allowed = new JsonObject();
		allowed.add("parse", new JsonArray());
		JsonArray users = new JsonArray();
		ids.forEach(users::add);
		allowed.add("users", users);
		body.add("allowed_mentions", allowed);
		http.sendAsync(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.discarding());
	}

	private String webhook(TextChannel channel) {
		String url = webhookUrl;
		if (url != null) return url;
		if (System.currentTimeMillis() < webhookRetryAt) return null;
		try {
			Webhook hook = channel.retrieveWebhooks().complete().stream().filter(w -> "Nice Control Center".equals(w.getName())).findFirst()
					.orElseGet(() -> channel.createWebhook("Nice Control Center").complete());
			webhookUrl = hook.getUrl();
			return webhookUrl;
		} catch (RuntimeException e) {
			// No "Manage Webhooks" permission: plain bot messages instead, asking again in 10 minutes.
			webhookRetryAt = System.currentTimeMillis() + 10 * 60_000;
			return null;
		}
	}

	/** Player names and messages can't format or ping in Discord. */
	private static String escape(String text) {
		return text == null ? "" : text.replaceAll("([*_~`|>\\\\])", "\\\\$1").replace("@", "@​");
	}
}
