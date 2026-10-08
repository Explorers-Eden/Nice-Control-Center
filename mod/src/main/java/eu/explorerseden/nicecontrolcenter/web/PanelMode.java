package eu.explorerseden.nicecontrolcenter.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Set when the Nice Control Center Panel (Docker) runs this server. The panel starts Minecraft with
 * -Dncc.panel.port, -Dncc.panel.secret and -Dncc.panel.url; the dashboard then listens only inside the
 * container and accepts the panel's secret instead of its own login link. The panel shows it to its
 * users with their own accounts and permissions.
 */
public final class PanelMode {
	private static final String PORT = System.getProperty("ncc.panel.port", "");
	private static final String SECRET = System.getProperty("ncc.panel.secret", "");
	private static final String URL = System.getProperty("ncc.panel.url", "");

	private PanelMode() {
	}

	public static boolean active() {
		return !SECRET.isBlank() && port() > 0;
	}

	public static int port() {
		try {
			return Integer.parseInt(PORT.trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	public static boolean secretMatches(String value) {
		return value != null && active() && MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8), SECRET.getBytes(StandardCharsets.UTF_8));
	}

	/** The panel's address for links in chat, without a trailing slash; empty if the panel didn't say. */
	public static String url() {
		String url = URL.trim();
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}
}
