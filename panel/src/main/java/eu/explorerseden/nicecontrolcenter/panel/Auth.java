package eu.explorerseden.nicecontrolcenter.panel;

import com.password4j.Password;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Logins and sessions. Two kinds of accounts:
 * <ul>
 * <li>The env admin (PANEL_ADMIN_USER / PANEL_ADMIN_PASSWORD, or a generated password printed once):
 * allowed everything, sessions in memory, works even when the database is down.</li>
 * <li>Users in Postgres with roles. Passwords are Argon2id hashes; sessions are stored as SHA-256 of the
 * cookie and cached for a few seconds, so disabling a user takes effect almost at once.</li>
 * </ul>
 */
public final class Auth {
	public static final String COOKIE = "ncc_session";
	public static final int MIN_PASSWORD = 8;
	private static final long SESSION_MS = 7L * 24 * 60 * 60 * 1000;
	private static final long CACHE_MS = 15_000;
	private static final int MAX_FAILS = 10;
	private static final long FAIL_WINDOW_MS = 10 * 60_000;
	private static final int ITERATIONS = 310_000;

	/** userId is null for the env admin. */
	public record Session(Integer userId, String name, Set<String> permissions) {
		public boolean can(String permission) {
			return Permissions.has(permissions, permission);
		}

		public boolean envAdmin() {
			return userId == null;
		}
	}

	private record Cached(Session session, long until, long expires) {
	}

	private final SecureRandom random = new SecureRandom();
	private final String envUser;
	private final String envPassword;
	private final String envHash;
	private final Accounts accounts;
	private final Map<String, Long> envSessions = new ConcurrentHashMap<>();
	private final Map<String, Cached> cache = new ConcurrentHashMap<>();
	private final Map<String, Deque<Long>> fails = new HashMap<>();
	/** Checked against for unknown names, so the response time doesn't tell whether a user exists. */
	private final String dummyHash;

	public Auth(Path dataDir, Accounts accounts) throws IOException {
		this.accounts = accounts;
		envUser = Panel.env("PANEL_ADMIN_USER", "admin");
		dummyHash = accounts == null ? null : hashPassword(token(16));
		String password = System.getenv("PANEL_ADMIN_PASSWORD");
		if (password != null && !password.isBlank()) {
			envPassword = password;
			envHash = null;
			return;
		}
		envPassword = null;
		Path file = dataDir.resolve("admin.hash");
		if (Files.exists(file)) {
			envHash = Files.readString(file, StandardCharsets.UTF_8).strip();
			return;
		}
		String generated = token(12);
		envHash = pbkdf2Hash(generated);
		Files.createDirectories(dataDir);
		Files.writeString(file, envHash, StandardCharsets.UTF_8);
		System.out.println("================================================================");
		System.out.println(" No PANEL_ADMIN_PASSWORD set. Log in as '" + envUser + "' with: " + generated);
		System.out.println(" This is shown only once. Set PANEL_ADMIN_PASSWORD, or delete");
		System.out.println(" /data/panel/admin.hash to get a new one.");
		System.out.println("================================================================");
	}

	public String envUser() {
		return envUser;
	}

	// ── Login ──────────────────────────────────────────────────────────────

	public record LoginResult(String token, Session session, String error) {
	}

	public LoginResult login(String name, String password, String ip, String userAgent) {
		if (tooManyFails(ip)) return new LoginResult(null, null, "Too many failed attempts. Try again in a few minutes.");
		if (name == null || password == null || name.isBlank()) return failed(ip);
		if (name.strip().equalsIgnoreCase(envUser)) {
			if (!checkEnvPassword(password)) return failed(ip);
			String token = token(32);
			envSessions.put(token, System.currentTimeMillis() + SESSION_MS);
			clearFails(ip);
			return new LoginResult(token, envSession(), null);
		}
		if (accounts == null) return failed(ip);
		try {
			Accounts.Login login = accounts.findLogin(name.strip());
			if (login == null) {
				checkPassword(password, dummyHash);
				return failed(ip);
			}
			if (!checkPassword(password, login.passwordHash())) return failed(ip);
			if (login.disabled()) return new LoginResult(null, null, "This account is disabled.");
			String token = token(32);
			accounts.createSession(sha256(token), login.id(), System.currentTimeMillis() + SESSION_MS, ip, userAgent);
			accounts.touchLogin(login.id());
			clearFails(ip);
			return new LoginResult(token, new Session(login.id(), login.name(), accounts.permissions(login.id())), null);
		} catch (SQLException e) {
			return new LoginResult(null, null, "The database isn't reachable, so only " + envUser + " can log in right now.");
		}
	}

	private LoginResult failed(String ip) {
		synchronized (fails) {
			fails.computeIfAbsent(ip, k -> new ArrayDeque<>()).addLast(System.currentTimeMillis());
		}
		return new LoginResult(null, null, "Wrong name or password");
	}

	private void clearFails(String ip) {
		synchronized (fails) {
			fails.remove(ip);
		}
	}

	private boolean tooManyFails(String ip) {
		synchronized (fails) {
			Deque<Long> recent = fails.get(ip);
			if (recent == null) return false;
			long cutoff = System.currentTimeMillis() - FAIL_WINDOW_MS;
			while (!recent.isEmpty() && recent.peekFirst() < cutoff) recent.removeFirst();
			return recent.size() >= MAX_FAILS;
		}
	}

	// ── Without a web session (SFTP) ───────────────────────────────────────

	/** Checks name and password like a login, with the same failed-attempt limit, but creates no session. */
	public Session verify(String name, String password, String ip) {
		if (tooManyFails(ip) || name == null || password == null) return null;
		if (name.strip().equalsIgnoreCase(envUser)) {
			if (checkEnvPassword(password)) return envSession();
			failed(ip);
			return null;
		}
		if (accounts == null) return null;
		try {
			Accounts.Login login = accounts.findLogin(name.strip());
			if (login == null) {
				checkPassword(password, dummyHash);
				failed(ip);
				return null;
			}
			if (login.disabled() || !checkPassword(password, login.passwordHash())) {
				failed(ip);
				return null;
			}
			clearFails(ip);
			return new Session(login.id(), login.name(), accounts.permissions(login.id()));
		} catch (SQLException e) {
			return null;
		}
	}

	/** An enabled database user by name, with permissions (for SSH key logins). */
	public Session user(String name) {
		if (accounts == null || name == null) return null;
		try {
			Accounts.Login login = accounts.findLogin(name.strip());
			if (login == null || login.disabled()) return null;
			return new Session(login.id(), login.name(), accounts.permissions(login.id()));
		} catch (SQLException e) {
			return null;
		}
	}

	public Accounts accounts() {
		return accounts;
	}

	// ── Sessions ───────────────────────────────────────────────────────────

	public Session session(String token) {
		if (token == null || token.isEmpty()) return null;
		long now = System.currentTimeMillis();
		Long envExpires = envSessions.get(token);
		if (envExpires != null) {
			if (envExpires > now) return envSession();
			envSessions.remove(token);
			return null;
		}
		if (accounts == null) return null;
		String hash = sha256(token);
		Cached cached = cache.get(hash);
		if (cached != null && cached.until() > now && cached.expires() > now) return cached.session();
		try {
			Accounts.SessionRow row = accounts.session(hash);
			if (row == null || row.disabled() || row.expiresAt() < now) {
				cache.remove(hash);
				return null;
			}
			Session session = new Session(row.userId(), row.name(), accounts.permissions(row.userId()));
			cache.put(hash, new Cached(session, now + CACHE_MS, row.expiresAt()));
			return session;
		} catch (SQLException e) {
			// Database down: keep known sessions working until the cache entry's session expires.
			return cached != null && cached.expires() > now ? cached.session() : null;
		}
	}

	public void logout(String token) {
		if (token == null) return;
		envSessions.remove(token);
		if (accounts == null) return;
		String hash = sha256(token);
		cache.remove(hash);
		try {
			accounts.deleteSession(hash);
		} catch (SQLException e) {
			// Expires on its own.
		}
	}

	/** After a password change, disabling, deleting or a role change: log the user out everywhere. */
	public void forget(int userId) {
		cache.values().removeIf(c -> c.session().userId() != null && c.session().userId() == userId);
		try {
			accounts.deleteSessionsOf(userId);
		} catch (SQLException e) {
			// The cache is gone already; the rows expire on their own.
		}
	}

	/** After a role's permissions change, every cached session reloads its permissions. */
	public void forgetPermissions() {
		cache.clear();
	}

	private Session envSession() {
		return new Session(null, envUser, Set.of(Permissions.ALL));
	}

	// ── Passwords ──────────────────────────────────────────────────────────

	public static String hashPassword(String password) {
		return Password.hash(password).addRandomSalt().withArgon2().getResult();
	}

	public static boolean checkPassword(String password, String hash) {
		return Password.check(password, hash).withArgon2();
	}

	private boolean checkEnvPassword(String password) {
		if (envHash == null) return equal(password, envPassword);
		String[] parts = envHash.split(":");
		if (parts.length != 3) return false;
		byte[] salt = Base64.getDecoder().decode(parts[1]);
		return MessageDigest.isEqual(Base64.getDecoder().decode(parts[2]), pbkdf2(password, salt, Integer.parseInt(parts[0])));
	}

	private String pbkdf2Hash(String password) {
		byte[] salt = new byte[16];
		random.nextBytes(salt);
		return ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(pbkdf2(password, salt, ITERATIONS));
	}

	private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
		try {
			return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, 256)).getEncoded();
		} catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
			throw new IllegalStateException(e);
		}
	}

	private static boolean equal(String a, String b) {
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}

	static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private String token(int bytes) {
		byte[] b = new byte[bytes];
		random.nextBytes(b);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
	}
}
