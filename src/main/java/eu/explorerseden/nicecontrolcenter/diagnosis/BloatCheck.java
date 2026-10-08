package eu.explorerseden.nicecontrolcenter.diagnosis;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;

import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.core.SourceIndex;

/**
 * On-demand check for leftovers that slowly make a server slower: huge scoreboards, big command
 * storage, piles of entity tags and large files in the world folder.
 */
public final class BloatCheck {
	public record Item(String name, String source, long value) {
	}

	public record Result(long time, int objectives, long scoreEntries, int trackedHolders, List<Item> topObjectives,
			long storageBytes, List<Item> storage, long taggedEntities, int distinctTags, List<Item> topTags,
			long worldBytes, List<Item> folders, List<Item> largestFiles, List<Diagnoser.Finding> findings) {
	}

	private static volatile Result last;
	private static volatile CompletableFuture<Result> running;
	/** Files looked at so far by a running check, for the progress shown in the dashboard. */
	private static final java.util.concurrent.atomic.AtomicLong scannedFiles = new java.util.concurrent.atomic.AtomicLong();
	private static volatile String lastError;

	private BloatCheck() {
	}

	public static Result last() {
		return last;
	}

	public static boolean isRunning() {
		CompletableFuture<Result> current = running;
		return current != null && !current.isDone();
	}

	public static long scannedFiles() {
		return scannedFiles.get();
	}

	public static String lastError() {
		return lastError;
	}

	/** Starts a check unless one is already running; returns the running one. */
	public static synchronized CompletableFuture<Result> start(MinecraftServer server) {
		if (isRunning()) {
			return running;
		}
		lastError = null;
		scannedFiles.set(0);
		running = run(server).whenComplete((result, error) -> {
			if (error != null) {
				lastError = error.getCause() != null ? error.getCause().getMessage() : error.getMessage();
				NiceControlCenter.LOGGER.warn("Bloat check failed", error);
			}
		});
		return running;
	}

	/** Runs the check: game data on the server thread, file sizes in the background. */
	private static CompletableFuture<Result> run(MinecraftServer server) {
		CompletableFuture<GameData> game = CompletableFuture.supplyAsync(() -> gameData(server), server);
		Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
		CompletableFuture<FileData> files = CompletableFuture.supplyAsync(() -> fileData(world));
		return game.thenCombine(files, (g, f) -> {
			Result result = new Result(System.currentTimeMillis(), g.objectives, g.scoreEntries, g.trackedHolders, g.topObjectives,
					g.storageBytes, g.storage, g.taggedEntities, g.distinctTags, g.topTags, f.total, f.folders, f.largest,
					findings(g, f));
			last = result;
			return result;
		});
	}

	private record GameData(int objectives, long scoreEntries, int trackedHolders, List<Item> topObjectives, long storageBytes,
			List<Item> storage, long taggedEntities, int distinctTags, List<Item> topTags) {
	}

	private record FileData(long total, List<Item> folders, List<Item> largest) {
	}

	private static GameData gameData(MinecraftServer server) {
		Scoreboard scoreboard = server.getScoreboard();
		List<Item> objectives = new ArrayList<>();
		long entries = 0;
		for (Objective objective : scoreboard.getObjectives()) {
			int size = scoreboard.listPlayerScores(objective).size();
			entries += size;
			objectives.add(new Item(objective.getName(), guessSource(objective.getName()), size));
		}
		objectives.sort(Comparator.comparingLong(Item::value).reversed());

		List<Item> storage = new ArrayList<>();
		long storageBytes = 0;
		for (Identifier key : server.getCommandStorage().keys().toList()) {
			long bytes = nbtSize(server.getCommandStorage().get(key));
			storageBytes += bytes;
			storage.add(new Item(key.toString(), guessSource(key.getNamespace()), bytes));
		}
		storage.sort(Comparator.comparingLong(Item::value).reversed());

		Map<String, Long> tags = new HashMap<>();
		long tagged = 0;
		for (ServerLevel level : server.getAllLevels()) {
			for (Entity entity : level.getAllEntities()) {
				if (!entity.entityTags().isEmpty()) {
					tagged++;
					for (String tag : entity.entityTags()) {
						tags.merge(tag, 1L, Long::sum);
					}
				}
			}
		}
		List<Item> topTags = new ArrayList<>();
		tags.forEach((tag, count) -> topTags.add(new Item(tag, guessSource(tag), count)));
		topTags.sort(Comparator.comparingLong(Item::value).reversed());

		return new GameData(objectives.size(), entries, scoreboard.getTrackedPlayers().size(), top(objectives, 10), storageBytes,
				top(storage, 10), tagged, tags.size(), top(topTags, 10));
	}

	private static long nbtSize(CompoundTag tag) {
		if (tag == null) {
			return 0;
		}
		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) {
			NbtIo.write(tag, out);
			return bytes.size();
		} catch (IOException e) {
			return tag.sizeInBytes();
		}
	}

	private static FileData fileData(Path world) {
		Map<String, Long> folders = new HashMap<>();
		List<Item> files = new ArrayList<>();
		long[] total = {0};
		try {
			// walkFileTree instead of Files.walk: a single unreadable or locked file must not stop the scan.
			Files.walkFileTree(world, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
					if (attributes.isRegularFile()) {
						long size = attributes.size();
						total[0] += size;
						scannedFiles.incrementAndGet();
						Path relative = world.relativize(path);
						String folder = relative.getNameCount() > 1 ? folderLabel(relative) : "(world root)";
						folders.merge(folder, size, Long::sum);
						files.add(new Item(relative.toString().replace('\\', '/'), null, size));
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path path, IOException e) {
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException | RuntimeException e) {
			NiceControlCenter.LOGGER.warn("Bloat check could not read the world folder", e);
		}
		List<Item> folderItems = new ArrayList<>();
		folders.forEach((name, size) -> folderItems.add(new Item(name, null, size)));
		folderItems.sort(Comparator.comparingLong(Item::value).reversed());
		files.sort(Comparator.comparingLong(Item::value).reversed());
		return new FileData(total[0], top(folderItems, 12), top(files, 10));
	}

	/** Groups e.g. "dimensions/minecraft/overworld/region/r.0.0.mca" as "dimensions/minecraft/overworld/region". */
	private static String folderLabel(Path relative) {
		int depth = Math.min(relative.getNameCount() - 1, relative.getName(0).toString().equals("dimensions") ? 4 : 1);
		return relative.subpath(0, depth).toString().replace('\\', '/');
	}

	/** Data packs often prefix their names with their namespace ("nicemobs.timer", "nm_kills"). */
	static String guessSource(String name) {
		for (String separator : new String[] {":", ".", "_", "-"}) {
			int i = name.indexOf(separator);
			if (i > 0) {
				SourceIndex.Source source = SourceIndex.known(name.substring(0, i));
				if (source != null) {
					return source.name();
				}
			}
		}
		SourceIndex.Source exact = SourceIndex.known(name);
		return exact == null ? null : exact.name();
	}

	private static List<Item> top(List<Item> items, int count) {
		return new ArrayList<>(items.subList(0, Math.min(count, items.size())));
	}

	private static List<Diagnoser.Finding> findings(GameData g, FileData f) {
		List<Diagnoser.Finding> out = new ArrayList<>();
		if (g.scoreEntries > 20_000) {
			Item top = g.topObjectives.isEmpty() ? null : g.topObjectives.get(0);
			String where = top == null ? "" : String.format(Locale.ROOT, " The largest objective is `%s` with %s entries%s.", top.name(),
					Diagnoser.count(top.value()), top.source() == null ? "" : " (" + top.source() + ")");
			out.add(new Diagnoser.Finding(g.scoreEntries > 200_000 ? Diagnoser.Severity.WARN : Diagnoser.Severity.INFO, "Bloat",
					"Large scoreboard", 0, 0, String.format(Locale.ROOT, "%s score entries in %d objectives, for %s score holders.%s",
							Diagnoser.count(g.scoreEntries), g.objectives, Diagnoser.count(g.trackedHolders), where),
					"The whole scoreboard is saved on every autosave. Data packs that track entities by UUID or never reset scores often cause this; "
							+ "/scoreboard players reset on old holders helps."));
		}
		// A few hundred KB of storage is normal for data packs; only flag what's really large.
		if (!g.storage.isEmpty() && g.storage.get(0).value() > 5L * 1024 * 1024) {
			Item top = g.storage.get(0);
			out.add(new Diagnoser.Finding(top.value() > 20L * 1024 * 1024 ? Diagnoser.Severity.WARN : Diagnoser.Severity.INFO, "Bloat",
					"Large command storage", 0, 0, String.format(Locale.ROOT, "Storage `%s`%s holds %s.", top.name(),
							top.source() == null ? "" : " (" + top.source() + ")", Diagnoser.bytes(top.value())),
					"Every /data get or modify on this storage copies large NBT, and it's saved with the world. Lists that only grow are the usual cause."));
		}
		if (g.distinctTags > 2_000) {
			out.add(new Diagnoser.Finding(Diagnoser.Severity.INFO, "Bloat", "Many different entity tags", 0, 0,
					String.format(Locale.ROOT, "%s different tags on %s entities.", Diagnoser.count(g.distinctTags), Diagnoser.count(g.taggedEntities)),
					"Data packs that create a unique tag per entity (e.g. with an ID in the name) make selectors and saving slower."));
		}
		for (Item folder : f.folders) {
			if (folder.name().equals("data") && folder.value() > 200L * 1024 * 1024) {
				out.add(new Diagnoser.Finding(folder.value() > 500L * 1024 * 1024 ? Diagnoser.Severity.WARN : Diagnoser.Severity.INFO,
						"Bloat", "Large world data folder", 0, 0,
						String.format(Locale.ROOT, "`data/` is %s (scoreboard, command storage, maps, raids).", Diagnoser.bytes(folder.value())),
						"This folder is rewritten on every save. See the largest files below to find the culprit."));
			}
			if (folder.name().equals("playerdata") && folder.value() > 500L * 1024 * 1024) {
				out.add(new Diagnoser.Finding(Diagnoser.Severity.INFO, "Bloat", "Large player data", 0, 0,
						String.format(Locale.ROOT, "`playerdata/` is %s.", Diagnoser.bytes(folder.value())),
						"Usually items with huge NBT in inventories or ender chests. Very large player files slow down joining and saving."));
			}
		}
		return out;
	}

	/** Findings from the last run, for the main list. */
	static void addFindings(List<Diagnoser.Finding> out) {
		Result result = last;
		if (result != null) {
			out.addAll(result.findings());
		}
	}
}
