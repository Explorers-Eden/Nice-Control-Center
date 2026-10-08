package eu.explorerseden.nicecontrolcenter.core;

import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates every chunk inside a square or circle ahead of time, Chunky-style: in a spiral from the
 * center, a few at a time on Minecraft's own world-generation threads, and only as fast as the server
 * has room for (it backs off when a tick takes more than ~40 ms). The empty-server pause is switched
 * off while it runs, so it keeps going without players.
 */
public final class Pregen {
	private static final int MAX_IN_FLIGHT = 24;
	private static volatile Job job;
	private static volatile Map<String, Object> last;

	private Pregen() {
	}

	private static final class Job {
		final ServerLevel level;
		final String dimension;
		final int centerX;
		final int centerZ;
		final int radius;
		final boolean circle;
		final long total;
		final long started = System.currentTimeMillis();
		final int previousPause;
		// Square spiral over chunk coordinates around the center chunk.
		int ring;
		int step;
		// Updated from world-generation threads too.
		final AtomicInteger inFlight = new AtomicInteger();
		final AtomicLong done = new AtomicLong();
		final AtomicLong skipped = new AtomicLong();
		boolean finishedQueue;
		String stopReason;

		Job(ServerLevel level, String dimension, int centerX, int centerZ, int radius, boolean circle, int previousPause) {
			this.level = level;
			this.dimension = dimension;
			this.centerX = centerX;
			this.centerZ = centerZ;
			this.radius = radius;
			this.circle = circle;
			this.previousPause = previousPause;
			long count = 0;
			int rc = radius / 16 + 1;
			for (int dz = -rc; dz <= rc; dz++) for (int dx = -rc; dx <= rc; dx++) if (inside((centerX >> 4) + dx, (centerZ >> 4) + dz)) count++;
			this.total = count;
		}

		boolean inside(int cx, int cz) {
			double x = cx * 16 + 8 - centerX;
			double z = cz * 16 + 8 - centerZ;
			return circle ? x * x + z * z <= (double) radius * radius : Math.abs(x) <= radius && Math.abs(z) <= radius;
		}

		/** Next chunk in the spiral, or null when the spiral left the area. */
		int[] next() {
			int rc = radius / 16 + 1;
			while (ring <= rc) {
				int side = ring * 2;
				int perimeter = ring == 0 ? 1 : side * 4;
				if (step >= perimeter) {
					ring++;
					step = 0;
					continue;
				}
				int s = step++;
				int dx;
				int dz;
				if (ring == 0) {
					dx = 0;
					dz = 0;
				} else if (s < side) {
					dx = -ring + s;
					dz = -ring;
				} else if (s < 2 * side) {
					dx = ring;
					dz = -ring + (s - side);
				} else if (s < 3 * side) {
					dx = ring - (s - 2 * side);
					dz = ring;
				} else {
					dx = -ring;
					dz = ring - (s - 3 * side);
				}
				int cx = (centerX >> 4) + dx;
				int cz = (centerZ >> 4) + dz;
				if (inside(cx, cz)) return new int[] { cx, cz };
			}
			return null;
		}
	}

	/** Returns an error, or null when it started. Server thread. */
	public static String start(MinecraftServer server, String dimension, int x, int z, int radius, boolean circle) {
		if (job != null) return "A pregeneration is already running.";
		if (radius < 16 || radius > 100_000) return "The radius must be between 16 and 100,000 blocks.";
		Identifier id = Identifier.tryParse(dimension == null ? "" : dimension);
		ServerLevel level = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
		if (level == null) return "Unknown dimension " + dimension + ".";
		int previousPause = 0;
		if (server instanceof DedicatedServer dedicated) {
			previousPause = dedicated.pauseWhenEmptySeconds();
			dedicated.setPauseWhenEmptySeconds(0);
		}
		job = new Job(level, id.toString(), x, z, radius, circle, previousPause);
		NiceControlCenter.LOGGER.info("Pregenerating {} chunks in {} around {}, {} (radius {}, {})", job.total, id, x, z, radius, circle ? "circle" : "square");
		return null;
	}

	public static void stop(MinecraftServer server, String reason) {
		Job j = job;
		if (j == null) return;
		finish(server, j, reason);
	}

	private static void finish(MinecraftServer server, Job j, String reason) {
		j.stopReason = reason;
		if (server instanceof DedicatedServer dedicated) dedicated.setPauseWhenEmptySeconds(j.previousPause);
		last = status(j);
		job = null;
		NiceControlCenter.LOGGER.info("Pregeneration {}: {} of {} chunks", reason, j.done.get(), j.total);
	}

	/** Every server tick. */
	public static void tick(MinecraftServer server) {
		Job j = job;
		if (j == null) return;
		if (j.finishedQueue && j.inFlight.get() == 0) {
			finish(server, j, "finished");
			return;
		}
		// Room left in this tick: back off when the server is busy, so players never notice.
		float mspt = server.getCurrentSmoothedTickTime();
		int budget = mspt > 45 ? 0 : mspt > 35 ? 2 : mspt > 25 ? 6 : MAX_IN_FLIGHT;
		while (j.inFlight.get() < budget && !j.finishedQueue) {
			int[] c = j.next();
			if (c == null) {
				j.finishedQueue = true;
				break;
			}
			j.inFlight.incrementAndGet();
			j.level.getChunkSource().getChunkFuture(c[0], c[1], ChunkStatus.FULL, true).whenComplete((result, error) -> {
				j.inFlight.decrementAndGet();
				if (error == null && result != null && result.isSuccess()) j.done.incrementAndGet();
				else j.skipped.incrementAndGet();
			});
		}
	}

	public static Map<String, Object> status() {
		Job j = job;
		if (j != null) return status(j);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("running", false);
		if (last != null) out.put("last", last);
		return out;
	}

	private static Map<String, Object> status(Job j) {
		Map<String, Object> out = new LinkedHashMap<>();
		long seconds = Math.max(1, (System.currentTimeMillis() - j.started) / 1000);
		long done = j.done.get();
		long skipped = j.skipped.get();
		double rate = (done + skipped) / (double) seconds;
		out.put("running", job == j && j.stopReason == null);
		out.put("dimension", j.dimension);
		out.put("x", j.centerX);
		out.put("z", j.centerZ);
		out.put("radius", j.radius);
		out.put("shape", j.circle ? "circle" : "square");
		out.put("done", done + skipped);
		out.put("failed", skipped);
		out.put("total", j.total);
		out.put("perSecond", Math.round(rate * 10) / 10.0);
		out.put("etaSeconds", rate > 0 ? Math.round((j.total - done - skipped) / rate) : -1);
		out.put("seconds", seconds);
		if (j.stopReason != null) out.put("result", j.stopReason);
		return out;
	}
}
