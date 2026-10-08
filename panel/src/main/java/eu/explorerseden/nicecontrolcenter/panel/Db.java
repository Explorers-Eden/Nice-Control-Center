package eu.explorerseden.nicecontrolcenter.panel;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * The Postgres connection (DB_URL, DB_USER, DB_PASS). The panel keeps running while the database is
 * unreachable: it retries in the background, and the env admin can still log in and control the server.
 */
public final class Db {
	private final HikariDataSource pool;
	private volatile boolean ready;
	private volatile String problem = "Connecting to the database…";

	private Db(HikariDataSource pool) {
		this.pool = pool;
	}

	/** Null when no DB_URL is set: the panel then has only the env admin. */
	public static Db connect() {
		String url = Panel.env("DB_URL", "");
		if (url.isEmpty()) return null;
		if (url.startsWith("postgres://") || url.startsWith("postgresql://")) url = "jdbc:postgresql://" + url.substring(url.indexOf("://") + 3);
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(url);
		config.setUsername(Panel.env("DB_USER", ""));
		config.setPassword(Panel.env("DB_PASS", ""));
		config.setMaximumPoolSize(6);
		config.setPoolName("panel-db");
		// Don't fail at startup when the database is down; connections are tried on use.
		config.setInitializationFailTimeout(-1);
		config.setConnectionTimeout(5000);
		Db db = new Db(new HikariDataSource(config));
		Thread migrate = new Thread(db::migrateUntilReady, "db-migrate");
		migrate.setDaemon(true);
		migrate.start();
		return db;
	}

	private void migrateUntilReady() {
		while (!ready) {
			try {
				Flyway.configure().dataSource(pool).locations("classpath:db/migration").load().migrate();
				ready = true;
				problem = null;
				System.out.println("Database ready");
			} catch (RuntimeException e) {
				problem = "Database not reachable: " + rootMessage(e);
				System.err.println(problem + " (retrying in 10 s)");
				try {
					Thread.sleep(10_000);
				} catch (InterruptedException ie) {
					return;
				}
			}
		}
	}

	public boolean ready() {
		return ready;
	}

	/** Why the database can't be used right now, or null. */
	public String problem() {
		return problem;
	}

	public Connection connection() throws SQLException {
		if (!ready) throw new SQLException(problem);
		return pool.getConnection();
	}

	public void close() {
		pool.close();
	}

	private static String rootMessage(Throwable e) {
		while (e.getCause() != null && e.getCause() != e) e = e.getCause();
		return e.getMessage();
	}
}
