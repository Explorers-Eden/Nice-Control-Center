package eu.explorerseden.nicecontrolcenter.panel;

import io.javalin.router.JavalinDefaultRoutingApi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static eu.explorerseden.nicecontrolcenter.panel.Web.*;

/**
 * Long file and world work (copy, zip, unzip, trim previews) runs here instead of inside the web request,
 * so a big folder can't time out behind a reverse proxy. The page polls /api/jobs/{id} for progress and
 * the result. Finished jobs are kept for an hour.
 */
final class Jobs {
	interface Work {
		Object run() throws Exception;
	}

	static final class Job {
		final String id = UUID.randomUUID().toString().substring(0, 8);
		final String kind;
		final String label;
		final String by;
		final long started = System.currentTimeMillis();
		volatile long ended;
		volatile String progress = "";
		volatile String error;
		volatile Object result;

		Job(String kind, String label, String by) {
			this.kind = kind;
			this.label = label;
			this.by = by;
		}

		Map<String, Object> view() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", id);
			m.put("kind", kind);
			m.put("label", label);
			m.put("started", started);
			m.put("done", ended > 0);
			m.put("progress", progress);
			if (error != null) m.put("error", error);
			if (result != null) m.put("result", result);
			return m;
		}
	}

	private static final ThreadLocal<Job> CURRENT = new ThreadLocal<>();
	private static final long KEEP_MS = 3600_000L;

	private final Map<String, Job> jobs = new ConcurrentHashMap<>();
	private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "job");
		t.setDaemon(true);
		return t;
	});

	/** Runs the work in the background; the error is the exception's message. */
	Job submit(String kind, String label, String by, Work work) {
		long cutoff = System.currentTimeMillis() - KEEP_MS;
		jobs.values().removeIf(j -> j.ended > 0 && j.ended < cutoff);
		Job job = new Job(kind, label, by);
		jobs.put(job.id, job);
		pool.submit(() -> {
			CURRENT.set(job);
			try {
				job.result = work.run();
			} catch (Exception e) {
				job.error = e.getMessage() == null ? e.toString() : e.getMessage();
			} finally {
				CURRENT.remove();
				job.ended = System.currentTimeMillis();
			}
		});
		return job;
	}

	/** Progress text for the job running on this thread (no-op outside a job). */
	static void progress(String text) {
		Job j = CURRENT.get();
		if (j != null) j.progress = text;
	}

	void register(JavalinDefaultRoutingApi routes) {
		// Each account sees its own jobs.
		routes.get("/api/jobs", guard(null, (ctx, me) -> {
			List<Map<String, Object>> out = new ArrayList<>();
			jobs.values().stream().filter(j -> j.by.equals(me.name())).sorted((a, b) -> Long.compare(b.started, a.started)).forEach(j -> out.add(j.view()));
			json(ctx, Map.of("jobs", out));
		}));
		routes.get("/api/jobs/{id}", guard(null, (ctx, me) -> {
			Job j = jobs.get(ctx.pathParam("id"));
			if (j == null || !j.by.equals(me.name())) {
				error(ctx, io.javalin.http.HttpStatus.NOT_FOUND, "No such job (finished jobs are kept for an hour).");
				return;
			}
			json(ctx, j.view());
		}));
	}
}
