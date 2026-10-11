package eu.explorerseden.nicecontrolcenter.core;

import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Generates every chunk inside a square or circle ahead of time, Chunky-style: in a spiral from the
 * center, a few at a time on Minecraft's own world-generation threads. The job runs on its own thread,
 * not in the server tick: it only asks for chunks and is woken when one is done, and it backs off when a
 * tick takes more than ~25 ms so players don't notice. Optionally skips an inner area (to regenerate only
 * the outer wilds after a trim). Further jobs can be queued. The empty-server pause is held off while it
 * runs (DedicatedServerPauseMixin), so it keeps going without players.
 */
public final class Pregen {
	private static final int MAX_IN_FLIGHT = 24;
	/**
	 * Holds each chunk until it's fully generated. Vanilla's own request ticket lasts one tick, so busy
	 * chunks were sometimes unloaded half-way and failed. Loads only (no ticking), never saved.
	 */
	private static TicketType ticket;
	private static volatile Job job;
	private static final ConcurrentLinkedQueue<Job> queue = new ConcurrentLinkedQueue<>();
	private static volatile Map<String, Object> last;

	private Pregen() {
	}

	private static final class Job {
		final MinecraftServer server;
		final ServerLevel level;
		final String dimension;
		final int centerX;
		final int centerZ;
		final int radius;
		/** Chunks inside this radius are left alone; 0 = none. */
		final int skipRadius;
		final boolean circle;
		volatile long total = -1;
		volatile long started;
		// Square spiral over chunk coordinates around the center chunk; only touched by the job thread.
		int ring;
		int step;
		// Updated from world-generation and server threads too.
		final AtomicInteger inFlight = new AtomicInteger();
		final AtomicLong done = new AtomicLong();
		final AtomicLong skipped = new AtomicLong();
		volatile boolean cancelled;
		volatile String stopReason;
		volatile Thread thread;

		Job(MinecraftServer server, ServerLevel level, String dimension, int centerX, int centerZ, int radius, int skipRadius, boolean circle) {
			this.server = server;
			this.level = level;
			this.dimension = dimension;
			this.centerX = centerX;
			this.centerZ = centerZ;
			this.radius = radius;
			this.skipRadius = skipRadius;
			this.circle = circle;
		}

		boolean within(int cx, int cz, int r) {
			double x = cx * 16 + 8 - centerX;
			double z = cz * 16 + 8 - centerZ;
			return circle ? x * x + z * z <= (double) r * r : Math.abs(x) <= r && Math.abs(z) <= r;
		}

		boolean inside(int cx, int cz) {
			return within(cx, cz, radius) && (skipRadius <= 0 || !within(cx, cz, skipRadius));
		}

		long count() {
			long count = 0;
			int rc = radius / 16 + 1;
			for (int dz = -rc; dz <= rc && !cancelled; dz++) for (int dx = -rc; dx <= rc; dx++) if (inside((centerX >> 4) + dx, (centerZ >> 4) + dz)) count++;
			return count;
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

		/** The job thread: requests chunks while there's room, sleeps until one finishes otherwise. */
		void run() {
			started = System.currentTimeMillis();
			total = count();
			NiceControlCenter.LOGGER.info("Pregenerating {} chunks in {} around {}, {} (radius {}{}, {})", total, dimension, centerX, centerZ, radius,
					skipRadius > 0 ? ", outside " + skipRadius : "", circle ? "circle" : "square");
			boolean queueEmpty = false;
			while (!cancelled) {
				if (queueEmpty) {
					if (inFlight.get() == 0) break;
					LockSupport.parkNanos(50_000_000L);
					continue;
				}
				// Room left in the tick: back off when the server is busy.
				float mspt = server.getCurrentSmoothedTickTime();
				int budget = mspt > 45 ? 0 : mspt > 35 ? 2 : mspt > 25 ? 6 : MAX_IN_FLIGHT;
				if (inFlight.get() >= budget) {
					// Woken early when a chunk finishes; the timeout re-reads the tick time when budget is 0.
					LockSupport.parkNanos(budget == 0 ? 100_000_000L : 20_000_000L);
					continue;
				}
				int[] c = next();
				if (c == null) {
					queueEmpty = true;
					continue;
				}
				request(c[0], c[1]);
			}
			// Wait for what's still generating, so the next job (or a stop) starts from a quiet server.
			long until = System.currentTimeMillis() + 30_000;
			while (inFlight.get() > 0 && System.currentTimeMillis() < until) LockSupport.parkNanos(50_000_000L);
			finished(this, cancelled ? stopReason : "finished");
		}

		/**
		 * Queued for the server thread, which only places the ticket (microseconds); the generation runs
		 * on Minecraft's worker threads and the ticket goes again when the chunk is done.
		 */
		private void request(int cx, int cz) {
			inFlight.incrementAndGet();
			ChunkPos pos = new ChunkPos(cx, cz);
			server.execute(() -> {
				try {
					level.getChunkSource().addTicketAndLoadWithRadius(ticket, pos, 0).whenComplete((result, error) -> {
						server.execute(() -> level.getChunkSource().removeTicketWithRadius(ticket, pos, 0));
						if (error == null && !(result instanceof ChunkResult<?> r && !r.isSuccess())) done.incrementAndGet();
						else skipped.incrementAndGet();
						inFlight.decrementAndGet();
						LockSupport.unpark(thread);
					});
				} catch (RuntimeException e) {
					skipped.incrementAndGet();
					inFlight.decrementAndGet();
					LockSupport.unpark(thread);
				}
			});
		}
	}

	/** At mod start, while registries are still open. */
	public static void register() {
		ticket = Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.fromNamespaceAndPath(NiceControlCenter.MOD_ID, "pregen"),
				new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING));
	}

	/**
	 * Returns an error, or null when it started (or was queued). skipRadius 0 = generate everything in the
	 * radius. Any thread.
	 */
	public static synchronized String start(MinecraftServer server, String dimension, int x, int z, int radius, int skipRadius, boolean circle, boolean enqueue) {
		if (job != null && !enqueue) return "A pregeneration is already running.";
		if (radius < 16 || radius > 100_000) return "The radius must be between 16 and 100,000 blocks.";
		if (skipRadius < 0 || skipRadius >= radius) skipRadius = 0;
		Identifier id = Identifier.tryParse(dimension == null ? "" : dimension);
		ServerLevel level = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
		if (level == null) return "Unknown dimension " + dimension + ".";
		Job j = new Job(server, level, id.toString(), x, z, radius, skipRadius, circle);
		if (job != null) {
			queue.add(j);
			NiceControlCenter.LOGGER.info("Pregeneration of {} queued ({} waiting)", id, queue.size());
			return null;
		}
		launch(j);
		return null;
	}

	private static void launch(Job j) {
		job = j;
		Thread t = new Thread(j::run, "Nice Control Center pregen");
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		j.thread = t;
		t.start();
	}

	private static synchronized void finished(Job j, String reason) {
		j.stopReason = reason;
		last = status(j);
		NiceControlCenter.LOGGER.info("Pregeneration of {} {}: {} of {} chunks", j.dimension, reason, j.done.get() + j.skipped.get(), j.total);
		if (job != j) return;
		job = null;
		Job next = "finished".equals(reason) ? queue.poll() : null;
		if (next != null) launch(next);
	}

	/** Stops the running job and drops the queue. Any thread. */
	public static synchronized void stop(MinecraftServer server, String reason) {
		queue.clear();
		Job j = job;
		if (j == null) return;
		j.stopReason = reason;
		j.cancelled = true;
		LockSupport.unpark(j.thread);
	}

	/** Read by DedicatedServerPauseMixin on every tick. */
	public static boolean active() {
		return job != null;
	}

	public static Map<String, Object> status() {
		Job j = job;
		Map<String, Object> out;
		if (j != null && j.stopReason == null) {
			out = status(j);
		} else {
			// A stopped job winding down already counts as the last run.
			out = new LinkedHashMap<>();
			out.put("running", false);
			Map<String, Object> previous = j != null ? status(j) : last;
			if (previous != null) out.put("last", previous);
		}
		List<String> waiting = new ArrayList<>();
		for (Job q : queue) waiting.add(q.dimension);
		out.put("queued", waiting);
		return out;
	}

	private static Map<String, Object> status(Job j) {
		Map<String, Object> out = new LinkedHashMap<>();
		long seconds = Math.max(1, (System.currentTimeMillis() - (j.started == 0 ? System.currentTimeMillis() : j.started)) / 1000);
		long done = j.done.get();
		long skipped = j.skipped.get();
		long total = Math.max(0, j.total);
		double rate = (done + skipped) / (double) seconds;
		out.put("running", job == j && j.stopReason == null);
		out.put("counting", j.total < 0);
		out.put("dimension", j.dimension);
		out.put("x", j.centerX);
		out.put("z", j.centerZ);
		out.put("radius", j.radius);
		out.put("skipRadius", j.skipRadius);
		out.put("shape", j.circle ? "circle" : "square");
		out.put("done", done + skipped);
		out.put("failed", skipped);
		out.put("total", total);
		out.put("perSecond", Math.round(rate * 10) / 10.0);
		out.put("etaSeconds", rate > 0 ? Math.round((total - done - skipped) / rate) : -1);
		out.put("seconds", seconds);
		if (j.stopReason != null) out.put("result", j.stopReason);
		return out;
	}
}
