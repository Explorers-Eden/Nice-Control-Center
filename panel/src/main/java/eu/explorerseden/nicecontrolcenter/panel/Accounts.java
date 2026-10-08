package eu.explorerseden.nicecontrolcenter.panel;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Users, roles, sessions and the audit log in Postgres. Plain JDBC; every method opens its own connection. */
public final class Accounts {
	public record User(int id, String name, boolean disabled, long createdAt, long lastLogin, List<Integer> roles) {
	}

	public record Role(int id, String name, boolean builtin, List<String> permissions, int users) {
	}

	public record Login(int id, String name, String passwordHash, boolean disabled) {
	}

	public record SessionRow(int userId, String name, boolean disabled, long expiresAt) {
	}

	private final Db db;

	public Accounts(Db db) {
		this.db = db;
	}

	// ── Users ──────────────────────────────────────────────────────────────

	public Login findLogin(String name) throws SQLException {
		try (Connection c = db.connection(); PreparedStatement st = c.prepareStatement(
				"SELECT id, name, password_hash, disabled FROM users WHERE lower(name) = lower(?)")) {
			st.setString(1, name);
			try (ResultSet rs = st.executeQuery()) {
				return rs.next() ? new Login(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)) : null;
			}
		}
	}

	public Set<String> permissions(int userId) throws SQLException {
		Set<String> out = new HashSet<>();
		try (Connection c = db.connection(); PreparedStatement st = c.prepareStatement(
				"SELECT DISTINCT rp.permission FROM user_roles ur JOIN role_permissions rp ON rp.role_id = ur.role_id WHERE ur.user_id = ?")) {
			st.setInt(1, userId);
			try (ResultSet rs = st.executeQuery()) {
				while (rs.next()) out.add(rs.getString(1));
			}
		}
		return out;
	}

	public List<User> users() throws SQLException {
		Map<Integer, List<Integer>> roles = new LinkedHashMap<>();
		List<User> out = new ArrayList<>();
		try (Connection c = db.connection()) {
			try (PreparedStatement st = c.prepareStatement("SELECT user_id, role_id FROM user_roles"); ResultSet rs = st.executeQuery()) {
				while (rs.next()) roles.computeIfAbsent(rs.getInt(1), k -> new ArrayList<>()).add(rs.getInt(2));
			}
			try (PreparedStatement st = c.prepareStatement("SELECT id, name, disabled, created_at, last_login FROM users ORDER BY lower(name)");
					ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					out.add(new User(rs.getInt(1), rs.getString(2), rs.getBoolean(3), millis(rs.getTimestamp(4)), millis(rs.getTimestamp(5)),
							roles.getOrDefault(rs.getInt(1), List.of())));
				}
			}
		}
		return out;
	}

	public String userName(int id) throws SQLException {
		try (Connection c = db.connection(); PreparedStatement st = c.prepareStatement("SELECT name FROM users WHERE id = ?")) {
			st.setInt(1, id);
			try (ResultSet rs = st.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		}
	}

	public int createUser(String name, String passwordHash, List<Integer> roleIds) throws SQLException {
		try (Connection c = db.connection()) {
			c.setAutoCommit(false);
			int id;
			try (PreparedStatement st = c.prepareStatement("INSERT INTO users (name, password_hash) VALUES (?, ?) RETURNING id")) {
				st.setString(1, name);
				st.setString(2, passwordHash);
				try (ResultSet rs = st.executeQuery()) {
					rs.next();
					id = rs.getInt(1);
				}
			}
			setRoles(c, id, roleIds);
			c.commit();
			return id;
		}
	}

	/** Null fields stay as they are. */
	public void updateUser(int id, String passwordHash, Boolean disabled, List<Integer> roleIds) throws SQLException {
		try (Connection c = db.connection()) {
			c.setAutoCommit(false);
			if (passwordHash != null) {
				try (PreparedStatement st = c.prepareStatement("UPDATE users SET password_hash = ? WHERE id = ?")) {
					st.setString(1, passwordHash);
					st.setInt(2, id);
					st.executeUpdate();
				}
			}
			if (disabled != null) {
				try (PreparedStatement st = c.prepareStatement("UPDATE users SET disabled = ? WHERE id = ?")) {
					st.setBoolean(1, disabled);
					st.setInt(2, id);
					st.executeUpdate();
				}
			}
			if (roleIds != null) setRoles(c, id, roleIds);
			c.commit();
		}
	}

	public void deleteUser(int id) throws SQLException {
		update("DELETE FROM users WHERE id = ?", id);
	}

	public void touchLogin(int id) throws SQLException {
		update("UPDATE users SET last_login = now() WHERE id = ?", id);
	}

	private static void setRoles(Connection c, int userId, List<Integer> roleIds) throws SQLException {
		try (PreparedStatement st = c.prepareStatement("DELETE FROM user_roles WHERE user_id = ?")) {
			st.setInt(1, userId);
			st.executeUpdate();
		}
		try (PreparedStatement st = c.prepareStatement("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
			for (int role : roleIds) {
				st.setInt(1, userId);
				st.setInt(2, role);
				st.addBatch();
			}
			st.executeBatch();
		}
	}

	// ── Roles ──────────────────────────────────────────────────────────────

	public List<Role> roles() throws SQLException {
		Map<Integer, List<String>> perms = new LinkedHashMap<>();
		Map<Integer, Integer> counts = new LinkedHashMap<>();
		List<Role> out = new ArrayList<>();
		try (Connection c = db.connection()) {
			try (PreparedStatement st = c.prepareStatement("SELECT role_id, permission FROM role_permissions ORDER BY permission"); ResultSet rs = st.executeQuery()) {
				while (rs.next()) perms.computeIfAbsent(rs.getInt(1), k -> new ArrayList<>()).add(rs.getString(2));
			}
			try (PreparedStatement st = c.prepareStatement("SELECT role_id, count(*) FROM user_roles GROUP BY role_id"); ResultSet rs = st.executeQuery()) {
				while (rs.next()) counts.put(rs.getInt(1), rs.getInt(2));
			}
			try (PreparedStatement st = c.prepareStatement("SELECT id, name, builtin FROM roles ORDER BY builtin DESC, id"); ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					int id = rs.getInt(1);
					out.add(new Role(id, rs.getString(2), rs.getBoolean(3), perms.getOrDefault(id, List.of()), counts.getOrDefault(id, 0)));
				}
			}
		}
		return out;
	}

	public Role role(int id) throws SQLException {
		return roles().stream().filter(r -> r.id() == id).findFirst().orElse(null);
	}

	/** Creates the role when id is null. Returns its id. */
	public int saveRole(Integer id, String name, List<String> permissions) throws SQLException {
		try (Connection c = db.connection()) {
			c.setAutoCommit(false);
			int roleId;
			if (id == null) {
				try (PreparedStatement st = c.prepareStatement("INSERT INTO roles (name) VALUES (?) RETURNING id")) {
					st.setString(1, name);
					try (ResultSet rs = st.executeQuery()) {
						rs.next();
						roleId = rs.getInt(1);
					}
				}
			} else {
				roleId = id;
				try (PreparedStatement st = c.prepareStatement("UPDATE roles SET name = ? WHERE id = ? AND NOT builtin")) {
					st.setString(1, name);
					st.setInt(2, roleId);
					st.executeUpdate();
				}
			}
			try (PreparedStatement st = c.prepareStatement("DELETE FROM role_permissions WHERE role_id = ?")) {
				st.setInt(1, roleId);
				st.executeUpdate();
			}
			try (PreparedStatement st = c.prepareStatement("INSERT INTO role_permissions (role_id, permission) VALUES (?, ?)")) {
				for (String p : permissions) {
					st.setInt(1, roleId);
					st.setString(2, p);
					st.addBatch();
				}
				st.executeBatch();
			}
			c.commit();
			return roleId;
		}
	}

	public void deleteRole(int id) throws SQLException {
		update("DELETE FROM roles WHERE id = ? AND NOT builtin", id);
	}

	// ── Sessions ───────────────────────────────────────────────────────────

	public void createSession(String tokenHash, int userId, long expiresAt, String ip, String userAgent) throws SQLException {
		try (Connection c = db.connection(); PreparedStatement st = c.prepareStatement(
				"INSERT INTO sessions (token_hash, user_id, expires_at, ip, user_agent) VALUES (?, ?, ?, ?, ?)")) {
			st.setString(1, tokenHash);
			st.setInt(2, userId);
			st.setTimestamp(3, new Timestamp(expiresAt));
			st.setString(4, ip);
			st.setString(5, userAgent == null ? null : userAgent.substring(0, Math.min(300, userAgent.length())));
			st.executeUpdate();
		}
	}

	public SessionRow session(String tokenHash) throws SQLException {
		try (Connection c = db.connection(); PreparedStatement st = c.prepareStatement(
				"SELECT s.user_id, u.name, u.disabled, s.expires_at FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ?")) {
			st.setString(1, tokenHash);
			try (ResultSet rs = st.executeQuery()) {
				return rs.next() ? new SessionRow(rs.getInt(1), rs.getString(2), rs.getBoolean(3), millis(rs.getTimestamp(4))) : null;
			}
		}
	}

	public void deleteSession(String tokenHash) throws SQLException {
		try (Connection c = db.connection(); PreparedStatement st = c.prepareStatement("DELETE FROM sessions WHERE token_hash = ?")) {
			st.setString(1, tokenHash);
			st.executeUpdate();
		}
	}

	public void deleteSessionsOf(int userId) throws SQLException {
		update("DELETE FROM sessions WHERE user_id = ?", userId);
	}

	public void deleteExpiredSessions() throws SQLException {
		try (Connection c = db.connection(); PreparedStatement st = c.prepareStatement("DELETE FROM sessions WHERE expires_at < now()")) {
			st.executeUpdate();
		}
	}

	// ── SSH keys ───────────────────────────────────────────────────────────

	public record SshKey(int id, String name, String fingerprint, long createdAt, long lastUsed, String publicKey) {
	}

	public List<SshKey> keys(int userId) throws SQLException {
		List<SshKey> out = new ArrayList<>();
		try (Connection c = db.connection(); PreparedStatement st = c.prepareStatement(
				"SELECT id, name, fingerprint, created_at, last_used, public_key FROM user_ssh_keys WHERE user_id = ? ORDER BY id")) {
			st.setInt(1, userId);
			try (ResultSet rs = st.executeQuery()) {
				while (rs.next()) out.add(new SshKey(rs.getInt(1), rs.getString(2), rs.getString(3), millis(rs.getTimestamp(4)), millis(rs.getTimestamp(5)), rs.getString(6)));
			}
		}
		return out;
	}

	public void addKey(int userId, String name, String publicKey, String fingerprint) throws SQLException {
		try (Connection c = db.connection(); PreparedStatement st = c.prepareStatement(
				"INSERT INTO user_ssh_keys (user_id, name, public_key, fingerprint) VALUES (?, ?, ?, ?)")) {
			st.setInt(1, userId);
			st.setString(2, name);
			st.setString(3, publicKey);
			st.setString(4, fingerprint);
			st.executeUpdate();
		}
	}

	public boolean deleteKey(int userId, int keyId) throws SQLException {
		try (Connection c = db.connection(); PreparedStatement st = c.prepareStatement("DELETE FROM user_ssh_keys WHERE id = ? AND user_id = ?")) {
			st.setInt(1, keyId);
			st.setInt(2, userId);
			return st.executeUpdate() > 0;
		}
	}

	public void touchKey(int keyId) throws SQLException {
		update("UPDATE user_ssh_keys SET last_used = now() WHERE id = ?", keyId);
	}

	// ── Audit log ──────────────────────────────────────────────────────────

	public void audit(String user, String action, String detail, String ip) throws SQLException {
		try (Connection c = db.connection(); PreparedStatement st = c.prepareStatement(
				"INSERT INTO audit_log (user_name, action, detail, ip) VALUES (?, ?, ?, ?)")) {
			st.setString(1, user);
			st.setString(2, action);
			st.setString(3, detail);
			st.setString(4, ip);
			st.executeUpdate();
		}
	}

	/** Newest first; before = the oldest id already shown, or 0. */
	public List<Map<String, Object>> auditLog(long before, int limit, String search) throws SQLException {
		List<Map<String, Object>> out = new ArrayList<>();
		String filter = search == null || search.isBlank() ? null : "%" + search.strip().toLowerCase() + "%";
		try (Connection c = db.connection(); PreparedStatement st = c.prepareStatement(
				"SELECT id, time, user_name, action, detail, ip FROM audit_log WHERE (? = 0 OR id < ?)"
						+ " AND (?::text IS NULL OR lower(coalesce(user_name, '') || ' ' || action || ' ' || coalesce(detail, '')) LIKE ?)"
						+ " ORDER BY id DESC LIMIT ?")) {
			st.setLong(1, before);
			st.setLong(2, before);
			st.setString(3, filter);
			st.setString(4, filter);
			st.setInt(5, limit);
			try (ResultSet rs = st.executeQuery()) {
				while (rs.next()) {
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("id", rs.getLong(1));
					row.put("time", millis(rs.getTimestamp(2)));
					row.put("user", rs.getString(3));
					row.put("action", rs.getString(4));
					row.put("detail", rs.getString(5));
					row.put("ip", rs.getString(6));
					out.add(row);
				}
			}
		}
		return out;
	}

	private void update(String sql, int id) throws SQLException {
		try (Connection c = db.connection(); PreparedStatement st = c.prepareStatement(sql)) {
			st.setInt(1, id);
			st.executeUpdate();
		}
	}

	private static long millis(Timestamp t) {
		return t == null ? 0 : t.getTime();
	}
}
