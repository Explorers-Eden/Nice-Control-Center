package eu.explorerseden.nicecontrolcenter.panel;

import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Who did what in the panel. Written to the audit_log table in the background, and always to the
 * container log too, so nothing is lost while the database is down.
 */
public final class Audit {
	private final Accounts accounts;
	private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "audit-writer");
		t.setDaemon(true);
		return t;
	});

	public Audit(Accounts accounts) {
		this.accounts = accounts;
	}

	public void log(String user, String action, String detail, String ip) {
		String clean = detail == null ? null : detail.length() > 2000 ? detail.substring(0, 2000) + "…" : detail;
		System.out.println("[audit] " + (user == null ? "-" : user) + " " + action + (clean == null ? "" : ": " + clean) + (ip == null ? "" : " (" + ip + ")"));
		if (accounts == null) return;
		writer.submit(() -> {
			try {
				accounts.audit(user, action, clean, ip);
			} catch (SQLException e) {
				System.err.println("Could not write the audit log entry: " + e.getMessage());
			}
		});
	}
}
