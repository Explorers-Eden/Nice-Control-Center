package eu.explorerseden.nicecontrolcenter.panel;

import java.util.List;
import java.util.Set;

/** Everything a role can allow. "*" allows all, including permissions added later. */
public final class Permissions {
	public record Permission(String id, String group, String label) {
	}

	public static final String ALL = "*";
	public static final String SERVER_VIEW = "server.view";
	public static final String SERVER_POWER = "server.power";
	public static final String CONSOLE_READ = "console.read";
	public static final String CONSOLE_WRITE = "console.write";
	public static final String SETTINGS_JAVA = "settings.java";
	public static final String DASHBOARD_VIEW = "dashboard.view";
	public static final String PLAYERS_MANAGE = "players.manage";
	public static final String SETTINGS_SERVER = "settings.server";
	public static final String UPDATES_MANAGE = "updates.manage";
	public static final String SCHEDULE_MANAGE = "schedule.manage";
	public static final String BACKUP_VIEW = "backup.view";
	public static final String BACKUP_CREATE = "backup.create";
	public static final String BACKUP_MANAGE = "backup.manage";
	public static final String USERS_ADMIN = "users.admin";
	public static final String AUDIT_VIEW = "audit.view";

	public static final List<Permission> CATALOG = List.of(
			new Permission(SERVER_VIEW, "Server", "See the server state"),
			new Permission(SERVER_POWER, "Server", "Start, stop, restart and kill the server"),
			new Permission(CONSOLE_READ, "Console", "Read the console"),
			new Permission(CONSOLE_WRITE, "Console", "Run console commands"),
			new Permission(DASHBOARD_VIEW, "Dashboard", "See the dashboard: performance, world, players, errors, reports"),
			new Permission(PLAYERS_MANAGE, "Dashboard", "Message, kick and ban players"),
			new Permission(SETTINGS_SERVER, "Settings", "Change server.properties, game rules and data pack settings"),
			new Permission(SETTINGS_JAVA, "Settings", "Change startup and Java settings"),
			new Permission(UPDATES_MANAGE, "Settings", "Install and roll back mod and data pack updates"),
			new Permission(SCHEDULE_MANAGE, "Settings", "Edit and run scheduled tasks and commands"),
			new Permission(BACKUP_VIEW, "Backups", "See and download backups"),
			new Permission(BACKUP_CREATE, "Backups", "Make a backup"),
			new Permission(BACKUP_MANAGE, "Backups", "Restore, delete and pin backups, change backup settings"),
			new Permission(USERS_ADMIN, "Accounts", "Manage users and roles"),
			new Permission(AUDIT_VIEW, "Accounts", "Read the audit log"));

	private Permissions() {
	}

	public static boolean has(Set<String> granted, String permission) {
		return granted.contains(ALL) || granted.contains(permission);
	}

	public static boolean known(String permission) {
		return ALL.equals(permission) || CATALOG.stream().anyMatch(p -> p.id().equals(permission));
	}
}
