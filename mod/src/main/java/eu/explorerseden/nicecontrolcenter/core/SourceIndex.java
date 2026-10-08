package eu.explorerseden.nicecontrolcenter.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.resources.Resource;

/** Works out which data pack or mod something belongs to, and what to call it. */
public final class SourceIndex {
	public enum Kind {
		DATAPACK, MOD, VANILLA, COMMANDS
	}

	public record Source(String id, String name, Kind kind) {
	}

	public static final Source VANILLA = new Source("vanilla", "Minecraft", Kind.VANILLA);
	public static final Source COMMANDS = new Source("commands", "Commands (chat, console, command blocks)", Kind.COMMANDS);

	private static final Map<String, Source> packs = new ConcurrentHashMap<>();
	private static final Map<String, String> packByFunction = new ConcurrentHashMap<>();
	private static final Map<String, Source> namespaces = new ConcurrentHashMap<>();
	/** Which enabled data pack adds content under a namespace (worldgen, functions, …). */
	private static final Map<String, Source> packNamespaces = new ConcurrentHashMap<>();
	/** Which mod ships data under a namespace that isn't its mod id (Warping Wonders: mr_warping_wonders → wawo). */
	private static volatile Map<String, Source> modNamespaces;

	private SourceIndex() {
	}

	private static volatile boolean ready;

	/** True once the enabled packs have been read at least once. */
	public static boolean ready() {
		return ready;
	}

	/** Re-reads the enabled packs. Call on the server thread after start and after /reload. */
	public static void reload(MinecraftServer server) {
		packs.clear();
		packByFunction.clear();
		namespaces.clear();
		packNamespaces.clear();
		for (Pack pack : server.getPackRepository().getSelectedPacks()) {
			String id = pack.getId();
			Source source = describePack(id, pack.getTitle().getString());
			packs.put(id, source);
			if (source.kind() != Kind.DATAPACK) {
				continue;
			}
			try (Stream<PackResources> resources = pack.open()) {
				resources.forEach(r -> {
					try (r) {
						for (String ns : r.getNamespaces(PackType.SERVER_DATA)) {
							if (!ns.equals("minecraft")) {
								packNamespaces.putIfAbsent(ns, source);
							}
						}
					}
				});
			} catch (RuntimeException e) {
				// A broken pack shouldn't stop the index; its namespaces just show up by name.
			}
		}
		ready = true;
	}

	private static Source describePack(String id, String title) {
		if (id.equals("vanilla") || id.startsWith("minecraft") || id.equals("feature/") || id.startsWith("feature/")) {
			return new Source(id, title.isBlank() ? "Minecraft" : "Minecraft (" + title + ")", Kind.VANILLA);
		}
		Optional<ModContainer> mod = FabricLoader.getInstance().getModContainer(id);
		if (mod.isPresent()) {
			return new Source(id, mod.get().getMetadata().getName(), Kind.MOD);
		}
		if (id.equals("fabric") || id.startsWith("fabric-")) {
			return new Source(id, "Fabric mods", Kind.MOD);
		}
		String name = title.isBlank() ? id : title;
		if (name.startsWith("file/")) {
			name = name.substring(5);
		}
		if (name.endsWith(".zip")) {
			name = name.substring(0, name.length() - 4);
		}
		return new Source(id, name, Kind.DATAPACK);
	}

	/** Pack id that provides the given function file (the topmost one wins, like the game does). Server thread only. */
	public static String packForFunction(MinecraftServer server, Identifier function) {
		if (function.equals(LiveBucket.DIRECT_COMMANDS)) {
			return null;
		}
		String key = function.toString();
		String cached = packByFunction.get(key);
		if (cached != null) {
			return cached.isEmpty() ? null : cached;
		}
		String pack = "";
		try {
			List<Resource> stack = server.getResourceManager().getResourceStack(
					Identifier.fromNamespaceAndPath(function.getNamespace(), "function/" + function.getPath() + ".mcfunction"));
			if (!stack.isEmpty()) {
				pack = stack.get(stack.size() - 1).sourcePackId();
			}
		} catch (RuntimeException e) {
			// Resource manager is being swapped during a reload; try again next second.
			return null;
		}
		packByFunction.put(key, pack);
		return pack.isEmpty() ? null : pack;
	}

	public static Source pack(String packId) {
		if (packId == null) {
			return COMMANDS;
		}
		Source source = packs.get(packId);
		if (source != null) {
			return source;
		}
		return describePack(packId, packId);
	}

	/** Who adds content with this namespace (entity types, block entity types). */
	public static Source namespace(String namespace) {
		return namespaces.computeIfAbsent(namespace, ns -> {
			if (ns.equals("minecraft")) {
				return VANILLA;
			}
			Optional<ModContainer> mod = FabricLoader.getInstance().getModContainer(ns);
			if (mod.isPresent()) {
				return new Source(ns, mod.get().getMetadata().getName(), Kind.MOD);
			}
			Source pack = packNamespaces.get(ns);
			if (pack != null) {
				return pack;
			}
			Source owner = modNamespaces().get(ns);
			return owner != null ? owner : new Source(ns, ns, Kind.MOD);
		});
	}

	/** data/<namespace> folders inside the mod jars. Mods don't change while running, so this is read once. */
	private static Map<String, Source> modNamespaces() {
		Map<String, Source> cached = modNamespaces;
		if (cached != null) {
			return cached;
		}
		Map<String, Source> result = new HashMap<>();
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			String id = mod.getMetadata().getId();
			if (id.equals("minecraft") || id.equals("java")) {
				continue;
			}
			Source source = new Source(id, mod.getMetadata().getName(), Kind.MOD);
			for (Path root : mod.getRootPaths()) {
				Path data = root.resolve("data");
				if (!Files.isDirectory(data)) {
					continue;
				}
				try (Stream<Path> folders = Files.list(data)) {
					folders.filter(Files::isDirectory).forEach(folder -> {
						String ns = folder.getFileName().toString().replace("/", "");
						if (!ns.equals("minecraft")) {
							result.putIfAbsent(ns, source);
						}
					});
				} catch (IOException e) {
					// Unreadable jar; its namespaces just show up by name.
				}
			}
		}
		modNamespaces = result;
		return result;
	}

	/** A data pack or mod that really uses this namespace, or null. Used to guess where scoreboard names come from. */
	public static Source known(String namespace) {
		if (namespace == null || namespace.isEmpty()) {
			return null;
		}
		Source pack = packNamespaces.get(namespace);
		if (pack != null) {
			return pack;
		}
		return FabricLoader.getInstance().getModContainer(namespace)
				.map(mod -> new Source(namespace, mod.getMetadata().getName(), Kind.MOD)).orElseGet(() -> modNamespaces().get(namespace));
	}

	public static Source mod(String modId) {
		if (modId.equals("minecraft")) {
			return VANILLA;
		}
		return FabricLoader.getInstance().getModContainer(modId)
				.map(mod -> new Source(modId, mod.getMetadata().getName(), Kind.MOD))
				.orElse(new Source(modId, modId, Kind.MOD));
	}
}
