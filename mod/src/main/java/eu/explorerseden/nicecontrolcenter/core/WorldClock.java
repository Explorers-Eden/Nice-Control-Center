package eu.explorerseden.nicecontrolcenter.core;

import java.util.Locale;
import java.util.Optional;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * In-game date and time for the dashboard header. Uses the calendar of the Nice Actions data pack
 * when it's installed (storage eden:calendar), otherwise the vanilla day counter.
 */
public final class WorldClock {
	/**
	 * @param source "nice_actions" or "vanilla"
	 * @param time   e.g. "14:05" or "2:05 PM"
	 * @param date   e.g. "October 12, 2026", or "Day 37" for vanilla
	 * @param detail extra line for the tooltip (weekday, season, time of day)
	 * @param weather "clear", "rain" or "thunder"
	 * @param day    whether the sun is up (for the icon)
	 * @param minuteOfDay exact in-game minute of the day (0–1440), so the dashboard can count on smoothly
	 * @param twelveHour  show "2:05 PM" instead of "14:05"
	 */
	public record Clock(String source, String time, String date, String weekday, String detail, String weather, boolean day,
			double minuteOfDay, boolean twelveHour) {
	}

	private static final Identifier CALENDAR = Identifier.fromNamespaceAndPath("eden", "calendar");
	private static final Identifier SETTINGS = Identifier.fromNamespaceAndPath("eden", "settings");
	private static volatile Clock current;

	private WorldClock() {
	}

	public static Clock current() {
		return current;
	}

	/** Reads the clock; server thread, once per second. */
	public static void update(MinecraftServer server) {
		try {
			ServerLevel overworld = server.overworld();
			String weather = overworld.isThundering() ? "thunder" : overworld.isRaining() ? "rain" : "clear";
			Clock clock = niceActions(server, weather);
			current = clock != null ? clock : vanilla(overworld, weather);
		} catch (RuntimeException e) {
			current = null;
		}
	}

	private static Clock niceActions(MinecraftServer server, String weather) {
		CompoundTag calendar = server.getCommandStorage().get(CALENDAR);
		Optional<CompoundTag> global = calendar == null ? Optional.empty() : calendar.getCompound("global");
		if (global.isEmpty() || global.get().getString("month_name").isEmpty()) {
			return null;
		}
		CompoundTag g = global.get();
		int hour24 = number(g.get("24_hour"));
		int minute = number(g.get("minute"));
		boolean twelveHour = server.getCommandStorage().get(SETTINGS).getCompound("nice_actions")
				.flatMap(s -> s.getInt("time_format")).map(f -> f == 12).orElse(false);
		String time = twelveHour
				? String.format(Locale.ROOT, "%d:%02d %s", hour24 % 12 == 0 ? 12 : hour24 % 12, minute, hour24 < 12 ? "AM" : "PM")
				: String.format(Locale.ROOT, "%02d:%02d", hour24, minute);
		String date = g.getString("month_name").orElse("") + " " + number(g.get("day")) + ", " + number(g.get("year"));
		String weekday = g.getString("weekday").orElse("");
		String detail = String.join(" · ", nonEmpty(g.getString("season").orElse(""), g.getString("daypart").orElse("")));
		return new Clock("nice_actions", time, date, weekday, detail, weather, hour24 >= 6 && hour24 < 18, hour24 * 60 + minute, twelveHour);
	}

	private static Clock vanilla(ServerLevel overworld, String weather) {
		long ticks = overworld.getOverworldClockTime();
		long day = ticks / 24000L + 1;
		long ofDay = Math.floorMod(ticks, 24000L);
		// Tick 0 is 06:00 in the morning.
		double exact = (ofDay * 1440.0 / 24000.0 + 6 * 60) % 1440.0;
		long minutes = (long) exact;
		int hour = (int) (minutes / 60);
		String time = String.format(Locale.ROOT, "%02d:%02d", hour, minutes % 60);
		return new Clock("vanilla", time, "Day " + String.format(Locale.ROOT, "%,d", day), "", "Minecraft day counter", weather,
				hour >= 6 && hour < 18, exact, false);
	}

	private static int number(Tag tag) {
		if (tag == null) {
			return 0;
		}
		// Nice Actions stores some values as padded strings ("07").
		return tag.asInt().or(() -> tag.asString().flatMap(WorldClock::parse)).orElse(0);
	}

	private static Optional<Integer> parse(String text) {
		try {
			return Optional.of(Integer.parseInt(text.trim()));
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

	private static String[] nonEmpty(String... parts) {
		return java.util.Arrays.stream(parts).filter(p -> !p.isBlank()).toArray(String[]::new);
	}
}
