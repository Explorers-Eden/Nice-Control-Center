package eu.explorerseden.nicecontrolcenter.panel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * One admin account until user management arrives: the name and password come from PANEL_ADMIN_USER
 * and PANEL_ADMIN_PASSWORD. Without a password, one is generated on first start, printed to the
 * container log once and kept as a hash in /data/panel/admin.hash. Sessions live in memory.
 */
public final class Auth {
	public static final String COOKIE = "ncc_session";
	private static final long SESSION_MS = 7L * 24 * 60 * 60 * 1000;
	private static final int MAX_FAILS = 10;
	private static final long FAIL_WINDOW_MS = 10 * 60_000;
	private static final int ITERATIONS = 310_000;

	private final SecureRandom random = new SecureRandom();
	private final String user;
	private final String envPassword;
	private final String storedHash;
	private final Map<String, Session> sessions = new ConcurrentHashMap<>();
	private final Map<String, Deque<Long>> fails = new HashMap<>();

	public record Session(String user, long expires) {
	}

	public Auth(Path dataDir) throws IOException {
		user = Panel.env("PANEL_ADMIN_USER", "admin");
		envPassword = System.getenv("PANEL_ADMIN_PASSWORD");
		if (envPassword != null && !envPassword.isBlank()) {
			storedHash = null;
			return;
		}
		Path file = dataDir.resolve("admin.hash");
		if (Files.exists(file)) {
			storedHash = Files.readString(file, StandardCharsets.UTF_8).strip();
			return;
		}
		String generated = token(12);
		storedHash = hash(generated);
		Files.createDirectories(dataDir);
		Files.writeString(file, storedHash, StandardCharsets.UTF_8);
		System.out.println("================================================================");
		System.out.println(" No PANEL_ADMIN_PASSWORD set. Log in as '" + user + "' with: " + generated);
		System.out.println(" This is shown only once. Set PANEL_ADMIN_PASSWORD, or delete");
		System.out.println(" /data/panel/admin.hash to get a new one.");
		System.out.println("================================================================");
	}

	/** Returns a session token, or null. Refuses after too many failed attempts from one address. */
	public String login(String name, String password, String ip) {
		synchronized (fails) {
			Deque<Long> recent = fails.computeIfAbsent(ip, k -> new ArrayDeque<>());
			long now = System.currentTimeMillis();
			while (!recent.isEmpty() && recent.peekFirst() < now - FAIL_WINDOW_MS) recent.removeFirst();
			if (recent.size() >= MAX_FAILS) return null;
			boolean ok = name != null && password != null && equal(name, user) && checkPassword(password);
			if (!ok) {
				recent.addLast(now);
				return null;
			}
			recent.clear();
		}
		String token = token(32);
		sessions.put(token, new Session(user, System.currentTimeMillis() + SESSION_MS));
		return token;
	}

	public boolean tooManyFails(String ip) {
		synchronized (fails) {
			Deque<Long> recent = fails.get(ip);
			long cutoff = System.currentTimeMillis() - FAIL_WINDOW_MS;
			return recent != null && recent.stream().filter(t -> t >= cutoff).count() >= MAX_FAILS;
		}
	}

	public Session session(String token) {
		if (token == null) return null;
		Session s = sessions.get(token);
		if (s == null) return null;
		if (s.expires() < System.currentTimeMillis()) {
			sessions.remove(token);
			return null;
		}
		return s;
	}

	public void logout(String token) {
		if (token != null) sessions.remove(token);
	}

	private boolean checkPassword(String password) {
		if (storedHash == null) return equal(password, envPassword);
		String[] parts = storedHash.split(":");
		if (parts.length != 3) return false;
		byte[] salt = Base64.getDecoder().decode(parts[1]);
		return MessageDigest.isEqual(Base64.getDecoder().decode(parts[2]), pbkdf2(password, salt, Integer.parseInt(parts[0])));
	}

	private String hash(String password) {
		byte[] salt = new byte[16];
		random.nextBytes(salt);
		return ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(pbkdf2(password, salt, ITERATIONS));
	}

	private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
		try {
			return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, 256)).getEncoded();
		} catch (java.security.NoSuchAlgorithmException | InvalidKeySpecException e) {
			throw new IllegalStateException(e);
		}
	}

	private static boolean equal(String a, String b) {
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}

	private String token(int bytes) {
		byte[] b = new byte[bytes];
		random.nextBytes(b);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
	}
}
