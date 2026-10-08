package eu.explorerseden.nicecontrolcenter.alert;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import net.minecraft.server.MinecraftServer;

import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.ControlCenterConfig;
import eu.explorerseden.nicecontrolcenter.command.ControlCenterCommand;
import eu.explorerseden.nicecontrolcenter.data.Breakdown;
import eu.explorerseden.nicecontrolcenter.data.History;
import eu.explorerseden.nicecontrolcenter.diagnosis.Diagnoser;
import eu.explorerseden.nicecontrolcenter.record.Recorder;

/**
 * Watches every second for lag episodes: MSPT above (or TPS below) the configured limit for
 * {@code alert_seconds} in a row. Tells operators what's causing it and starts an automatic
 * recording so the episode can be looked at later.
 */
public final class AlertWatcher {
	private static final int PRE_ROLL_MINUTES = 5;
	private static final long TAIL_MILLIS = 2 * 60_000L;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	private static int badStreak;
	private static int goodStreak;
	private static volatile boolean lagging;
	private static volatile long lagSince;
	private static long lastAlert;
	private static boolean alertSent;

	private AlertWatcher() {
	}

	public static boolean lagging() {
		return lagging;
	}

	public static long lagSince() {
		return lagSince;
	}

	public static void reset() {
		badStreak = 0;
		goodStreak = 0;
		lagging = false;
		alertSent = false;
	}

	/** Called by the snapshotter once per second on the server thread. */
	public static void onSecond(MinecraftServer server, Breakdown second) {
		ControlCenterConfig config = NiceControlCenter.config();
		if (config == null || (!config.alerts_enabled && !config.auto_record_lag)) {
			return;
		}
		if (second.ticks == 0) {
			// Paused while empty: neither lag nor recovery.
			return;
		}
		double targetTps = 1000.0 / Math.max(1e-3, second.targetMspt);
		boolean bad = second.msptAvg() > config.alert_mspt || second.tps(targetTps) < config.alert_tps;
		if (bad) {
			badStreak++;
			goodStreak = 0;
		} else {
			goodStreak++;
			badStreak = 0;
		}
		int needed = Math.max(3, config.alert_seconds);

		if (!lagging && badStreak >= needed) {
			lagging = true;
			lagSince = System.currentTimeMillis() - needed * 1000L;
			onLagStart(server, config, needed);
		} else if (lagging && goodStreak >= needed) {
			lagging = false;
			onRecovered(server, config);
		}
	}

	private static void onLagStart(MinecraftServer server, ControlCenterConfig config, int seconds) {
		Breakdown window = History.lastSeconds(seconds);
		Diagnoser.Diagnosis diagnosis = Diagnoser.diagnose(window);
		Diagnoser.Finding cause = diagnosis.findings().stream().filter(f -> f.ms() > 0).findFirst().orElse(null);
		String summary = String.format(Locale.ROOT, "%.0f MSPT, %.1f TPS for %d s", window.msptAvg(),
				window.tps(1000.0 / Math.max(1e-3, window.targetMspt)), seconds);
		String causeText = cause == null ? null
				: cause.category().toLowerCase(Locale.ROOT) + " \"" + cause.title() + "\" (" + Diagnoser.ms(cause.ms()) + ")";

		long now = System.currentTimeMillis();
		alertSent = config.alerts_enabled && now - lastAlert >= config.alert_cooldown_minutes * 60_000L;
		if (alertSent) {
			lastAlert = now;
			NiceControlCenter.notifyAdmins(ControlCenterCommand.lagAlert(summary, causeText, cause), "Lag: " + summary
					+ (causeText == null ? "" : ". Mainly " + causeText));
		}
		if (config.auto_record_lag) {
			String reason = "Lag at " + LocalTime.now().format(TIME) + ": " + summary + (causeText == null ? "" : ", mainly " + causeText);
			Recorder.startAutomatic(server, reason, config.auto_record_max_minutes, config.auto_record_per_day, PRE_ROLL_MINUTES);
		}
	}

	private static void onRecovered(MinecraftServer server, ControlCenterConfig config) {
		long minutes = Math.max(1, (System.currentTimeMillis() - lagSince) / 60_000L);
		if (alertSent) {
			NiceControlCenter.notifyAdmins(ControlCenterCommand.lagRecovered(minutes), "Lag is over after about " + minutes + " min");
		}
		alertSent = false;
		Recorder.finishAutomaticIn(TAIL_MILLIS);
	}
}
