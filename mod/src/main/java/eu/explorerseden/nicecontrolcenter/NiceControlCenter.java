package eu.explorerseden.nicecontrolcenter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import eu.explorerseden.nicecontrolcenter.alert.AlertWatcher;
import eu.explorerseden.nicecontrolcenter.alert.BossBarMonitor;
import eu.explorerseden.nicecontrolcenter.command.ControlCenterCommand;
import eu.explorerseden.nicecontrolcenter.core.Snapshotter;
import eu.explorerseden.nicecontrolcenter.core.SourceIndex;
import eu.explorerseden.nicecontrolcenter.core.Tracker;
import eu.explorerseden.nicecontrolcenter.data.History;
import eu.explorerseden.nicecontrolcenter.log.ErrorWatcher;
import eu.explorerseden.nicecontrolcenter.log.LogCapture;
import java.util.Map;
import eu.explorerseden.nicecontrolcenter.diagnosis.Advisor;
import eu.explorerseden.nicecontrolcenter.record.Recorder;
import eu.explorerseden.nicecontrolcenter.sampler.StackSampler;
import eu.explorerseden.nicecontrolcenter.update.UpdateManager;
import eu.explorerseden.nicecontrolcenter.web.DashboardServer;
import eu.explorerseden.nicecontrolcenter.worldgen.WorldgenTracker;

/**
 * Nice Control Center: always-on server monitor. Starts with the server, keeps the last 15 minutes, and
 * shows what costs performance and why in a web dashboard and in chat.
 */
public class NiceControlCenter implements ModInitializer {
	public static final String MOD_ID = "nicecontrolcenter";
	public static final Logger LOGGER = LoggerFactory.getLogger("Nice Control Center");

	private static ControlCenterConfig config;
	private static volatile MinecraftServer server;
	private static StackSampler sampler;
	private static DashboardServer dashboard;
	/** Messages for operators that nobody online could read yet; sent when the next one joins. */
	private static final java.util.List<String> laterMessages = new java.util.concurrent.CopyOnWriteArrayList<>();

	@Override
	public void onInitialize() {
		LogCapture.install();
		eu.explorerseden.nicecontrolcenter.core.Pregen.register();
		eu.explorerseden.nicecontrolcenter.core.WarmTargets.register();
		Migration.configFiles();
		config = ControlCenterConfig.load();
		ErrorWatcher.ignore(config.error_ignore);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> ControlCenterCommand.register(dispatcher));
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> eu.explorerseden.nicecontrolcenter.command.LinksCommand.register(dispatcher));
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(eu.explorerseden.nicecontrolcenter.command.LinksCommand::tick);

		ServerLifecycleEvents.SERVER_STARTED.register(NiceControlCenter::start);
		// Under the panel: Discord linking at login, chat and game events for the Discord bridge.
		eu.explorerseden.nicecontrolcenter.web.PanelBridge.register();
		// Land claims (off until switched on in the panel or config/nicecontrolcenter-claims.json).
		eu.explorerseden.nicecontrolcenter.claims.ClaimStore.register();
		// Who is active (claim blocks, AFK tag) and the gameplay tweaks from the panel.
		eu.explorerseden.nicecontrolcenter.core.PlayerActivity.register();
		eu.explorerseden.nicecontrolcenter.tweaks.TweaksControl.register();
		eu.explorerseden.nicecontrolcenter.rewards.RewardsControl.register();
		eu.explorerseden.nicecontrolcenter.players.TempFly.register();
		eu.explorerseden.nicecontrolcenter.players.Perks.register();
		eu.explorerseden.nicecontrolcenter.claims.GameClaims.register();
		eu.explorerseden.nicecontrolcenter.games.GameRuntime.register();
		eu.explorerseden.nicecontrolcenter.games.DungeonRuntime.register();
		eu.explorerseden.nicecontrolcenter.rewards.RewardActions.register();
		eu.explorerseden.nicecontrolcenter.market.MarketStore.register();
		eu.explorerseden.nicecontrolcenter.pets.PetStore.register();
		// Pregeneration runs on its own thread; the tick only keeps players inside the hard border.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(eu.explorerseden.nicecontrolcenter.core.HardBorder::tick);
		// The outer wilds message and post effect when players cross the soft border.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(eu.explorerseden.nicecontrolcenter.core.OuterWilds::tick);
		// Performance and maintenance settings from the panel (config files, read again when they change).
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.START_SERVER_TICK.register(eu.explorerseden.nicecontrolcenter.core.PerfControl::tick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(eu.explorerseden.nicecontrolcenter.core.MaintenanceControl::tick);
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			eu.explorerseden.nicecontrolcenter.core.PerfControl.reset();
			eu.explorerseden.nicecontrolcenter.core.MaintenanceControl.reset();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> eu.explorerseden.nicecontrolcenter.core.Pregen.stop(server, eu.explorerseden.nicecontrolcenter.core.Pregen.SHUTDOWN));
		ServerLifecycleEvents.SERVER_STARTED.register(eu.explorerseden.nicecontrolcenter.core.Pregen::resume);
		startConfigWatcher();
		ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((server, resources) -> ErrorWatcher.reloadStarted());
		// Data packs' own English texts, for messages sent to Discord.
		ServerLifecycleEvents.SERVER_STARTED.register(eu.explorerseden.nicecontrolcenter.web.PackLang::load);
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> eu.explorerseden.nicecontrolcenter.web.PackLang.load(server));
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
			server.execute(() -> reportReloadErrors(server));
			SourceIndex.reload(server);
			WorldgenTracker.clearCaches();
			eu.explorerseden.nicecontrolcenter.packs.PackSettings.clear();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(NiceControlCenter::stop);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> UpdateManager.stop());
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			sendLaterMessages(handler.player);
			// A player's very first visit: the panel welcomes them in chat and on Discord.
			boolean first = eu.explorerseden.nicecontrolcenter.players.Seen.joined(server, handler.player.getUUID());
			if (first) eu.explorerseden.nicecontrolcenter.web.PanelBridge.firstJoin(server, handler.player);
			eu.explorerseden.nicecontrolcenter.rewards.RewardsControl.joined(server, handler.player, first);
		});
		ServerLifecycleEvents.SERVER_STARTED.register(eu.explorerseden.nicecontrolcenter.players.Seen::load);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			eu.explorerseden.nicecontrolcenter.players.ClientInfo.forget(handler.player.getUUID());
			eu.explorerseden.nicecontrolcenter.players.Seen.left(handler.player.getUUID());
			eu.explorerseden.nicecontrolcenter.core.HardBorder.forget(handler.player.getUUID());
			eu.explorerseden.nicecontrolcenter.core.OuterWilds.forget(handler.player.getUUID());
		});
	}

	public static ControlCenterConfig config() {
		return config;
	}

	/**
	 * Picks up hand edits of config/nicecontrolcenter.json while running: the new values are copied
	 * into the config object everything holds. Port, bind address and sampler settings still need a
	 * restart (or /ncc web port).
	 */
	private static void startConfigWatcher() {
		java.util.concurrent.ScheduledExecutorService watcher = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
			Thread thread = new Thread(r, "Nice Control Center Config Watcher");
			thread.setDaemon(true);
			return thread;
		});
		watcher.scheduleWithFixedDelay(() -> {
			MinecraftServer running = server;
			if (config != null && running != null && config.tokenExpired()) {
				running.execute(() -> {
					if (config.tokenExpired()) {
						config.renewToken();
						config.save();
						LOGGER.info("The dashboard token is older than token_hours ({}), made a new one; run /ncc web for the link", config.token_hours);
					}
				});
			}
			if (config == null || !ControlCenterConfig.changedOnDisk()) {
				return;
			}
			ControlCenterConfig fresh = ControlCenterConfig.read();
			if (fresh == null) {
				return;
			}
			Runnable apply = () -> {
				boolean monitor = config.monitor_enabled;
				config.copyFrom(fresh);
				config.save();
				ErrorWatcher.ignore(config.error_ignore);
				if (monitor != config.monitor_enabled && server != null) {
					setMonitoring(config.monitor_enabled);
				}
				if (ControlCenterConfig.color(config.message_color) == null) {
					LOGGER.warn("message_color \"{}\" isn't a color; use a Minecraft color name (gold, dark_purple, aqua, …) or #RRGGBB.",
							config.message_color);
				}
				LOGGER.info("Reloaded config/nicecontrolcenter.json (port, bind and sampler changes apply after a restart)");
			};
			MinecraftServer current = server;
			if (current != null) {
				current.execute(apply);
			} else {
				apply.run();
			}
		}, 3, 3, java.util.concurrent.TimeUnit.SECONDS);
	}

	public static DashboardServer dashboard() {
		return dashboard;
	}

	private static void start(MinecraftServer minecraftServer) {
		MinecraftServer server = minecraftServer;
		NiceControlCenter.server = server;
		Migration.dataFolder(server.getServerDirectory());
		eu.explorerseden.nicecontrolcenter.core.DiskUsage.start(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT), server.getServerDirectory());
		config = ControlCenterConfig.load();
		if (config.token_hours <= 0 || config.tokenExpired()) {
			// Without token_hours a dashboard link only works until the next restart.
			config.renewToken();
			config.save();
			LOGGER.info("New dashboard token; run /ncc web for the link");
		}
		History.clear();
		SourceIndex.reload(server);
		Tracker.spikeThresholdNs = Math.max(1, config.spike_threshold_ms) * 1_000_000L;
		Tracker.start(server);
		Tracker.setEnabled(config.monitor_enabled);

		sampler = new StackSampler(config.sampler_interval_ms);
		Snapshotter.sampler(sampler);
		sampler.start();
		eu.explorerseden.nicecontrolcenter.core.HostMetrics.start();

		Recorder.announcer(message -> notifyAdmins(ControlCenterCommand.reportReady(message), message));
		Recorder.cleanUp(server);
		AlertWatcher.reset();
		Advisor.reset();
		BossBarMonitor.load();

		restartDashboard(server);
		UpdateManager.start(server, config);
		eu.explorerseden.nicecontrolcenter.players.XrayCheck.reset();
		eu.explorerseden.nicecontrolcenter.schedule.Scheduler.start(server);
		LOGGER.info(config.monitor_enabled ? "Nice Control Center is monitoring the server"
				: "Nice Control Center is ready; monitoring is off (turn it on with /ncc monitor on)");
	}

	/**
	 * Sends a message to operators (level 2+) and anyone granted nicecontrolcenter.notify, and logs it
	 * to the console. Safe to call from any thread.
	 */
	/** {@code logText} null: not logged (the panel shows it as an alert already). */
	public static void notifyAdmins(Component message, String logText) {
		if (logText != null) {
			LOGGER.info(logText);
		}
		MinecraftServer current = server;
		if (current == null) {
			return;
		}
		current.execute(() -> {
			for (ServerPlayer player : current.getPlayerList().getPlayers()) {
				if (Permissions.check(player, ControlCenterCommand.PERMISSION_NOTIFY, PermissionLevel.GAMEMASTERS)) {
					player.sendSystemMessage(message);
				}
			}
		});
	}

	/**
	 * Like {@link #notifyAdmins}, but if no operator is online the message waits and goes to the next
	 * one who joins. Used for news from before the server was up, such as installed updates.
	 */
	public static void notifyLater(String text) {
		LOGGER.info(text);
		MinecraftServer current = server;
		boolean anyoneOnline = current != null && current.getPlayerList().getPlayers().stream()
				.anyMatch(player -> Permissions.check(player, ControlCenterCommand.PERMISSION_NOTIFY, PermissionLevel.GAMEMASTERS));
		if (anyoneOnline) {
			notifyAdmins(Component.literal(text), text);
		} else {
			laterMessages.add(text);
		}
	}

	private static void sendLaterMessages(ServerPlayer player) {
		if (laterMessages.isEmpty() || !Permissions.check(player, ControlCenterCommand.PERMISSION_NOTIFY, PermissionLevel.GAMEMASTERS)) {
			return;
		}
		java.util.List<String> messages = java.util.List.copyOf(laterMessages);
		laterMessages.clear();
		messages.forEach(text -> player.sendSystemMessage(Component.literal(text)));
	}

	/** After /reload: tell operators which data packs or mods logged errors while loading. */
	private static void reportReloadErrors(MinecraftServer server) {
		Map<String, java.util.List<ErrorWatcher.Group>> problems = ErrorWatcher.problemsBySource(true);
		if (problems.isEmpty()) {
			return;
		}
		int errors = problems.values().stream().mapToInt(java.util.List::size).sum();
		String summary = "Reload: " + errors + (errors == 1 ? " error" : " errors") + " in " + problems.size()
				+ (problems.size() == 1 ? " data pack or mod" : " data packs or mods") + " (" + String.join(", ", problems.keySet()) + ")";
		notifyAdmins(ControlCenterCommand.reloadErrors(summary, problems), summary);
	}

	/** Switches the live monitor on or off and remembers the choice for the next start. */
	public static void setMonitoring(boolean on) {
		Tracker.setEnabled(on);
		config.monitor_enabled = on;
		config.save();
	}

	public static void restartDashboard(MinecraftServer server) {
		if (dashboard != null) {
			dashboard.stop();
			dashboard = null;
		}
		if (config.web_enabled) {
			dashboard = new DashboardServer(server, config);
			dashboard.start();
		}
	}

	private static void stop(MinecraftServer server) {
		eu.explorerseden.nicecontrolcenter.core.DiskUsage.stop();
		var report = Recorder.stop(server);
		if (report != null) {
			try {
				report.get();
			} catch (Exception e) {
				LOGGER.warn("Report for the running recording could not be finished", e);
			}
		}
		eu.explorerseden.nicecontrolcenter.schedule.Scheduler.stop();
		Tracker.stop();
		BossBarMonitor.stop();
		NiceControlCenter.server = null;
		eu.explorerseden.nicecontrolcenter.core.HostMetrics.stop();
		if (sampler != null) {
			sampler.stop();
			sampler = null;
		}
		Snapshotter.sampler(null);
		if (dashboard != null) {
			dashboard.stop();
			dashboard = null;
		}
	}
}
