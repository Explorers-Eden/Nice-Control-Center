package eu.explorerseden.nicecontrolcenter.log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import eu.explorerseden.nicecontrolcenter.core.SourceIndex;
import eu.explorerseden.nicecontrolcenter.sampler.ModResolver;

/**
 * Groups warnings and errors from the server log and traces them back to the data pack or mod
 * they come from. Same messages with different numbers, UUIDs or positions count as one group.
 */
public final class ErrorWatcher {
	private static final int MAX_GROUPS = 500;
	private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
	private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z_])-?\\d+(?:\\.\\d+)?");
	private static final Pattern FROM_PACK = Pattern.compile("\\(from (file/[^)]+|[a-z0-9_.-]+)\\)");
	private static final Pattern PACK_PATH = Pattern.compile("datapacks[/\\\\]([^/\\\\]+)[/\\\\]");
	private static final Pattern RESOURCE = Pattern.compile("\\b([a-z0-9_.-]+):[a-z0-9_/.-]+");
	/** Known noise: worth seeing, but not a problem. */
	private static final Pattern HARMLESS = Pattern.compile(
			"Invalid path in pack: .*\\.DS_Store|Non-directory entry .*\\.DS_Store|SERVER IS RUNNING IN OFFLINE|The server will make no attempt to authenticate|resource-pack-id missing, using default"
					+ "|While this makes the game possible to play without internet|To change this, set \"online-mode\""
					+ "|Unable to parse version|TranslationConventionLogWarnings|No key layers in MapLike",
			Pattern.CASE_INSENSITIVE);

	/** One kind of message. */
	public static final class Group {
		public String key;
		public String level;
		public String source;
		public String sourceKind;
		public String logger;
		public String example;
		public String error;
		public long count;
		public long sinceReload;
		public long first;
		public long last;
		public boolean harmless;
		/** False while the source was guessed before the data pack index existed (early startup). */
		boolean sourceFinal;
		String firstMessage;
	}

	private static final Map<String, Group> groups = new ConcurrentHashMap<>();
	private static final ModResolver resolver = new ModResolver();
	private static volatile List<Pattern> ignore = List.of();
	private static volatile long reloadStarted;
	private static volatile long reloadSeq;

	private ErrorWatcher() {
	}

	public static void ignore(List<String> patterns) {
		List<Pattern> compiled = new ArrayList<>();
		for (String pattern : patterns) {
			try {
				compiled.add(Pattern.compile(pattern));
			} catch (RuntimeException e) {
				// Bad pattern in the config: skip it.
			}
		}
		ignore = compiled;
	}

	/** Called at the start of /reload: counts since this point are "since reload". */
	public static void reloadStarted() {
		reloadStarted = System.currentTimeMillis();
		reloadSeq = LogCapture.lastSeq();
		groups.values().forEach(g -> g.sinceReload = 0);
	}

	public static long reloadTime() {
		return reloadStarted;
	}

	static void record(LogCapture.Line line, Throwable thrown) {
		String message = firstLine(line.message());
		for (Pattern pattern : ignore) {
			if (pattern.matcher(message).find()) {
				return;
			}
		}
		String key = line.level() + "|" + normalise(message);
		Group group = groups.get(key);
		if (group == null) {
			if (groups.size() >= MAX_GROUPS) {
				return;
			}
			group = new Group();
			group.key = key;
			group.level = line.level();
			group.logger = line.logger();
			group.first = line.time();
			String[] source = source(message, line.logger(), thrown);
			group.source = source[0];
			group.sourceKind = source[1];
			group.firstMessage = message;
			group.sourceFinal = thrown != null || SourceIndex.ready();
			group.harmless = HARMLESS.matcher(message + " " + line.logger()).find();
			Group existing = groups.putIfAbsent(key, group);
			if (existing != null) {
				group = existing;
			}
		}
		synchronized (group) {
			group.count++;
			group.sinceReload++;
			group.last = line.time();
			group.example = line.message().length() > 2000 ? line.message().substring(0, 2000) + "…" : line.message();
			if (line.error() != null) {
				group.error = line.error();
			}
		}
	}

	private static String firstLine(String message) {
		int newline = message.indexOf('\n');
		return newline < 0 ? message : message.substring(0, newline);
	}

	private static String normalise(String message) {
		String text = UUID.matcher(message).replaceAll("<uuid>");
		return NUMBER.matcher(text).replaceAll("#");
	}

	/** {name, kind}: kind is "datapack", "mod", "minecraft" or "other". */
	private static String[] source(String message, String logger, Throwable thrown) {
		Matcher from = FROM_PACK.matcher(message);
		if (from.find()) {
			SourceIndex.Source pack = SourceIndex.pack(from.group(1));
			return new String[] {pack.name(), kind(pack)};
		}
		Matcher path = PACK_PATH.matcher(message);
		if (path.find()) {
			SourceIndex.Source pack = SourceIndex.pack("file/" + path.group(1));
			return new String[] {pack.name(), kind(pack)};
		}
		Matcher resource = RESOURCE.matcher(message);
		while (resource.find()) {
			String namespace = resource.group(1);
			if (namespace.equals("minecraft") || namespace.equals("file")) {
				continue;
			}
			SourceIndex.Source source = SourceIndex.known(namespace);
			if (source != null) {
				return new String[] {source.name(), kind(source)};
			}
		}
		if (logger != null) {
			for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
				String id = mod.getMetadata().getId();
				String name = mod.getMetadata().getName();
				if (!id.equals("minecraft") && !id.equals("java") && (logger.equalsIgnoreCase(id) || logger.equalsIgnoreCase(name)
						|| logger.toLowerCase(Locale.ROOT).startsWith(id + "/"))) {
					return new String[] {name, "mod"};
				}
			}
		}
		if (thrown != null) {
			synchronized (resolver) {
				for (Throwable t = thrown; t != null; t = t.getCause()) {
					for (StackTraceElement frame : t.getStackTrace()) {
						String owner = resolver.owner(frame);
						if (!owner.equals(ModResolver.JVM) && !owner.equals(ModResolver.MINECRAFT) && !owner.equals("fabricloader")) {
							return new String[] {SourceIndex.mod(owner).name(), "mod"};
						}
					}
				}
			}
		}
		if (logger == null || logger.equals("Minecraft") || logger.startsWith("net.minecraft") || logger.startsWith("com.mojang")) {
			return new String[] {"Minecraft", "minecraft"};
		}
		return new String[] {logger, "other"};
	}

	private static String kind(SourceIndex.Source source) {
		return switch (source.kind()) {
			case DATAPACK -> "datapack";
			case MOD -> "mod";
			case VANILLA -> "minecraft";
			default -> "other";
		};
	}

	/** All groups, newest first; optionally only those seen since the last /reload. */
	public static List<Group> groups(boolean sinceReload) {
		List<Group> result = new ArrayList<>();
		boolean ready = SourceIndex.ready();
		for (Group group : groups.values()) {
			if (!group.sourceFinal && ready) {
				// Logged during startup, before data packs were known: look again now.
				String[] source = source(group.firstMessage, group.logger, null);
				group.source = source[0];
				group.sourceKind = source[1];
				group.sourceFinal = true;
			}
			if (!sinceReload || group.sinceReload > 0) {
				result.add(group);
			}
		}
		result.sort(Comparator.comparing((Group g) -> g.harmless).thenComparing(g -> !g.level.equals("ERROR") && !g.level.equals("FATAL"))
				.thenComparing(Comparator.comparingLong((Group g) -> g.last).reversed()));
		return result;
	}

	/** Errors (not warnings, not harmless) per source since the last /reload, or since start. */
	public static Map<String, List<Group>> problemsBySource(boolean sinceReload) {
		Map<String, List<Group>> result = new java.util.LinkedHashMap<>();
		for (Group group : groups(sinceReload)) {
			if (!group.harmless && (group.level.equals("ERROR") || group.level.equals("FATAL"))) {
				result.computeIfAbsent(group.source, k -> new ArrayList<>()).add(group);
			}
		}
		return result;
	}

	public static void clear() {
		groups.clear();
	}
}
