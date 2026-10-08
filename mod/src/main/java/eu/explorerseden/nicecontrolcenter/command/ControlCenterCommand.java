package eu.explorerseden.nicecontrolcenter.command;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.ControlCenterConfig;
import eu.explorerseden.nicecontrolcenter.data.Breakdown;
import eu.explorerseden.nicecontrolcenter.data.History;
import eu.explorerseden.nicecontrolcenter.diagnosis.Diagnoser;
import eu.explorerseden.nicecontrolcenter.diagnosis.Views;
import eu.explorerseden.nicecontrolcenter.record.Recorder;
import eu.explorerseden.nicecontrolcenter.update.UpdateManager;
import eu.explorerseden.nicecontrolcenter.web.DashboardServer;

/** /nicecontrolcenter (alias /ncc): status, top lists, dashboard link and recordings. */
public final class ControlCenterCommand {
	/**
	 * Permission nodes (LuckPerms or any other Fabric permissions mod). Without a permissions mod,
	 * or when a node isn't set, only operators (level 2+) may use them.
	 */
	public static final String PERMISSION_STATUS = "nicecontrolcenter.command.status";
	public static final String PERMISSION_WEB = "nicecontrolcenter.command.web";
	public static final String PERMISSION_MONITOR = "nicecontrolcenter.command.monitor";
	public static final String PERMISSION_RECORD = "nicecontrolcenter.command.record";
	public static final String PERMISSION_UPDATES = "nicecontrolcenter.command.updates";
	/** Receives "report ready" messages. */
	public static final String PERMISSION_NOTIFY = "nicecontrolcenter.notify";
	private static final PermissionLevel DEFAULT_LEVEL = PermissionLevel.GAMEMASTERS;
	private static final String[] COMMAND_NODES = {PERMISSION_STATUS, PERMISSION_WEB, PERMISSION_MONITOR, PERMISSION_RECORD, PERMISSION_UPDATES};

	private static final TextColor ACCENT = TextColor.fromRgb(0xb6c8ff);
	private static final TextColor GOLD = TextColor.fromRgb(0xffd18b);
	private static final TextColor MUTED = TextColor.fromRgb(0xcfd7e6);
	private static final TextColor GOOD = TextColor.fromRgb(0x6fe89b);
	private static final TextColor POOR = TextColor.fromRgb(0xff9090);

	private ControlCenterCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		// For every player: answer an admin privately (nothing goes to public chat).
		dispatcher.register(Commands.literal("nccreply")
				.requires(source -> source.getPlayer() != null)
				.then(Commands.argument("message", StringArgumentType.greedyString())
						.executes(ctx -> reply(ctx, StringArgumentType.getString(ctx, "message")))));
		var root = dispatcher.register(build("nicecontrolcenter"));
		dispatcher.register(Commands.literal("ncc")
				.requires(ControlCenterCommand::anyNode)
				.executes(ctx -> status(ctx, 1))
				.redirect(root));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> build(String name) {
		return Commands.literal(name)
				.requires(ControlCenterCommand::anyNode)
				.executes(ctx -> canStatus(ctx.getSource()) ? status(ctx, 1) : help(ctx))
				.then(Commands.literal("status")
						.requires(ControlCenterCommand::canStatus)
						.executes(ctx -> status(ctx, 1))
						.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 60))
								.executes(ctx -> status(ctx, IntegerArgumentType.getInteger(ctx, "minutes")))))
				.then(top("datapacks", ControlCenterCommand::packLines))
				.then(top("functions", ControlCenterCommand::functionLines))
				.then(top("mods", ControlCenterCommand::modLines))
				.then(top("entities", b -> typeLines(Views.entities(b))))
				.then(top("blockentities", b -> typeLines(Views.blockEntities(b))))
				.then(top("chunks", ControlCenterCommand::chunkLines))
				.then(top("phases", ControlCenterCommand::phaseLines))
				.then(Commands.literal("bloat")
						.requires(ControlCenterCommand::canStatus)
						.executes(ControlCenterCommand::bloat))
				.then(Commands.literal("bossbar")
						.requires(ControlCenterCommand::canStatus)
						.executes(ControlCenterCommand::bossbar))
				.then(Commands.literal("monitor")
						.requires(Permissions.require(PERMISSION_MONITOR, DEFAULT_LEVEL))
						.executes(ControlCenterCommand::monitorStatus)
						.then(Commands.literal("on").executes(ctx -> monitor(ctx, true)))
						.then(Commands.literal("off").executes(ctx -> monitor(ctx, false))))
				.then(Commands.literal("web")
						.requires(Permissions.require(PERMISSION_WEB, DEFAULT_LEVEL))
						.executes(ControlCenterCommand::web)
						.then(Commands.literal("regen").executes(ControlCenterCommand::regen))
						.then(Commands.literal("port")
								.then(Commands.argument("port", IntegerArgumentType.integer(1, 65535))
										.executes(ctx -> port(ctx, IntegerArgumentType.getInteger(ctx, "port"))))))
				.then(Commands.literal("updates")
						.requires(Permissions.require(PERMISSION_UPDATES, DEFAULT_LEVEL))
						.executes(ControlCenterCommand::updates)
						.then(Commands.literal("check").executes(ControlCenterCommand::updatesCheck))
						.then(Commands.literal("webhook")
								.executes(ctx -> updatesWebhook(ctx, false))
								.then(Commands.literal("regen").executes(ctx -> updatesWebhook(ctx, true))))
						.then(Commands.literal("skip")
								.then(Commands.argument("entry", StringArgumentType.greedyString())
										.suggests((ctx, builder) -> {
											UpdateManager.entries().forEach(e -> builder.suggest(e.key));
											return builder.buildFuture();
										})
										.executes(ctx -> updatesSkip(ctx, StringArgumentType.getString(ctx, "entry")))))
						.then(Commands.literal("rollback")
								.executes(ctx -> updatesRollback(ctx, null))
								.then(Commands.argument("backup", StringArgumentType.word())
										.suggests((ctx, builder) -> {
											UpdateManager.backups().forEach(b -> builder.suggest(b.id()));
											return builder.buildFuture();
										})
										.executes(ctx -> updatesRollback(ctx, StringArgumentType.getString(ctx, "backup"))))))
				.then(Commands.literal("record")
						.requires(Permissions.require(PERMISSION_RECORD, DEFAULT_LEVEL))
						.executes(ControlCenterCommand::recordStatus)
						.then(Commands.literal("start")
								.executes(ctx -> recordStart(ctx, 60))
								.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 24 * 60))
										.executes(ctx -> recordStart(ctx, IntegerArgumentType.getInteger(ctx, "minutes")))))
						.then(Commands.literal("stop").executes(ControlCenterCommand::recordStop))
						.then(Commands.literal("status").executes(ControlCenterCommand::recordStatus)));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> top(String what, Function<Breakdown, List<Component>> lines) {
		return Commands.literal("top").requires(ControlCenterCommand::canStatus).then(Commands.literal(what)
				.executes(ctx -> showTop(ctx, what, 1, lines))
				.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 60))
						.executes(ctx -> showTop(ctx, what, IntegerArgumentType.getInteger(ctx, "minutes"), lines))));
	}

	// ── Permissions ─────────────────────────────────────────────────────────

	private static boolean canStatus(CommandSourceStack source) {
		return Permissions.check(source, PERMISSION_STATUS, DEFAULT_LEVEL);
	}

	private static boolean anyNode(CommandSourceStack source) {
		for (String node : COMMAND_NODES) {
			if (Permissions.check(source, node, DEFAULT_LEVEL)) {
				return true;
			}
		}
		return false;
	}

	private static int help(CommandContext<CommandSourceStack> ctx) {
		ctx.getSource().sendSystemMessage(header("Commands").append("\n").append(muted(
				"Use /ncc web, /ncc monitor, /ncc record or /ncc updates, depending on what you're allowed to do.")));
		return 1;
	}

	// ── Bloat check ─────────────────────────────────────────────────────────

	private static int bloat(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		source.sendSystemMessage(header("Bloat check").append("\n").append(muted("Checking scoreboard, storage, tags and world files…")));
		eu.explorerseden.nicecontrolcenter.diagnosis.BloatCheck.start(source.getServer()).whenComplete((result, error) -> source.getServer().execute(() -> {
			if (error != null) {
				source.sendFailure(Component.literal("The bloat check failed: " + error.getMessage()));
				return;
			}
			MutableComponent message = header("Bloat check").append("\n").append(muted(String.format(Locale.ROOT,
					"Scoreboard: %d objectives, %s entries · Storage: %s · Tags: %s distinct · World: %s",
					result.objectives(), Diagnoser.count(result.scoreEntries()), Diagnoser.bytes(result.storageBytes()),
					Diagnoser.count(result.distinctTags()), Diagnoser.bytes(result.worldBytes()))));
			if (result.findings().isEmpty()) {
				message.append("\n").append(Component.literal("Nothing looks bloated.").withStyle(Style.EMPTY.withColor(GOOD)));
			}
			for (Diagnoser.Finding finding : result.findings()) {
				message.append("\n").append(Component.literal(" ■ " + finding.title()).withStyle(Style.EMPTY.withColor(color(finding.severity()))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal(plain(finding.reason()) + "\n\nTip: " + finding.hint())))));
			}
			source.sendSystemMessage(message);
		}));
		return 1;
	}

	// ── Boss bar ────────────────────────────────────────────────────────────

	private static int bossbar(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) {
			ctx.getSource().sendFailure(Component.literal("The boss bar is shown to players; run this in game."));
			return 0;
		}
		boolean on = eu.explorerseden.nicecontrolcenter.alert.BossBarMonitor.toggle(player);
		ctx.getSource().sendSystemMessage(header(on ? "Boss bar on" : "Boss bar off").append("\n").append(muted(on
				? "Shows TPS and MSPT of the last 5 seconds, updated every second. Run /ncc bossbar again to hide it."
				: "Run /ncc bossbar to show it again.")));
		return 1;
	}

	// ── Monitor on/off ──────────────────────────────────────────────────────

	private static int monitor(CommandContext<CommandSourceStack> ctx, boolean on) {
		NiceControlCenter.setMonitoring(on);
		CommandSourceStack source = ctx.getSource();
		source.sendSuccess(() -> withDashboardLink(source, header(on ? "Monitoring on" : "Monitoring off").append("\n").append(muted(on
				? "Measuring again from the next tick. Data shows up within a few seconds."
				: "Nothing is measured now, so there is no overhead. The last measurements stay visible. Turn it back on with /ncc monitor on.")), on), true);
		return 1;
	}

	private static int monitorStatus(CommandContext<CommandSourceStack> ctx) {
		boolean on = eu.explorerseden.nicecontrolcenter.core.Tracker.enabled();
		CommandSourceStack source = ctx.getSource();
		source.sendSystemMessage(withDashboardLink(source, header("Monitoring is " + (on ? "on" : "off")).append("\n")
				.append(muted(on ? "Turn it off with /ncc monitor off." : "Turn it on with /ncc monitor on.")), on));
		return on ? 1 : 0;
	}

	/** Adds the dashboard button while monitoring is on, for people allowed to open the dashboard. */
	private static MutableComponent withDashboardLink(CommandSourceStack source, MutableComponent message, boolean on) {
		DashboardServer dashboard = NiceControlCenter.dashboard();
		if (on && dashboard != null && dashboard.running() && Permissions.check(source, PERMISSION_WEB, DEFAULT_LEVEL)) {
			message.append("\n").append(dashboardButton(source));
		}
		return message;
	}

	// ── Status ──────────────────────────────────────────────────────────────

	private static int status(CommandContext<CommandSourceStack> ctx, int minutes) {
		int window = minutes >= 60 ? 60 : minutes >= 15 ? 15 : minutes >= 5 ? 5 : 1;
		Breakdown b = History.window(window);
		Diagnoser.Diagnosis diagnosis = Diagnoser.diagnose(b);
		Views.Kpi kpi = Views.kpi(b);

		MutableComponent message = header("Last " + window + (window == 1 ? " minute" : " minutes"));
		if (!eu.explorerseden.nicecontrolcenter.core.Tracker.enabled()) {
			message.append("\n").append(Component.literal("Monitoring is off; these are the last measurements. /ncc monitor on")
					.withStyle(Style.EMPTY.withColor(GOLD)));
		}
		message.append("\n").append(Component.literal("● ").withStyle(Style.EMPTY.withColor(color(diagnosis.health().status()))))
				.append(Component.literal(diagnosis.health().title()).withStyle(Style.EMPTY.withColor(0xffffff)));
		message.append("\n").append(muted(String.format(Locale.ROOT, "%.1f TPS · %.0f%% of the %.0f ms tick budget used",
				kpi.tps(), kpi.msptAvg() / kpi.budget() * 100, kpi.budget())));
		message.append("\n").append(muted("MSPT ")).append(Component.literal(String.format(Locale.ROOT,
				"min %.1f · med %.1f · 95%% %.1f · max %.1f", kpi.msptMin(), kpi.msptMedian(), kpi.msptP95(), kpi.msptMax()))
				.withStyle(Style.EMPTY.withColor(GOLD)));
		message.append("\n").append(muted(String.format(Locale.ROOT, "Memory %s / %s after GC · CPU %.0f%% · %,d entities · %,d chunks · %d players",
				Diagnoser.bytes(kpi.heapLive() > 0 ? kpi.heapLive() : kpi.heapUsed()), Diagnoser.bytes(kpi.heapMax()), kpi.cpuProcess(), kpi.counts().entities,
				kpi.counts().chunks, kpi.counts().players)));

		int shown = 0;
		for (Diagnoser.Finding finding : diagnosis.findings()) {
			if (shown++ >= 4) {
				break;
			}
			MutableComponent hover = Component.literal(finding.title()).withStyle(Style.EMPTY.withColor(GOLD))
					.append(Component.literal("\n" + plain(finding.reason())).withStyle(Style.EMPTY.withColor(0xffffff)))
					.append(Component.literal("\n\nTip: " + finding.hint()).withStyle(Style.EMPTY.withColor(ACCENT)));
			MutableComponent line = Component.literal("\n ")
					.append(Component.literal("■ ").withStyle(Style.EMPTY.withColor(color(finding.severity()))))
					.append(Component.literal(finding.category() + ": ").withStyle(Style.EMPTY.withColor(MUTED)))
					.append(Component.literal(finding.title()).withStyle(Style.EMPTY.withColor(0xffffff)));
			if (finding.ms() > 0) {
				line.append(Component.literal(String.format(Locale.ROOT, "  %s (%.0f%%)", Diagnoser.ms(finding.ms()), finding.percent()))
						.withStyle(Style.EMPTY.withColor(GOLD)));
			}
			message.append(line.withStyle(Style.EMPTY.withHoverEvent(new HoverEvent.ShowText(hover))));
		}
		if (diagnosis.findings().isEmpty()) {
			message.append("\n").append(muted("Nothing stands out."));
		} else {
			message.append("\n").append(muted("Hover a line for the reason and a tip."));
		}
		if (!diagnosis.notes().isEmpty()) {
			MutableComponent hover = Component.empty();
			for (Diagnoser.Finding note : diagnosis.notes()) {
				hover.append(Component.literal("• " + note.title() + "\n").withStyle(Style.EMPTY.withColor(GOLD)))
						.append(Component.literal(plain(note.hint()) + "\n").withStyle(Style.EMPTY.withColor(MUTED)));
			}
			int count = diagnosis.notes().size();
			message.append("\n").append(Component.literal(count + (count == 1 ? " server setting or check" : " server settings and checks")
					+ " worth a look (hover)").withStyle(Style.EMPTY.withColor(ACCENT).withHoverEvent(new HoverEvent.ShowText(hover))));
		}
		if (Permissions.check(ctx.getSource(), PERMISSION_WEB, DEFAULT_LEVEL)) {
			message.append("\n").append(dashboardButton(ctx.getSource()));
		}
		ctx.getSource().sendSystemMessage(message);
		return 1;
	}

	// ── Top lists ───────────────────────────────────────────────────────────

	private static int showTop(CommandContext<CommandSourceStack> ctx, String what, int minutes, Function<Breakdown, List<Component>> lines) {
		int window = minutes >= 60 ? 60 : minutes >= 15 ? 15 : minutes >= 5 ? 5 : 1;
		Breakdown b = History.window(window);
		MutableComponent message = header("Top " + what + " · last " + window + " min");
		List<Component> rows = lines.apply(b);
		if (rows.isEmpty()) {
			message.append("\n").append(muted("Nothing measured yet."));
		}
		for (Component row : rows) {
			message.append("\n").append(row);
		}
		ctx.getSource().sendSystemMessage(message);
		return rows.size();
	}

	private static List<Component> packLines(Breakdown b) {
		return Views.packs(b).stream().limit(8).map(p -> row(p.name(), p.ms(), p.percent(),
				p.functions().isEmpty() ? "" : "Heaviest: " + p.functions().get(0).id())).toList();
	}

	private static List<Component> functionLines(Breakdown b) {
		return Views.packs(b).stream().flatMap(p -> p.functions().stream())
				.sorted((x, y) -> Double.compare(y.ms(), x.ms())).limit(10)
				.map(f -> row(f.id(), f.ms(), f.percent(), f.lines().isEmpty() ? "" : "Heaviest line: " + f.lines().get(0).text())).toList();
	}

	private static List<Component> modLines(Breakdown b) {
		return Views.mods(b).stream().limit(8).map(m -> row(m.name(), m.ms(), m.percent(),
				String.format(Locale.ROOT, "Own code %.1f%%, vanilla code it called %.1f%%%s", m.selfPercent(), m.causedPercent(),
						m.methods().isEmpty() ? "" : "\nHottest: " + m.methods().get(0).name()))).toList();
	}

	private static List<Component> typeLines(List<Views.TypeView> types) {
		return types.stream().limit(10).map(t -> row(Diagnoser.prettyType(t.id()), t.ms(), t.percent(),
				String.format(Locale.ROOT, "%.0f loaded on average (max %d), %s each per tick\nFrom: %s",
						t.averageCount(), t.maxCount(), Diagnoser.eachMs(t.microsEach() / 1000), t.source()))).toList();
	}

	private static List<Component> chunkLines(Breakdown b) {
		return Views.hotspots(b).stream().map(h -> {
			String name = String.format(Locale.ROOT, "%d, %d in %s", h.blockX(), h.blockZ(), Diagnoser.prettyDimension(h.dimension()));
			MutableComponent component = (MutableComponent) row(name, h.ms(), Views.percentOf(b, h.ms()),
					String.format(Locale.ROOT, "%d entities, %d block entities%s\nClick to suggest a teleport", h.entities(), h.blockEntities(),
							h.topType() == null ? "" : "\nMostly " + h.topType() + " (" + h.topTypeCount() + ")"));
			String tp = String.format(Locale.ROOT, "/execute in %s run tp @s %d ~ %d", h.dimension(), h.blockX(), h.blockZ());
			return (Component) component.withStyle(component.getStyle().withClickEvent(new ClickEvent.SuggestCommand(tp)));
		}).toList();
	}

	private static List<Component> phaseLines(Breakdown b) {
		return Views.phases(b).stream().map(p -> row(p.label(), p.ms(), p.percent(), p.description())).toList();
	}

	private static Component row(String name, double ms, double percent, String hover) {
		MutableComponent row = Component.literal(" ")
				.append(Component.literal(String.format(Locale.ROOT, "%5.1f%% ", percent)).withStyle(Style.EMPTY.withColor(GOLD)))
				.append(Component.literal(name).withStyle(Style.EMPTY.withColor(0xffffff)))
				.append(Component.literal("  " + Diagnoser.ms(ms)).withStyle(Style.EMPTY.withColor(MUTED)));
		if (!hover.isEmpty()) {
			row.withStyle(Style.EMPTY.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
		}
		return row;
	}

	// ── Web ─────────────────────────────────────────────────────────────────

	private static int web(CommandContext<CommandSourceStack> ctx) {
		DashboardServer dashboard = NiceControlCenter.dashboard();
		if (dashboard == null || !dashboard.running()) {
			ctx.getSource().sendFailure(Component.literal("The dashboard is not running. Check web_enabled and the port in config/nicecontrolcenter.json and the server log."));
			return 0;
		}
		MutableComponent message = header("Dashboard");
		message.append("\n").append(dashboardButton(ctx.getSource()));
		message.append("\n").append(muted("This link contains a private key; don't share it. Use /ncc web regen to make a new one."));
		if (ctx.getSource().getPlayer() == null) {
			message.append("\n").append(Component.literal(dashboard.link()));
		}
		ctx.getSource().sendSystemMessage(message);
		return 1;
	}

	private static int regen(CommandContext<CommandSourceStack> ctx) {
		ControlCenterConfig config = NiceControlCenter.config();
		config.token = ControlCenterConfig.newToken();
		config.save();
		ctx.getSource().sendSuccess(() -> header("New dashboard link created. Old links stop working.")
				.append("\n").append(dashboardButton(ctx.getSource())), false);
		return 1;
	}

	private static int port(CommandContext<CommandSourceStack> ctx, int port) {
		ControlCenterConfig config = NiceControlCenter.config();
		config.port = port;
		config.save();
		NiceControlCenter.restartDashboard(ctx.getSource().getServer());
		DashboardServer dashboard = NiceControlCenter.dashboard();
		if (dashboard == null || !dashboard.running()) {
			ctx.getSource().sendFailure(Component.literal("Saved port " + port + ", but the dashboard could not start on it. See the server log."));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> header("Dashboard moved to port " + dashboard.port())
				.append("\n").append(dashboardButton(ctx.getSource())), false);
		return 1;
	}

	private static Component dashboardButton(CommandSourceStack source) {
		DashboardServer dashboard = NiceControlCenter.dashboard();
		if (dashboard == null || !dashboard.running()) {
			return muted("Dashboard disabled.");
		}
		String link = dashboard.link();
		return Component.literal("[Open live dashboard]").withStyle(Style.EMPTY.withColor(ACCENT).withUnderlined(true)
				.withClickEvent(new ClickEvent.OpenUrl(URI.create(link)))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(link))));
	}

	// ── Updates ─────────────────────────────────────────────────────────────

	private static int updates(CommandContext<CommandSourceStack> ctx) {
		ControlCenterConfig config = NiceControlCenter.config();
		List<UpdateManager.Entry> entries = UpdateManager.entries();
		MutableComponent message = header("Updates");
		if ("off".equals(config.update_mode)) {
			message.append("\n").append(muted("Update checks are off (update_mode in config/nicecontrolcenter.json)."));
		}
		if (UpdateManager.checking()) {
			message.append("\n").append(muted("Checking right now…"));
		} else if (entries.isEmpty()) {
			message.append("\n").append(muted("Not checked yet. Run /ncc updates check."));
		}
		int upToDate = 0;
		for (UpdateManager.Entry entry : entries) {
			String text = switch (entry.status) {
				case AVAILABLE -> "available: " + entry.installedVersion + " → " + entry.latestVersion;
				case STAGED -> "downloaded, waiting for approval in the dashboard: → " + entry.latestVersion;
				case PENDING -> "installs at the next restart: " + entry.installedVersion + " → " + entry.latestVersion;
				case HELD -> "held: " + entry.reason;
				case FAILED -> "failed: " + entry.reason;
				case SKIPPED -> "skipped " + entry.latestVersion;
				default -> null;
			};
			if (entry.status == UpdateManager.Status.UP_TO_DATE) {
				upToDate++;
			}
			if (text == null) {
				continue;
			}
			int color = switch (entry.status) {
				case HELD, FAILED -> 0xff9090;
				case SKIPPED -> 0xcfd7e6;
				default -> 0x6fe89b;
			};
			message.append("\n ").append(Component.literal("■ ").withStyle(Style.EMPTY.withColor(color)))
					.append(Component.literal(entry.name).withStyle(Style.EMPTY.withColor(GOLD)))
					.append(muted(" " + text));
		}
		if (!entries.isEmpty()) {
			long notFound = entries.stream().filter(e -> e.status == UpdateManager.Status.NOT_FOUND).count();
			message.append("\n").append(muted(upToDate + " up to date" + (notFound > 0 ? ", " + notFound + " not on Modrinth" : "")
					+ " · mode: " + config.update_mode));
		}
		DashboardServer dashboard = NiceControlCenter.dashboard();
		if (dashboard != null && dashboard.running() && Permissions.check(ctx.getSource(), PERMISSION_WEB, DEFAULT_LEVEL)) {
			message.append("\n").append(Component.literal("[Open updates in dashboard]").withStyle(Style.EMPTY.withColor(ACCENT).withUnderlined(true)
					.withClickEvent(new ClickEvent.OpenUrl(URI.create(dashboard.link() + "#updates")))));
		}
		ctx.getSource().sendSystemMessage(message);
		return entries.size();
	}

	private static int updatesCheck(CommandContext<CommandSourceStack> ctx) {
		if ("off".equals(NiceControlCenter.config().update_mode)) {
			ctx.getSource().sendFailure(Component.literal("Update checks are off. Set update_mode in config/nicecontrolcenter.json."));
			return 0;
		}
		if (UpdateManager.checking()) {
			ctx.getSource().sendFailure(Component.literal("A check is already running."));
			return 0;
		}
		UpdateManager.checkNow(NiceControlCenter.config());
		ctx.getSource().sendSuccess(() -> muted("Checking for updates in the background. See the result with /ncc updates."), false);
		return 1;
	}

	private static int reply(CommandContext<CommandSourceStack> ctx, String message) {
		ServerPlayer player = ctx.getSource().getPlayer();
		String text = message.replaceAll("[\\r\\n]+", " ").strip();
		if (player == null || text.isEmpty()) {
			return 0;
		}
		if (text.length() > 256) {
			text = text.substring(0, 256);
		}
		String name = player.getGameProfile().name();
		if (!eu.explorerseden.nicecontrolcenter.players.Conversations.fromPlayer(player.getUUID(), name, text)) {
			ctx.getSource().sendFailure(Component.literal("Slow down a little; try again in a few seconds."));
			return 0;
		}
		ctx.getSource().sendSystemMessage(Component.literal("Sent to the admins; only they can see it.").withStyle(Style.EMPTY.withColor(MUTED)));
		String shown = text;
		NiceControlCenter.notifyAdmins(Component.literal("[Reply from " + name + "] ").withStyle(Style.EMPTY.withColor(GOLD))
				.append(Component.literal(shown).withStyle(Style.EMPTY.withColor(0xffffff))), "Reply from " + name + ": " + shown);
		return 1;
	}

	private static int updatesWebhook(CommandContext<CommandSourceStack> ctx, boolean regen) {
		ControlCenterConfig config = NiceControlCenter.config();
		if (regen || config.resource_pack_webhook_token.isBlank()) {
			config.resource_pack_webhook_token = ControlCenterConfig.newToken();
			config.save();
		}
		DashboardServer dashboard = NiceControlCenter.dashboard();
		String base = dashboard != null && dashboard.running() ? dashboard.link().replaceFirst("/\\?t=.*$", "") : "http://<server>:" + config.port;
		String curl = "curl -fsS -X POST -H \"Authorization: Bearer " + config.resource_pack_webhook_token + "\" " + base + "/api/updates/resourcepack/check";
		MutableComponent message = header("Resource pack build ping")
				.append("\n").append(muted("Add this as the last step of the script that builds your resource pack, so the server checks right away. "
						+ "The token only allows this one action. Keep it in your build secrets."))
				.append("\n").append(Component.literal("[Copy curl command]").withStyle(Style.EMPTY.withColor(ACCENT).withUnderlined(true)
						.withClickEvent(new ClickEvent.CopyToClipboard(curl))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal(curl)))))
				.append("\n").append(muted(regen ? "New token created; the old one stops working." : "New token: /ncc updates webhook regen"));
		if (ctx.getSource().getPlayer() == null) {
			message.append("\n").append(Component.literal(curl));
		}
		ctx.getSource().sendSystemMessage(message);
		return 1;
	}

	private static int updatesSkip(CommandContext<CommandSourceStack> ctx, String key) {
		String error = UpdateManager.action("skip", key.trim());
		if (error != null) {
			ctx.getSource().sendFailure(Component.literal(error + " Use the names suggested after /ncc updates skip."));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> muted("Skipping this version of " + key.trim() + ". Newer versions will still be offered."), true);
		return 1;
	}

	private static int updatesRollback(CommandContext<CommandSourceStack> ctx, String batch) {
		if (batch == null) {
			List<UpdateManager.Backup> backups = UpdateManager.backups();
			if (backups.isEmpty()) {
				ctx.getSource().sendFailure(Component.literal("There are no backups to roll back to."));
				return 0;
			}
			batch = backups.get(0).id();
		}
		String id = batch;
		String error = UpdateManager.rollback(id);
		if (error != null) {
			ctx.getSource().sendFailure(Component.literal(error));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> header("Rollback planned").append("\n")
				.append(muted("The files replaced in " + id + " are put back at the next restart, and those versions are skipped from now on.")), true);
		return 1;
	}

	// ── Recording ───────────────────────────────────────────────────────────

	private static int recordStart(CommandContext<CommandSourceStack> ctx, int minutes) {
		CommandSourceStack source = ctx.getSource();
		if (!eu.explorerseden.nicecontrolcenter.core.Tracker.enabled()) {
			NiceControlCenter.setMonitoring(true);
			source.sendSystemMessage(muted("Monitoring was off; switched it on for the recording."));
		}
		String error = Recorder.start(source.getServer(), minutes, source.getTextName(), NiceControlCenter.config().max_recording_hours);
		if (error != null) {
			source.sendFailure(Component.literal(error));
			return 0;
		}
		source.sendSuccess(() -> header("Recording started")
				.append("\n").append(muted("Runs for " + minutes + " minutes, or until /ncc record stop. "
						+ "You'll get a downloadable report when it ends.")), true);
		return 1;
	}

	private static int recordStop(CommandContext<CommandSourceStack> ctx) {
		var future = Recorder.stop(ctx.getSource().getServer());
		if (future == null) {
			ctx.getSource().sendFailure(Component.literal("Nothing is being recorded. Start with /ncc record start [minutes]."));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> header("Recording stopped").append("\n").append(muted("Building the report…")), true);
		return 1;
	}

	private static int recordStatus(CommandContext<CommandSourceStack> ctx) {
		Recorder.Status status = Recorder.status();
		MutableComponent message = header("Recording");
		if (!status.active()) {
			message.append("\n").append(muted("Not recording. Start one with /ncc record start [minutes]."));
		} else {
			long left = Math.max(0, status.endsAt() - System.currentTimeMillis()) / 60_000;
			message.append("\n").append(muted(String.format(Locale.ROOT, "Recording for %d min (started by %s), stops in %d min.",
					status.seconds() / 60, status.startedBy(), left)));
		}
		List<Recorder.ReportFile> reports = Recorder.reports(ctx.getSource().getServer());
		if (!reports.isEmpty()) {
			message.append("\n").append(muted("Latest report: " + reports.get(0).name() + " (open the dashboard to download)"));
		}
		ctx.getSource().sendSystemMessage(message);
		return 1;
	}

	/** Chat message when a lag episode starts. */
	public static Component lagAlert(String summary, String causeText, Diagnoser.Finding cause) {
		MutableComponent message = header("Lag detected").append("\n")
				.append(Component.literal(summary).withStyle(Style.EMPTY.withColor(POOR)));
		if (causeText != null) {
			MutableComponent line = muted("Mainly ").append(Component.literal(causeText).withStyle(Style.EMPTY.withColor(0xffffff)));
			if (cause != null) {
				line.withStyle(Style.EMPTY.withHoverEvent(new HoverEvent.ShowText(Component.literal(plain(cause.reason()))
						.append(Component.literal("\n\nTip: " + cause.hint()).withStyle(Style.EMPTY.withColor(ACCENT))))));
			}
			message.append("\n").append(line);
		}
		message.append("\n").append(muted("Run /ncc for details. A lag report is being recorded."));
		DashboardServer dashboard = NiceControlCenter.dashboard();
		if (dashboard != null && dashboard.running()) {
			message.append(" ").append(Component.literal("[Open dashboard]").withStyle(Style.EMPTY.withColor(ACCENT).withUnderlined(true)
					.withClickEvent(new ClickEvent.OpenUrl(URI.create(dashboard.link())))));
		}
		return message;
	}

	/** Chat message after a /reload that logged errors. */
	public static Component reloadErrors(String summary, java.util.Map<String, java.util.List<eu.explorerseden.nicecontrolcenter.log.ErrorWatcher.Group>> problems) {
		MutableComponent hover = Component.empty();
		problems.forEach((source, groups) -> {
			hover.append(Component.literal(source + "\n").withStyle(Style.EMPTY.withColor(GOLD)));
			for (int i = 0; i < groups.size() && i < 3; i++) {
				String example = groups.get(i).example;
				hover.append(Component.literal("  " + (example.length() > 120 ? example.substring(0, 120) + "…" : example) + "\n")
						.withStyle(Style.EMPTY.withColor(MUTED)));
			}
		});
		MutableComponent message = header("Reload finished with errors").append("\n")
				.append(Component.literal(summary).withStyle(Style.EMPTY.withColor(POOR).withHoverEvent(new HoverEvent.ShowText(hover))));
		DashboardServer dashboard = NiceControlCenter.dashboard();
		if (dashboard != null && dashboard.running()) {
			message.append("\n").append(Component.literal("[Show in dashboard]").withStyle(Style.EMPTY.withColor(ACCENT).withUnderlined(true)
					.withClickEvent(new ClickEvent.OpenUrl(URI.create(dashboard.link() + "#server")))));
		}
		return message;
	}

	/** Chat message when a lag episode is over. */
	public static Component lagRecovered(long minutes) {
		return header("Lag is over").append("\n").append(Component.literal("Back under the limits after about " + minutes + " min.")
				.withStyle(Style.EMPTY.withColor(GOOD)));
	}

	/** Chat message for a finished report, sent to everyone allowed to use the command. */
	public static Component reportReady(String text) {
		MutableComponent message = header(text);
		DashboardServer dashboard = NiceControlCenter.dashboard();
		if (dashboard != null && dashboard.running()) {
			message.append("\n").append(Component.literal("[Open reports in dashboard]").withStyle(Style.EMPTY.withColor(ACCENT).withUnderlined(true)
					.withClickEvent(new ClickEvent.OpenUrl(URI.create(dashboard.link().replace("/?t=", "/?view=reports&t="))))));
		}
		return message;
	}

	// ── Formatting ──────────────────────────────────────────────────────────

	private static MutableComponent header(String text) {
		return Component.literal("Nice Control Center").withStyle(Style.EMPTY.withColor(GOLD))
				.append(Component.literal(" · " + text).withStyle(Style.EMPTY.withColor(ACCENT)));
	}

	private static MutableComponent muted(String text) {
		return Component.literal(text).withStyle(Style.EMPTY.withColor(MUTED));
	}

	private static TextColor color(Diagnoser.Severity severity) {
		return switch (severity) {
			case POOR -> POOR;
			case WARN -> GOLD;
			case INFO -> ACCENT;
			case GOOD -> GOOD;
		};
	}

	private static String plain(String text) {
		return text.replace("`", "");
	}
}
