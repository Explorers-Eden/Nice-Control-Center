package eu.explorerseden.nicecontrolcenter.client;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import eu.explorerseden.nicecontrolcenter.ControlCenterConfig;
import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.web.DashboardServer;

/**
 * Nice Control Center's settings for singleplayer, opened from Mod Menu. Edits
 * config/nicecontrolcenter.json; most values apply the next time a world is opened.
 */
public class ConfigScreen extends Screen {
	private enum Kind { BOOL, INT, DOUBLE, TEXT, CHOICE, COLOR }

	private record Option(String field, String label, String tooltip, Kind kind, double min, double max, List<String> choices) {
	}

	private record Category(String name, List<Option> options) {
	}

	private static final List<Category> CATEGORIES = List.of(
			new Category("Dashboard", List.of(
					bool("web_enabled", "Web dashboard", "Serve the dashboard in your browser while a world is open."),
					number("port", "Port", "Port the dashboard listens on (127.0.0.1 in singleplayer).", 1, 65535),
					bool("web_console", "Show server log", "Show the live log in the dashboard's Console tab."),
					bool("web_console_commands", "Run commands", "Allow running commands from the dashboard console."))),
			new Category("Monitor", List.of(
					bool("monitor_enabled", "Live monitor", "Measure what costs performance while a world is open."),
					number("sampler_interval_ms", "Sampler interval (ms)", "How often mods are sampled. Lower = more detail, more overhead.", 1, 1000),
					number("spike_threshold_ms", "Lag spike from (ms)", "Ticks slower than this are listed as lag spikes.", 1, 10000),
					number("max_recording_hours", "Max recording (hours)", "Recordings stop on their own after this long.", 1, 168))),
			new Category("Alerts", List.of(
					bool("alerts_enabled", "Lag alerts in chat", "Tell you in chat when the game lags, and why."),
					decimal("alert_mspt", "Alert above MSPT", "Lag = average milliseconds per tick above this…", 1, 1000),
					decimal("alert_tps", "Alert below TPS", "…or ticks per second below this…", 1, 20),
					number("alert_seconds", "For seconds", "…for this many seconds in a row.", 1, 3600),
					bool("auto_record_lag", "Save lag reports", "Save a report of every lag episode, including the 5 minutes before it."),
					number("auto_record_per_day", "Reports per day", "At most this many automatic lag reports per day.", 0, 1000))),
			new Category("Updates", List.of(
					choice("update_mode", "Update mode", "In singleplayer updates are only checked, never installed.", "auto", "stage", "check", "off"),
					number("update_check_hours", "Check every (hours)", "Hours between update checks.", 1, 720),
					number("update_keep_backups", "Keep backups", "How many backups of replaced versions to keep.", 1, 100))),
			new Category("Editing", List.of(
					bool("web_settings_edit", "Data pack settings", "Allow changing data pack settings in the dashboard."),
					bool("web_gamerules_edit", "Gamerules", "Allow changing gamerules in the dashboard."),
					bool("web_player_actions", "Player actions", "Allow kicking, banning and messaging players from the dashboard."),
					text("message_prefix", "Message prefix", "Shown before private messages from the dashboard, e.g. [Admin]."),
					new Option("message_color", "Prefix color", "A color name (gold, red, aqua, purple, pink, …) or #RRGGBB.", Kind.COLOR, 0, 0, List.of()),
					text("players_storage", "Player database", "Command storage with homes, graves and waypoints for the Players tab."))));

	private final Screen parent;
	private final ControlCenterConfig working;
	private final Map<String, String> errors = new LinkedHashMap<>();
	private int category;
	private Button done;

	public ConfigScreen(Screen parent) {
		super(Component.literal("Nice Control Center"));
		this.parent = parent;
		ControlCenterConfig current = NiceControlCenter.config() != null ? NiceControlCenter.config() : ControlCenterConfig.load();
		this.working = Json.GSON.fromJson(Json.GSON.toJson(current), ControlCenterConfig.class);
	}

	private static Option bool(String field, String label, String tooltip) {
		return new Option(field, label, tooltip, Kind.BOOL, 0, 0, List.of());
	}

	private static Option number(String field, String label, String tooltip, double min, double max) {
		return new Option(field, label, tooltip, Kind.INT, min, max, List.of());
	}

	private static Option decimal(String field, String label, String tooltip, double min, double max) {
		return new Option(field, label, tooltip, Kind.DOUBLE, min, max, List.of());
	}

	private static Option text(String field, String label, String tooltip) {
		return new Option(field, label, tooltip, Kind.TEXT, 0, 0, List.of());
	}

	private static Option choice(String field, String label, String tooltip, String... choices) {
		return new Option(field, label, tooltip, Kind.CHOICE, 0, 0, List.of(choices));
	}

	@Override
	protected void init() {
		int center = this.width / 2;
		addRenderableWidget(new StringWidget(center - 100, 10, 200, 12, this.title, this.font));

		// Category buttons.
		int tabWidth = Math.min(80, (this.width - 20) / CATEGORIES.size());
		int tabX = center - tabWidth * CATEGORIES.size() / 2;
		for (int i = 0; i < CATEGORIES.size(); i++) {
			int index = i;
			Button tab = Button.builder(Component.literal(CATEGORIES.get(i).name()), b -> {
				category = index;
				rebuildWidgets();
			}).bounds(tabX + i * tabWidth, 28, tabWidth - 2, 20).build();
			tab.active = i != category;
			addRenderableWidget(tab);
		}

		int y = 58;
		int labelX = center - 155;
		int controlX = center + 5;
		for (Option option : CATEGORIES.get(category).options()) {
			StringWidget label = new StringWidget(labelX, y + 5, 150, 10, Component.literal(option.label()), this.font);
			label.setTooltip(Tooltip.create(Component.literal(option.tooltip())));
			addRenderableWidget(label);
			addRenderableWidget(control(option, controlX, y));
			y += 24;
		}

		int bottom = this.height - 28;
		DashboardServer dashboard = NiceControlCenter.dashboard();
		Button open = Button.builder(Component.literal("Open dashboard"), b -> {
			if (NiceControlCenter.dashboard() != null && NiceControlCenter.dashboard().running()) {
				ConfirmLinkScreen.confirmLinkNow(this, URI.create(NiceControlCenter.dashboard().link()), false);
			}
		}).bounds(center - 155, bottom, 100, 20).build();
		open.active = dashboard != null && dashboard.running();
		open.setTooltip(Tooltip.create(Component.literal(open.active ? "Opens the live dashboard in your browser."
				: "Open a world first; the dashboard runs while a world is open.")));
		addRenderableWidget(open);
		done = addRenderableWidget(Button.builder(Component.literal("Done"), b -> save()).bounds(center - 50, bottom, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(center + 55, bottom, 100, 20).build());
		addRenderableWidget(new StringWidget(center - 155, bottom - 16, 310, 10,
				Component.literal(errors.isEmpty() ? "Most changes apply the next time a world is opened." : errors.values().iterator().next()),
				this.font));
		done.active = errors.isEmpty();
	}

	private net.minecraft.client.gui.components.AbstractWidget control(Option option, int x, int y) {
		Component tooltip = Component.literal(option.tooltip());
		switch (option.kind()) {
			case BOOL -> {
				CycleButton<Boolean> button = CycleButton.onOffBuilder((Boolean) get(option.field())).displayOnlyValue()
						.create(x, y, 150, 20, Component.literal(option.label()), (b, value) -> set(option.field(), value));
				button.setTooltip(Tooltip.create(tooltip));
				return button;
			}
			case CHOICE -> {
				String current = String.valueOf(get(option.field()));
				CycleButton<String> button = CycleButton.<String>builder(Component::literal, option.choices().contains(current) ? current : option.choices().get(0))
						.withValues(option.choices()).displayOnlyValue()
						.create(x, y, 150, 20, Component.literal(option.label()), (b, value) -> set(option.field(), value));
				button.setTooltip(Tooltip.create(tooltip));
				return button;
			}
			default -> {
				EditBox box = new EditBox(this.font, x, y, 150, 20, Component.literal(option.label()));
				box.setMaxLength(256);
				Object value = get(option.field());
				box.setValue(option.kind() == Kind.DOUBLE ? trim((Double) value) : String.valueOf(value));
				box.setTooltip(Tooltip.create(tooltip));
				box.setResponder(text -> {
					String error = apply(option, text);
					if (error == null) {
						errors.remove(option.field());
						box.setTextColor(0xFFE0E0E0);
					} else {
						errors.put(option.field(), option.label() + ": " + error);
						box.setTextColor(0xFFFF7070);
					}
					if (done != null) {
						done.active = errors.isEmpty();
					}
				});
				return box;
			}
		}
	}

	/** Stores a typed value in the working copy; returns an error or null. */
	private String apply(Option option, String text) {
		String clean = text.strip();
		switch (option.kind()) {
			case INT -> {
				try {
					int value = Integer.parseInt(clean);
					if (value < option.min() || value > option.max()) {
						return "between " + (int) option.min() + " and " + (int) option.max();
					}
					set(option.field(), value);
				} catch (NumberFormatException e) {
					return "a whole number";
				}
			}
			case DOUBLE -> {
				try {
					double value = Double.parseDouble(clean);
					if (value < option.min() || value > option.max()) {
						return "between " + trim(option.min()) + " and " + trim(option.max());
					}
					set(option.field(), value);
				} catch (NumberFormatException e) {
					return "a number";
				}
			}
			case COLOR -> {
				if (ControlCenterConfig.color(clean) == null) {
					return "not a color (try gold, aqua, purple or #RRGGBB)";
				}
				set(option.field(), clean);
			}
			default -> set(option.field(), clean);
		}
		return null;
	}

	private static String trim(double value) {
		return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
	}

	private Object get(String field) {
		try {
			return ControlCenterConfig.class.getField(field).get(working);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private void set(String field, Object value) {
		try {
			ControlCenterConfig.class.getField(field).set(working, value);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Copies the edited values into the live config, saves it and applies what can change right away. */
	private void save() {
		ControlCenterConfig live = NiceControlCenter.config();
		if (live == null) {
			working.save();
			onClose();
			return;
		}
		boolean monitorChanged = live.monitor_enabled != working.monitor_enabled;
		for (Field field : ControlCenterConfig.class.getFields()) {
			if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
				continue;
			}
			try {
				field.set(live, field.get(working));
			} catch (IllegalAccessException e) {
				// Public fields; can't happen.
			}
		}
		live.save();
		if (monitorChanged) {
			NiceControlCenter.setMonitoring(live.monitor_enabled);
		}
		onClose();
	}

	@Override
	public void onClose() {
		eu.explorerseden.nicecontrolcenter.compat.Screens.show(this.minecraft, parent);
	}
}
