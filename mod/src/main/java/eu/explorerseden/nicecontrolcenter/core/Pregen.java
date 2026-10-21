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
 * runs (DedicatedServerPauseMixin), so it keeps going without players. While players are online it can
 * slow down or pause. The running job and the queue are kept in config/nicecontrolcenter-pregen.json, so
 * a restart carries on where it was (at the start of the ring it was working on).
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
	/** Slow-mode cap on chunks in flight while players are online. */
	private static final int SLOW_IN_FLIGHT = 4;
	private static volatile boolean shuttingDown;

	/** What config/nicecontrolcenter-pregen.json holds per job. */
	private static final class Saved {
		String dimension;
		int x;
		int z;
		int radius;
		int skipRadius;
		String shape;
		String players;
		int ring;
		long done;
		long failed;
		/** Also Distant Horizons LODs after the chunks; "lods" when the job was already at that step. */
		boolean lods;
		String phase;
	}

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
		/** While players are online: "full", "slow" or "pause". */
		final String players;
		/** After the chunks, Distant Horizons' own LOD pregeneration over the same area (only set when DH is installed). */
		final boolean lods;
		/** "chunks", then "lods"; and what DH last said about its progress. */
		volatile String phase = "chunks";
		volatile String lodStatus;
		volatile long total = -1;
		volatile boolean pausedForPlayers;
		// Where a restart picks up: the ring being worked on and what was done before it.
		volatile int ringStart;
		volatile long doneAtRing;
		volatile long failedAtRing;
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

		Job(MinecraftServer server, ServerLevel level, String dimension, int centerX, int centerZ, int radius, int skipRadius, boolean circle, String players,
				boolean lods) {
			this.players = players;
			this.lods = lods;
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
					ringStart = ring;
					doneAtRing = done.get();
					failedAtRing = skipped.get();
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
			if ("lods".equals(phase)) {
				// Resumed after a restart while DH was generating LODs: the chunks are done.
				total = 0;
				lodPhase();
				finished(this, cancelled ? stopReason : lodResult);
				return;
			}
			total = count();
			NiceControlCenter.LOGGER.info("Pregenerating {} chunks in {} around {}, {} (radius {}{}, {})", total, dimension, centerX, centerZ, radius,
					skipRadius > 0 ? ", outside " + skipRadius : "", circle ? "circle" : "square");
			boolean queueEmpty = false;
			long saveAt = System.currentTimeMillis() + 30_000;
			while (!cancelled) {
				if (System.currentTimeMillis() >= saveAt) {
					save();
					saveAt = System.currentTimeMillis() + 30_000;
				}
				if (queueEmpty) {
					if (inFlight.get() == 0) break;
					LockSupport.parkNanos(50_000_000L);
					continue;
				}
				boolean playersOnline = server.getPlayerCount() > 0;
				pausedForPlayers = playersOnline && "pause".equals(players);
				if (pausedForPlayers) {
					LockSupport.parkNanos(1_000_000_000L);
					continue;
				}
				// Room left in the tick: back off when the server is busy.
				float mspt = server.getCurrentSmoothedTickTime();
				int budget = mspt > 45 ? 0 : mspt > 35 ? 2 : mspt > 25 ? 6 : MAX_IN_FLIGHT;
				if (playersOnline && "slow".equals(players)) budget = Math.min(budget, SLOW_IN_FLIGHT);
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
			if (!cancelled && lods) {
				lodPhase();
				finished(this, cancelled ? stopReason : lodResult);
				return;
			}
			finished(this, cancelled ? stopReason : "finished");
		}

		volatile String lodResult = "finished";

		/**
		 * Distant Horizons' own LOD pregeneration over the job's area (its wiki advises against relying on chunk
		 * pregenerators for LODs: loading chunks fast overloads it and leaves holes). Runs /dh pregen start, shows
		 * /dh pregen status every few seconds and waits for DH to report it complete; stopping the job stops it too.
		 * DH's area is a square of at least 32 chunks. With "pause", it waits until nobody is online to start.
		 */
		private void lodPhase() {
			phase = "lods";
			Pregen.save();
			while (!cancelled && "pause".equals(players) && server.getPlayerCount() > 0) {
				pausedForPlayers = true;
				LockSupport.parkNanos(2_000_000_000L);
			}
			pausedForPlayers = false;
			if (cancelled) return;
			int chunkRadius = Math.max(32, (radius + 15) / 16);
			java.util.concurrent.CompletableFuture<String> end = new java.util.concurrent.CompletableFuture<>();
			java.util.function.Consumer<String> listen = line -> {
				String l = line.toLowerCase(java.util.Locale.ROOT);
				if (l.contains("pregen is complete")) end.complete("finished");
				else if (l.contains("pregen is cancelled")) end.complete("LOD pregeneration cancelled");
				else if (l.contains("pregen failed")) end.complete("LOD pregeneration failed: " + line);
			};
			lodStatus = "starting";
			NiceControlCenter.LOGGER.info("Pregenerating Distant Horizons LODs in {} around {}, {} ({} chunks radius)", dimension, centerX, centerZ, chunkRadius);
			dh("dh pregen start " + dimension + " " + centerX + " " + centerZ + " " + chunkRadius, listen, end);
			long nextStatus = 0;
			while (!end.isDone()) {
				if (cancelled) {
					dh("dh pregen stop", null, null);
					try {
						end.get(15, java.util.concurrent.TimeUnit.SECONDS);
					} catch (Exception e) {
						// It stops on its own time; the job is over either way.
					}
					return;
				}
				if (System.currentTimeMillis() >= nextStatus) {
					nextStatus = System.currentTimeMillis() + 5_000;
					dh("dh pregen status", line -> {
						if (!line.isBlank()) lodStatus = line.strip();
					}, null);
				}
				LockSupport.parkNanos(500_000_000L);
			}
			lodResult = end.getNow("finished");
			lodStatus = null;
		}

		/**
		 * Runs a Distant Horizons command on the server thread; every line it prints now or later goes to listen.
		 * A command DH doesn't know (an old version) ends the LOD step with failed.
		 */
		private void dh(String command, java.util.function.Consumer<String> listen, java.util.concurrent.CompletableFuture<String> failed) {
			server.execute(() -> {
				net.minecraft.commands.CommandSource capture = new net.minecraft.commands.CommandSource() {
					@Override
					public void sendSystemMessage(net.minecraft.network.chat.Component message) {
						if (listen != null) listen.accept(message.getString());
					}

					@Override
					public boolean acceptsSuccess() {
						return true;
					}

					@Override
					public boolean acceptsFailure() {
						return true;
					}

					@Override
					public boolean shouldInformAdmins() {
						return false;
					}
				};
				boolean[] ran = {false};
				try {
					server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSource(capture).withCallback((ok, value) -> ran[0] |= ok), command);
				} catch (RuntimeException e) {
					NiceControlCenter.LOGGER.warn("/{} failed: {}", command, e.toString());
				}
				if (!ran[0] && failed != null) failed.complete("LOD pregeneration not possible (this Distant Horizons version has no /dh pregen)");
			});
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
	 * radius; players: "full", "slow" (default) or "pause" while players are online. Any thread.
	 */
	public static synchronized String start(MinecraftServer server, String dimension, int x, int z, int radius, int skipRadius, boolean circle,
			String players, boolean enqueue, boolean lods) {
		if (job != null && !enqueue) return "A pregeneration is already running.";
		Object made = create(server, dimension, x, z, radius, skipRadius, circle, players, lods);
		if (made instanceof String error) return error;
		add((Job) made);
		save();
		return null;
	}

	/** Whether Distant Horizons is installed (LOD pregeneration is only offered then). */
	public static boolean lodsAvailable() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("distanthorizons");
	}

	private static Object create(MinecraftServer server, String dimension, int x, int z, int radius, int skipRadius, boolean circle, String players,
			boolean lods) {
		if (radius < 16 || radius > 100_000) return "The radius must be between 16 and 100,000 blocks.";
		if (skipRadius < 0 || skipRadius >= radius) skipRadius = 0;
		Identifier id = Identifier.tryParse(dimension == null ? "" : dimension);
		ServerLevel level = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
		if (level == null) return "Unknown dimension " + dimension + ".";
		String mode = "full".equals(players) || "pause".equals(players) ? players : "slow";
		// Without Distant Horizons the LOD step is simply left out.
		return new Job(server, level, id.toString(), x, z, radius, skipRadius, circle, mode, lods && lodsAvailable());
	}

	private static void add(Job j) {
		if (job != null) {
			queue.add(j);
			NiceControlCenter.LOGGER.info("Pregeneration of {} queued ({} waiting)", j.dimension, queue.size());
		} else {
			launch(j);
		}
	}

	private static java.nio.file.Path file() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("nicecontrolcenter-pregen.json");
	}

	/** Writes the running job and the queue (or removes the file when there's nothing left). */
	static synchronized void save() {
		if (shuttingDown) return;
		java.util.List<Saved> jobs = new java.util.ArrayList<>();
		Job running = job;
		if (running != null && running.stopReason == null) jobs.add(saved(running));
		for (Job q : queue) jobs.add(saved(q));
		try {
			if (jobs.isEmpty()) {
				java.nio.file.Files.deleteIfExists(file());
			} else {
				java.nio.file.Path tmp = file().resolveSibling("nicecontrolcenter-pregen.json.tmp");
				java.nio.file.Files.writeString(tmp, eu.explorerseden.nicecontrolcenter.Json.GSON.toJson(jobs));
				java.nio.file.Files.move(tmp, file(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			}
		} catch (java.io.IOException e) {
			NiceControlCenter.LOGGER.warn("Could not save the pregeneration state: {}", e.toString());
		}
	}

	private static Saved saved(Job j) {
		Saved s = new Saved();
		s.dimension = j.dimension;
		s.x = j.centerX;
		s.z = j.centerZ;
		s.radius = j.radius;
		s.skipRadius = j.skipRadius;
		s.shape = j.circle ? "circle" : "square";
		s.players = j.players;
		s.ring = j.ringStart;
		s.done = j.doneAtRing;
		s.failed = j.failedAtRing;
		s.lods = j.lods;
		s.phase = j.phase;
		return s;
	}

	/** At server start: carries on with what was running (and queued) when the server stopped. */
	public static synchronized void resume(MinecraftServer server) {
		shuttingDown = false;
		java.nio.file.Path f = file();
		if (!java.nio.file.Files.exists(f)) return;
		Saved[] jobs;
		try {
			jobs = eu.explorerseden.nicecontrolcenter.Json.GSON.fromJson(java.nio.file.Files.readString(f), Saved[].class);
		} catch (java.io.IOException | RuntimeException e) {
			NiceControlCenter.LOGGER.warn("Could not read {}: {}", f, e.toString());
			return;
		}
		if (jobs == null) return;
		for (Saved s : jobs) {
			Object made = create(server, s.dimension, s.x, s.z, s.radius, s.skipRadius, "circle".equals(s.shape), s.players, s.lods);
			if (!(made instanceof Job j)) {
				NiceControlCenter.LOGGER.warn("Not resuming the pregeneration of {}: {}", s.dimension, made);
				continue;
			}
			j.ring = Math.max(0, s.ring);
			j.ringStart = j.ring;
			j.done.set(s.done);
			j.skipped.set(s.failed);
			j.doneAtRing = s.done;
			j.failedAtRing = s.failed;
			if ("lods".equals(s.phase) && j.lods) j.phase = "lods";
			NiceControlCenter.LOGGER.info("Resuming the pregeneration of {} at ring {} ({} chunks done)", s.dimension, j.ring, s.done);
			add(j);
		}
		save();
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
		boolean shutdown = SHUTDOWN.equals(reason);
		if (shutdown) {
			// Keep the file as it was saved at the stop, so the next start carries on.
			job = null;
			return;
		}
		job = null;
		Job next = "finished".equals(reason) ? queue.poll() : null;
		if (next != null) launch(next);
		save();
	}

	/** The reason given when the server stops; the job is kept for the next start. */
	public static final String SHUTDOWN = "stopped with the server";

	/** Stops the running job and drops the queue (kept for the next start when the server stops). Any thread. */
	public static synchronized void stop(MinecraftServer server, String reason) {
		Job j = job;
		boolean shutdown = SHUTDOWN.equals(reason);
		if (shutdown) {
			save();
			// From here on the file stays as it is until the next start.
			shuttingDown = true;
		}
		queue.clear();
		if (j != null) {
			j.stopReason = reason;
			j.cancelled = true;
			LockSupport.unpark(j.thread);
		}
		if (!shutdown) save();
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
		out.put("lodsAvailable", lodsAvailable());
		return out;
	}

	private static Map<String, Object> status(Job j) {
		Map<String, Object> out = new LinkedHashMap<>();
		long seconds = Math.max(1, (System.currentTimeMillis() - (j.started == 0 ? System.currentTimeMillis() : j.started)) / 1000);
		long done = j.done.get();
		long skipped = j.skipped.get();
		long total = Math.max(Math.max(0, j.total), done + skipped);
		double rate = (done + skipped) / (double) seconds;
		out.put("running", job == j && j.stopReason == null);
		out.put("counting", j.total < 0);
		out.put("dimension", j.dimension);
		out.put("x", j.centerX);
		out.put("z", j.centerZ);
		out.put("radius", j.radius);
		out.put("skipRadius", j.skipRadius);
		out.put("shape", j.circle ? "circle" : "square");
		out.put("players", j.players);
		if (j.pausedForPlayers) out.put("paused", "players online");
		out.put("done", done + skipped);
		out.put("failed", skipped);
		out.put("total", total);
		out.put("perSecond", Math.round(rate * 10) / 10.0);
		out.put("etaSeconds", rate > 0 ? Math.round((total - done - skipped) / rate) : -1);
		out.put("seconds", seconds);
		out.put("lods", j.lods);
		out.put("phase", j.phase);
		if (j.lodStatus != null) out.put("lodStatus", j.lodStatus);
		if (j.stopReason != null) out.put("result", j.stopReason);
		return out;
	}
}
