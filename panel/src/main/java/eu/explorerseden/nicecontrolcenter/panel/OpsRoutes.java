package eu.explorerseden.nicecontrolcenter.panel;

import eu.explorerseden.nicecontrolcenter.Json;
import io.javalin.http.HttpStatus;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static eu.explorerseden.nicecontrolcenter.panel.Web.*;

/** Backups and scheduled tasks. */
final class OpsRoutes {
	private final Backups backups;
	private final Scheduler scheduler;
	private final Audit audit;

	OpsRoutes(Backups backups, Scheduler scheduler, Audit audit) {
		this.backups = backups;
		this.scheduler = scheduler;
		this.audit = audit;
	}

	void register(JavalinDefaultRoutingApi routes) {
		// ── Backups ──
		routes.get("/api/backups", guard(Permissions.BACKUP_VIEW, (ctx, me) -> {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("backups", backups.list());
			out.put("status", backups.status());
			out.put("settings", backups.settings());
			json(ctx, out);
		}));

		routes.post("/api/backups", guard(Permissions.BACKUP_CREATE, (ctx, me) -> {
			if (backups.busy()) {
				error(ctx, HttpStatus.CONFLICT, "A backup is already running.");
				return;
			}
			String label = str(body(ctx), "label").strip();
			audit.log(me.name(), "backup.create", label.isEmpty() ? "manual" : label, clientIp(ctx));
			Thread t = new Thread(() -> backups.create(label.isEmpty() ? "manual" : label, me.name()), "backup");
			t.setDaemon(true);
			t.start();
			json(ctx, Map.of("ok", true));
		}));

		routes.get("/api/backups/{name}/download", guard(Permissions.BACKUP_VIEW, (ctx, me) -> {
			Path file = backups.file(ctx.pathParam("name"));
			if (file == null) {
				error(ctx, HttpStatus.NOT_FOUND, "No such backup.");
				return;
			}
			audit.log(me.name(), "backup.download", file.getFileName().toString(), clientIp(ctx));
			ctx.header("Content-Disposition", "attachment; filename=\"" + file.getFileName() + "\"");
			ctx.header("Content-Length", String.valueOf(Files.size(file)));
			ctx.contentType("application/zip");
			InputStream in = Files.newInputStream(file);
			ctx.result(in);
		}));

		routes.get("/api/backups/{name}/contents", guard(Permissions.BACKUP_MANAGE, (ctx, me) -> {
			String name = ctx.pathParam("name");
			if (!backups.exists(name)) {
				error(ctx, HttpStatus.NOT_FOUND, "No such backup.");
				return;
			}
			json(ctx, Map.of("contents", backups.contents(name)));
		}));

		routes.post("/api/backups/{name}/restore", guard(Permissions.BACKUP_MANAGE, (ctx, me) -> {
			String name = ctx.pathParam("name");
			if (!backups.exists(name)) {
				error(ctx, HttpStatus.NOT_FOUND, "No such backup.");
				return;
			}
			String blocked = backups.restoreBlocked();
			if (blocked != null) {
				error(ctx, HttpStatus.CONFLICT, blocked);
				return;
			}
			List<String> only = strings(body(ctx), "only");
			audit.log(me.name(), "backup.restore", name + (only == null || only.isEmpty() ? "" : ": " + String.join(", ", only)), clientIp(ctx));
			Thread t = new Thread(() -> backups.restore(name, only, me.name()), "restore");
			t.setDaemon(true);
			t.start();
			json(ctx, Map.of("ok", true));
		}));

		routes.post("/api/backups/{name}/delete", guard(Permissions.BACKUP_MANAGE, (ctx, me) -> {
			String name = ctx.pathParam("name");
			backups.delete(name);
			audit.log(me.name(), "backup.delete", name, clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));

		routes.post("/api/backups/{name}/pin", guard(Permissions.BACKUP_MANAGE, (ctx, me) -> {
			String name = ctx.pathParam("name");
			if (!backups.exists(name)) {
				error(ctx, HttpStatus.NOT_FOUND, "No such backup.");
				return;
			}
			boolean pinned = Boolean.TRUE.equals(bool(body(ctx), "pinned"));
			backups.pin(name, pinned);
			audit.log(me.name(), pinned ? "backup.pin" : "backup.unpin", name, clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));

		routes.post("/api/backups/settings", guard(Permissions.BACKUP_MANAGE, (ctx, me) -> {
			Backups.Settings next;
			try {
				next = Json.GSON.fromJson(ctx.body(), Backups.Settings.class);
			} catch (RuntimeException e) {
				next = null;
			}
			if (next == null) {
				error(ctx, HttpStatus.BAD_REQUEST, "Unreadable settings");
				return;
			}
			String problem = backups.saveSettings(next);
			if (problem != null) {
				error(ctx, HttpStatus.BAD_REQUEST, problem);
				return;
			}
			audit.log(me.name(), "backup.settings", "keep " + next.keepLast + " recent, " + next.keepDaily + " daily, " + next.keepWeekly
					+ " weekly; excluded: " + String.join(", ", next.exclude), clientIp(ctx));
			json(ctx, Map.of("ok", true, "deleted", backups.prune()));
		}));

		// ── Scheduled tasks ──
		routes.get("/api/schedule", guard(Permissions.SERVER_VIEW, (ctx, me) -> {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("tasks", scheduler.view());
			out.put("zone", scheduler.zone());
			out.put("days", Scheduler.dayNames());
			out.put("now", System.currentTimeMillis());
			json(ctx, out);
		}));

		routes.post("/api/schedule", guard(Permissions.SCHEDULE_MANAGE, (ctx, me) -> {
			Scheduler.Task task;
			try {
				task = Json.GSON.fromJson(ctx.body(), Scheduler.Task.class);
			} catch (RuntimeException e) {
				task = null;
			}
			if (task == null) {
				error(ctx, HttpStatus.BAD_REQUEST, "Unreadable task");
				return;
			}
			boolean created = task.id == null || task.id.isBlank();
			String problem = scheduler.save(task);
			if (problem != null) {
				error(ctx, HttpStatus.BAD_REQUEST, problem);
				return;
			}
			audit.log(me.name(), created ? "schedule.create" : "schedule.update", task.name + (task.enabled ? "" : " (off)"), clientIp(ctx));
			json(ctx, Map.of("ok", true, "id", task.id));
		}));

		routes.post("/api/schedule/{id}/delete", guard(Permissions.SCHEDULE_MANAGE, (ctx, me) -> {
			Scheduler.Task task = scheduler.task(ctx.pathParam("id"));
			if (task == null || !scheduler.delete(task.id)) {
				error(ctx, HttpStatus.NOT_FOUND, "No such task.");
				return;
			}
			audit.log(me.name(), "schedule.delete", task.name, clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));

		routes.post("/api/schedule/{id}/run", guard(Permissions.SCHEDULE_MANAGE, (ctx, me) -> {
			Scheduler.Task task = scheduler.task(ctx.pathParam("id"));
			if (task == null) {
				error(ctx, HttpStatus.NOT_FOUND, "No such task.");
				return;
			}
			result(ctx, scheduler.run(task, me.name()));
		}));
	}
}
