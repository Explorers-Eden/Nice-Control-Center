package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonObject;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static eu.explorerseden.nicecontrolcenter.panel.Web.*;

/**
 * The Home page's "Needs attention" list and setup checklist: things that went wrong recently and
 * things not set up yet, each pointing at the page that deals with it.
 */
final class Home {
	private final Supervisor server;
	private final Scheduler scheduler;
	private final Backups backups;
	private final Discord discord;
	private final Trimmer trimmer;
	private final Branding branding;
	private final ServerTuning tuning;
	private final Stats stats;

	Home(Supervisor server, Scheduler scheduler, Backups backups, Discord discord, Trimmer trimmer, Branding branding, ServerTuning tuning, Stats stats) {
		this.server = server;
		this.scheduler = scheduler;
		this.backups = backups;
		this.discord = discord;
		this.trimmer = trimmer;
		this.branding = branding;
		this.tuning = tuning;
		this.stats = stats;
	}

	private static Map<String, Object> item(String level, String text, String tab, String action) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("level", level);
		m.put("text", text);
		m.put("tab", tab);
		m.put("action", action);
		return m;
	}

	Map<String, Object> view() {
		List<Map<String, Object>> attention = new ArrayList<>();
		if (server.state() == Supervisor.State.CRASHED) attention.add(item("bad", "The server crashed and is not running.", "server", "Open the console"));
		if (tuning.maintenance().enabled) attention.add(item("warn", "Maintenance mode is on: only staff can join.", "maintenance", "Maintenance"));
		long weekAgo = System.currentTimeMillis() - 7 * 86_400_000L;
		boolean backupTask = false;
		for (Map<String, Object> t : scheduler.view()) {
			Scheduler.Task task = (Scheduler.Task) t.get("task");
			if (task.enabled && task.steps.stream().anyMatch(s -> "backup".equals(s.type) || ("trim".equals(s.type) && s.backup))) backupTask = true;
			if (t.get("last") instanceof Scheduler.Run run && !run.ok() && run.time() > weekAgo) {
				attention.add(item("bad", "Scheduled task \"" + task.name + "\" failed: " + run.message(), "schedule", "Schedule"));
			}
		}
		Object backupError = backups.status().get("lastError");
		if (backupError instanceof String e && !e.isBlank()) attention.add(item("bad", "The last backup failed: " + e, "backups", "Backups"));
		JsonObject last = stats.latest();
		if (last != null && last.has("free") && last.has("total") && last.get("total").getAsLong() > 0) {
			double pct = 100.0 * last.get("free").getAsLong() / last.get("total").getAsLong();
			if (pct < 10) attention.add(item("bad", "The disk is almost full: " + Backups.human(last.get("free").getAsLong()) + " free.", "backups", "Backups"));
		}
		if (!server.eulaAccepted()) attention.add(item("warn", "The Minecraft EULA isn't accepted yet, so the server can't start.", "server", "Console"));

		List<Map<String, Object>> setup = new ArrayList<>();
		setup.add(check("backups", "Schedule regular backups", backupTask, "schedule"));
		setup.add(check("borders", "Set the world borders", !trimmer.borders().isEmpty(), "world"));
		setup.add(check("discord", "Connect Discord", discord.settings().enabled, "discord"));
		setup.add(check("logo", "Add your logo", branding.has("panel") || branding.has("map"), "appearance"));

		Map<String, Object> out = new LinkedHashMap<>();
		// The greeting banner: what players see in the server list, and how the server is doing.
		java.util.Properties props = ServerTuning.readProperties(server.serverDir().resolve("server.properties"));
		out.put("motd", props.getProperty("motd", "A Minecraft Server"));
		out.put("maxPlayers", props.getProperty("max-players", "20"));
		out.put("state", server.state().name());
		if (last != null && last.has("players") && server.running()) out.put("players", last.get("players").getAsInt());
		double up = stats.uptime7d();
		if (up >= 0) out.put("uptime7d", up);
		out.put("attention", attention);
		out.put("setup", setup);
		return out;
	}

	private static Map<String, Object> check(String id, String text, boolean done, String tab) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id);
		m.put("text", text);
		m.put("done", done);
		m.put("tab", tab);
		return m;
	}

	void register(JavalinDefaultRoutingApi routes) {
		routes.get("/api/home", guard(Permissions.SERVER_VIEW, (ctx, me) -> json(ctx, view())));
	}
}
