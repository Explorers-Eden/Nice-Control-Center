package eu.explorerseden.nicecontrolcenter.record;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Stream;

import net.minecraft.server.MinecraftServer;

import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.data.Breakdown;
import eu.explorerseden.nicecontrolcenter.data.History;
import eu.explorerseden.nicecontrolcenter.data.Point;

/**
 * Long recordings for a downloadable report. Started and stopped by hand; the live monitor runs
 * all the time anyway.
 *
 * <p>While recording, every second's core metrics and a merged breakdown per minute are appended
 * to an NDJSON file, so even day-long recordings use little memory. Stopping turns that file into a
 * self-contained HTML report.
 */
public final class Recorder {
	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "Nice Control Center Recorder");
		thread.setDaemon(true);
		return thread;
	});

	public record Status(boolean active, long startedAt, long endsAt, String startedBy, int seconds, boolean automatic, String reason) {
	}

	/** A finished report; automatic ones were started by a lag episode. */
	public record ReportFile(String name, long size, long modified, boolean automatic) {
	}

	private static final String AUTO_PREFIX = "lag-";

	private static Recording current;
	private static Consumer<String> announcer = message -> { };

	private Recorder() {
	}

	private static final class Recording {
		long startedAt = System.currentTimeMillis();
		long endsAt;
		final String startedBy;
		final Path file;
		final BufferedWriter writer;
		final boolean automatic;
		final String reason;
		Breakdown minute = new Breakdown();
		int seconds;

		Recording(long endsAt, String startedBy, Path file, boolean automatic, String reason) throws IOException {
			this.endsAt = endsAt;
			this.startedBy = startedBy;
			this.file = file;
			this.automatic = automatic;
			this.reason = reason;
			this.writer = Files.newBufferedWriter(file);
		}
	}

	/** Where finished-report messages go (chat for ops, plus the log). */
	public static void announcer(Consumer<String> consumer) {
		announcer = consumer;
	}

	public static Path baseDir(MinecraftServer server) {
		return server.getServerDirectory().resolve("nicecontrolcenter");
	}

	public static Path reportsDir(MinecraftServer server) {
		return baseDir(server).resolve("reports");
	}

	public static synchronized Status status() {
		Recording r = current;
		return r == null ? new Status(false, 0, 0, null, 0, false, null)
				: new Status(true, r.startedAt, r.endsAt, r.startedBy, r.seconds, r.automatic, r.reason);
	}

	/**
	 * Starts an automatic recording for a lag episode, beginning with the minutes before it from
	 * {@link History}. Does nothing if any recording already runs or today's limit is reached.
	 */
	public static synchronized boolean startAutomatic(MinecraftServer server, String reason, int maxMinutes, int perDay, int preRollMinutes) {
		if (current != null || automaticToday(server) >= perDay) {
			return false;
		}
		try {
			Path dir = baseDir(server).resolve("recordings");
			Files.createDirectories(dir);
			Path file = dir.resolve(AUTO_PREFIX + LocalDateTime.now().format(FILE_TIME) + ".ndjson");
			Recording r = new Recording(System.currentTimeMillis() + maxMinutes * 60_000L, "automatic", file, true, reason);
			History.PreRoll preRoll = History.preRoll(preRollMinutes);
			if (!preRoll.points().isEmpty()) {
				r.startedAt = preRoll.points().get(0).t();
			}
			List<String> lines = new ArrayList<>();
			for (Point point : preRoll.points()) {
				lines.add(Json.GSON.toJson(new Line(point, null)));
			}
			for (Breakdown minute : preRoll.minutes()) {
				lines.add(Json.GSON.toJson(new Line(null, minute)));
			}
			r.seconds = preRoll.points().size();
			current = r;
			IO.execute(() -> {
				try {
					for (String line : lines) {
						r.writer.write(line);
						r.writer.newLine();
					}
				} catch (IOException e) {
					NiceControlCenter.LOGGER.warn("Could not write recording", e);
				}
			});
			return true;
		} catch (IOException e) {
			NiceControlCenter.LOGGER.error("Could not start automatic recording", e);
			return false;
		}
	}

	/** Lets a running automatic recording end the given time from now (after the lag recovered). */
	public static synchronized void finishAutomaticIn(long millis) {
		if (current != null && current.automatic) {
			current.endsAt = Math.min(current.endsAt, System.currentTimeMillis() + millis);
		}
	}

	private static int automaticToday(MinecraftServer server) {
		String today = "nice-control-center-" + AUTO_PREFIX + LocalDate.now() + "_";
		return (int) reports(server).stream().filter(r -> r.name().startsWith(today)).count();
	}

	/** Starts recording; returns an error message, or null on success. */
	public static synchronized String start(MinecraftServer server, int minutes, String startedBy, int maxHours) {
		if (current != null && current.automatic) {
			// A manual recording replaces an automatic one; the automatic part is saved as its own report.
			stop(server);
		}
		if (current != null) {
			return "A recording is already running. Stop it first with /ncc record stop.";
		}
		int capped = minutes <= 0 ? maxHours * 60 : Math.min(minutes, maxHours * 60);
		try {
			Path dir = baseDir(server).resolve("recordings");
			Files.createDirectories(dir);
			Path file = dir.resolve("recording-" + LocalDateTime.now().format(FILE_TIME) + ".ndjson");
			current = new Recording(System.currentTimeMillis() + capped * 60_000L, startedBy, file, false, null);
			return null;
		} catch (IOException e) {
			NiceControlCenter.LOGGER.error("Could not start recording", e);
			return "Could not create the recording file: " + e.getMessage();
		}
	}

	/** Called every second on the server thread. */
	public static void onSecond(MinecraftServer server, Breakdown second, Point point) {
		Recording r;
		synchronized (Recorder.class) {
			r = current;
			if (r == null) {
				return;
			}
			r.seconds++;
			r.minute.merge(second);
			String pointLine = Json.GSON.toJson(new Line(point, null));
			Breakdown full = null;
			if (r.minute.seconds >= 60) {
				full = r.minute;
				r.minute = new Breakdown();
			}
			Breakdown minute = full;
			IO.execute(() -> write(r, pointLine, minute));
		}
		if (System.currentTimeMillis() >= r.endsAt) {
			stop(server);
		}
	}

	/** One NDJSON line: either a per-second point or a per-minute breakdown. */
	record Line(Point p, Breakdown m) {
	}

	private static void write(Recording r, String pointLine, Breakdown minute) {
		try {
			r.writer.write(pointLine);
			r.writer.newLine();
			if (minute != null) {
				r.writer.write(Json.GSON.toJson(new Line(null, minute)));
				r.writer.newLine();
			}
		} catch (IOException e) {
			NiceControlCenter.LOGGER.warn("Could not write recording", e);
		}
	}

	/** Stops the recording and builds the report in the background. Null when nothing was recording. */
	public static synchronized CompletableFuture<Path> stop(MinecraftServer server) {
		Recording r = current;
		if (r == null) {
			return null;
		}
		current = null;
		Breakdown rest = r.minute.seconds > 0 ? r.minute : null;
		Path reports = reportsDir(server);
		String serverName = server.getMotd();
		String version = server.getServerVersion();
		CompletableFuture<Path> result = new CompletableFuture<>();
		IO.execute(() -> {
			try {
				if (rest != null) {
					r.writer.write(Json.GSON.toJson(new Line(null, rest)));
					r.writer.newLine();
				}
				r.writer.close();
				Files.createDirectories(reports);
				Path report = reports.resolve("nice-control-center-" + r.file.getFileName().toString().replace("recording-", "").replace(".ndjson", ".html"));
				ReportWriter.write(r.file, report, new ReportWriter.Meta(serverName, version, r.startedBy, r.startedAt, System.currentTimeMillis(), r.reason));
				announcer.accept((r.automatic ? "Lag report saved: " : "Recording saved: ") + report.getFileName());
				result.complete(report);
			} catch (IOException | RuntimeException e) {
				NiceControlCenter.LOGGER.error("Could not write report", e);
				announcer.accept("Could not write the report: " + e.getMessage());
				result.completeExceptionally(e);
			}
		});
		return result;
	}

	public static List<ReportFile> reports(MinecraftServer server) {
		List<ReportFile> files = new ArrayList<>();
		Path dir = reportsDir(server);
		if (!Files.isDirectory(dir)) {
			return files;
		}
		try (Stream<Path> stream = Files.list(dir)) {
			stream.filter(p -> p.getFileName().toString().endsWith(".html")).forEach(p -> {
				try {
					String name = p.getFileName().toString();
					files.add(new ReportFile(name, Files.size(p), Files.getLastModifiedTime(p).toMillis(), name.startsWith("nice-control-center-" + AUTO_PREFIX)));
				} catch (IOException e) {
					// Deleted while listing.
				}
			});
		} catch (IOException e) {
			NiceControlCenter.LOGGER.warn("Could not list reports", e);
		}
		files.sort(Comparator.comparingLong(ReportFile::modified).reversed());
		return files;
	}
}
