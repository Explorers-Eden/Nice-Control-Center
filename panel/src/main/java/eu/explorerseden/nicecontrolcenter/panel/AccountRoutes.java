package eu.explorerseden.nicecontrolcenter.panel;

import com.google.gson.JsonObject;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static eu.explorerseden.nicecontrolcenter.panel.Web.*;

/** Users, roles, the audit log and changing your own password. All of it needs the database. */
final class AccountRoutes {
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{2,32}");
	private static final Pattern ROLE_NAME = Pattern.compile("[\\p{L}\\p{N} _.-]{2,32}");

	private final Db db;
	private final Accounts accounts;
	private final Auth auth;
	private final Audit audit;

	AccountRoutes(Db db, Accounts accounts, Auth auth, Audit audit) {
		this.db = db;
		this.accounts = accounts;
		this.auth = auth;
		this.audit = audit;
	}

	void register(JavalinDefaultRoutingApi routes) {
		routes.get("/api/users", guard(Permissions.USERS_ADMIN, (ctx, me) -> {
			if (!dbReady(ctx)) return;
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("users", accounts.users());
			out.put("roles", accounts.roles());
			out.put("permissions", Permissions.CATALOG);
			out.put("envAdmin", auth.envUser());
			json(ctx, out);
		}));

		routes.post("/api/users", guard(Permissions.USERS_ADMIN, (ctx, me) -> {
			if (!dbReady(ctx)) return;
			JsonObject body = body(ctx);
			String name = str(body, "name").strip();
			String password = str(body, "password");
			List<Integer> roles = ints(body, "roles");
			String problem = checkName(name);
			if (problem == null) problem = checkPassword(password);
			if (problem == null) problem = checkRoles(roles);
			if (problem != null) {
				error(ctx, HttpStatus.BAD_REQUEST, problem);
				return;
			}
			if (accounts.findLogin(name) != null) {
				error(ctx, HttpStatus.CONFLICT, "There is already a user called " + name + ".");
				return;
			}
			accounts.createUser(name, Auth.hashPassword(password), roles);
			audit.log(me.name(), "user.create", name + " with roles " + roleNames(roles), clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));

		routes.post("/api/users/{id}", guard(Permissions.USERS_ADMIN, (ctx, me) -> {
			if (!dbReady(ctx)) return;
			int id = Integer.parseInt(ctx.pathParam("id"));
			String name = accounts.userName(id);
			if (name == null) {
				error(ctx, HttpStatus.NOT_FOUND, "No such user.");
				return;
			}
			JsonObject body = body(ctx);
			String password = body.has("password") ? str(body, "password") : null;
			if (password != null && password.isEmpty()) password = null;
			Boolean disabled = bool(body, "disabled");
			List<Integer> roles = ints(body, "roles");
			String problem = password != null ? checkPassword(password) : null;
			if (problem == null && roles != null) problem = checkRoles(roles);
			if (problem == null && Boolean.TRUE.equals(disabled) && me.userId() != null && me.userId() == id) problem = "You can't disable your own account.";
			if (problem != null) {
				error(ctx, HttpStatus.BAD_REQUEST, problem);
				return;
			}
			accounts.updateUser(id, password == null ? null : Auth.hashPassword(password), disabled, roles);
			List<String> changes = new java.util.ArrayList<>();
			if (password != null) changes.add("new password");
			if (disabled != null) changes.add(disabled ? "disabled" : "enabled");
			if (roles != null) changes.add("roles " + roleNames(roles));
			if (password != null || Boolean.TRUE.equals(disabled)) auth.forget(id);
			else auth.forgetPermissions();
			audit.log(me.name(), "user.update", name + ": " + String.join(", ", changes), clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));

		routes.post("/api/users/{id}/delete", guard(Permissions.USERS_ADMIN, (ctx, me) -> {
			if (!dbReady(ctx)) return;
			int id = Integer.parseInt(ctx.pathParam("id"));
			if (me.userId() != null && me.userId() == id) {
				error(ctx, HttpStatus.BAD_REQUEST, "You can't delete your own account.");
				return;
			}
			String name = accounts.userName(id);
			if (name == null) {
				error(ctx, HttpStatus.NOT_FOUND, "No such user.");
				return;
			}
			auth.forget(id);
			accounts.deleteUser(id);
			audit.log(me.name(), "user.delete", name, clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));

		routes.post("/api/roles", guard(Permissions.USERS_ADMIN, (ctx, me) -> {
			if (!dbReady(ctx)) return;
			JsonObject body = body(ctx);
			Integer id = body.has("id") && !body.get("id").isJsonNull() ? body.get("id").getAsInt() : null;
			String name = str(body, "name").strip();
			List<String> permissions = strings(body, "permissions");
			Accounts.Role existing = id == null ? null : accounts.role(id);
			if (id != null && existing == null) {
				error(ctx, HttpStatus.NOT_FOUND, "No such role.");
				return;
			}
			if (existing != null && existing.builtin()) {
				if (existing.permissions().contains(Permissions.ALL)) {
					error(ctx, HttpStatus.BAD_REQUEST, "The Admin role always has every permission.");
					return;
				}
				name = existing.name();
			}
			if (!ROLE_NAME.matcher(name).matches()) {
				error(ctx, HttpStatus.BAD_REQUEST, "Role names are 2–32 letters, digits, spaces, dots, dashes or underscores.");
				return;
			}
			if (permissions == null || permissions.stream().anyMatch(p -> !Permissions.known(p) || Permissions.ALL.equals(p))) {
				error(ctx, HttpStatus.BAD_REQUEST, "Unknown permission.");
				return;
			}
			String finalName = name;
			if (accounts.roles().stream().anyMatch(r -> r.name().equalsIgnoreCase(finalName) && (id == null || r.id() != id))) {
				error(ctx, HttpStatus.CONFLICT, "There is already a role called " + name + ".");
				return;
			}
			accounts.saveRole(id, name, permissions.stream().distinct().toList());
			auth.forgetPermissions();
			audit.log(me.name(), id == null ? "role.create" : "role.update", name + ": " + String.join(", ", permissions), clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));

		routes.post("/api/roles/{id}/delete", guard(Permissions.USERS_ADMIN, (ctx, me) -> {
			if (!dbReady(ctx)) return;
			Accounts.Role role = accounts.role(Integer.parseInt(ctx.pathParam("id")));
			if (role == null) {
				error(ctx, HttpStatus.NOT_FOUND, "No such role.");
				return;
			}
			if (role.builtin()) {
				error(ctx, HttpStatus.BAD_REQUEST, "Built-in roles can't be deleted.");
				return;
			}
			accounts.deleteRole(role.id());
			auth.forgetPermissions();
			audit.log(me.name(), "role.delete", role.name(), clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));

		routes.get("/api/audit", guard(Permissions.AUDIT_VIEW, (ctx, me) -> {
			if (!dbReady(ctx)) return;
			long before = parseLong(ctx.queryParam("before"));
			json(ctx, Map.of("entries", accounts.auditLog(before, 100, ctx.queryParam("q"))));
		}));

		routes.get("/api/me/keys", guard(null, (ctx, me) -> {
			if (me.envAdmin()) {
				json(ctx, Map.of("keys", List.of(), "note", "The container admin logs in to SFTP with its password."));
				return;
			}
			if (!dbReady(ctx)) return;
			json(ctx, Map.of("keys", accounts.keys(me.userId()).stream()
					.map(k -> Map.of("id", k.id(), "name", k.name(), "fingerprint", k.fingerprint(), "createdAt", k.createdAt(), "lastUsed", k.lastUsed())).toList()));
		}));

		routes.post("/api/me/keys", guard(null, (ctx, me) -> {
			if (me.envAdmin()) {
				error(ctx, HttpStatus.BAD_REQUEST, "The container admin logs in to SFTP with its password.");
				return;
			}
			if (!dbReady(ctx)) return;
			String line = str(body(ctx), "key").strip();
			String fingerprint;
			String name;
			try {
				var entry = org.apache.sshd.common.config.keys.AuthorizedKeyEntry.parseAuthorizedKeyEntry(line);
				var key = entry == null ? null : entry.resolvePublicKey(null, org.apache.sshd.common.config.keys.PublicKeyEntryResolver.IGNORING);
				if (key == null) throw new IllegalArgumentException();
				fingerprint = org.apache.sshd.common.config.keys.KeyUtils.getFingerPrint(key);
				name = entry.getComment() == null || entry.getComment().isBlank() ? org.apache.sshd.common.config.keys.KeyUtils.getKeyType(key) : entry.getComment().strip();
			} catch (Exception e) {
				error(ctx, HttpStatus.BAD_REQUEST, "That isn't a public key. Paste one line from a .pub file, like ssh-ed25519 AAAA… name@pc.");
				return;
			}
			if (accounts.keys(me.userId()).stream().anyMatch(k -> k.fingerprint().equals(fingerprint))) {
				error(ctx, HttpStatus.CONFLICT, "This key is already added.");
				return;
			}
			accounts.addKey(me.userId(), name.length() > 60 ? name.substring(0, 60) : name, line, fingerprint);
			audit.log(me.name(), "user.sshkey.add", name + " " + fingerprint, clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));

		routes.post("/api/me/keys/{id}/delete", guard(null, (ctx, me) -> {
			if (me.envAdmin() || !dbReady(ctx)) return;
			if (accounts.deleteKey(me.userId(), Integer.parseInt(ctx.pathParam("id")))) audit.log(me.name(), "user.sshkey.delete", ctx.pathParam("id"), clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));

		routes.post("/api/me/password", guard(null, (ctx, me) -> {
			if (me.envAdmin()) {
				error(ctx, HttpStatus.BAD_REQUEST, "This account's password is set with PANEL_ADMIN_PASSWORD in the container settings.");
				return;
			}
			if (!dbReady(ctx)) return;
			JsonObject body = body(ctx);
			Accounts.Login login = accounts.findLogin(me.name());
			if (login == null || !Auth.checkPassword(str(body, "current"), login.passwordHash())) {
				error(ctx, HttpStatus.BAD_REQUEST, "The current password is wrong.");
				return;
			}
			String password = str(body, "password");
			String problem = checkPassword(password);
			if (problem != null) {
				error(ctx, HttpStatus.BAD_REQUEST, problem);
				return;
			}
			accounts.updateUser(login.id(), Auth.hashPassword(password), null, null);
			auth.forget(login.id());
			audit.log(me.name(), "user.password", "changed their own password", clientIp(ctx));
			json(ctx, Map.of("ok", true));
		}));
	}

	private boolean dbReady(Context ctx) {
		if (db == null) {
			error(ctx, HttpStatus.SERVICE_UNAVAILABLE, "User accounts need a database: set DB_URL, DB_USER and DB_PASS in the container settings.");
			return false;
		}
		if (!db.ready()) {
			error(ctx, HttpStatus.SERVICE_UNAVAILABLE, db.problem());
			return false;
		}
		return true;
	}

	private String checkName(String name) {
		if (!NAME.matcher(name).matches()) return "User names are 2–32 letters, digits, dots, dashes or underscores.";
		if (name.equalsIgnoreCase(auth.envUser())) return name + " is the container's admin account.";
		return null;
	}

	private static String checkPassword(String password) {
		if (password == null || password.length() < Auth.MIN_PASSWORD) return "Passwords need at least " + Auth.MIN_PASSWORD + " characters.";
		if (password.length() > 200) return "That password is too long.";
		return null;
	}

	private String checkRoles(List<Integer> roles) throws SQLException {
		if (roles == null) return "Choose the user's roles.";
		Set<Integer> known = accounts.roles().stream().map(Accounts.Role::id).collect(Collectors.toSet());
		return known.containsAll(roles) ? null : "Unknown role.";
	}

	private String roleNames(List<Integer> roles) throws SQLException {
		if (roles.isEmpty()) return "(none)";
		return accounts.roles().stream().filter(r -> roles.contains(r.id())).map(Accounts.Role::name).collect(Collectors.joining(", "));
	}

	private static long parseLong(String value) {
		try {
			return value == null ? 0 : Long.parseLong(value);
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
