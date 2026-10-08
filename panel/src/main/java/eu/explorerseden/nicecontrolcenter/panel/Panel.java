package eu.explorerseden.nicecontrolcenter.panel;

import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.core.SystemMetrics;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Entry point of the Docker panel. For now it only serves its start page and a health check. */
public final class Panel {
	public static final String VERSION = readVersion();

	private Panel() {
	}

	public static void main(String[] args) {
		int port = Integer.parseInt(env("PANEL_PORT", "8080"));
		Javalin app = Javalin.create(config -> {
			config.startup.showJavalinBanner = false;
			config.staticFiles.add(files -> {
				files.hostedPath = "/";
				files.directory = "/panel/web";
				files.location = Location.CLASSPATH;
			});
			// Docker's HEALTHCHECK and reverse proxies poll this.
			config.routes.get("/api/health", ctx -> {
				SystemMetrics.Sample s = SystemMetrics.sample();
				Map<String, Object> out = new LinkedHashMap<>();
				out.put("ok", true);
				out.put("version", VERSION);
				out.put("heapUsed", s.heapUsed());
				out.put("heapMax", s.heapMax());
				ctx.contentType("application/json").result(Json.GSON.toJson(out));
			});
		});
		// `docker stop` sends SIGTERM; later this is where the Minecraft server gets stopped cleanly.
		Runtime.getRuntime().addShutdownHook(new Thread(app::stop, "panel-shutdown"));
		app.start(port);
		System.out.println("Nice Control Center Panel " + VERSION + " listening on port " + port);
	}

	static String env(String name, String fallback) {
		String value = System.getenv(name);
		return value == null || value.isBlank() ? fallback : value.trim();
	}

	private static String readVersion() {
		try (InputStream in = Panel.class.getResourceAsStream("/panel/version.txt")) {
			return in == null ? "dev" : new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
		} catch (IOException e) {
			return "dev";
		}
	}
}
