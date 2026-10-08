package eu.explorerseden.nicecontrolcenter.sampler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/**
 * Finds the mod that owns a stack frame. Used only by the sampler thread.
 *
 * <p>Classes are matched by package against the classes each mod jar contains. Mixin code merged
 * into vanilla classes is recognised by the handler method names, which Fabric's Mixin prefixes
 * with the mod id (e.g. {@code handler$zza000$lithium$onTick}).
 */
public final class ModResolver {
	public static final String JVM = "(jvm)";
	public static final String MINECRAFT = "minecraft";
	public static final String SELF = "nicecontrolcenter";

	private static final Pattern MIXIN_HANDLER = Pattern.compile("\\$[a-z]{3}\\d{3}\\$([a-z][a-z0-9_-]*)\\$");
	/** JDK and mixin plumbing; skipped so the code above or below them gets the sample. */
	private static final String[] JVM_PREFIXES = {"java.", "javax.", "jdk.", "sun.", "com.sun.", "com.llamalad7.mixinextras.",
			"org.spongepowered.", "org.objectweb."};
	private static final String[] MINECRAFT_PREFIXES = {
			"net.minecraft.", "com.mojang.", "it.unimi.dsi.", "io.netty.", "com.google.", "org.apache.",
			"org.slf4j.", "org.lwjgl.", "joptsimple.", "oshi.", "org.joml.", "com.ibm.icu.", "org.jetbrains."};

	private final Map<String, String> modByPackage = new HashMap<>();
	private final Map<String, String> modByClass = new HashMap<>();
	private final Map<String, String> modByMethod = new HashMap<>();
	private final Set<String> modIds = new HashSet<>();
	private boolean indexed;

	/** Mod id for a frame, {@link #JVM} for JDK frames, {@link #MINECRAFT} for the game and its libraries. */
	public String owner(StackTraceElement frame) {
		String method = frame.getMethodName();
		if (method.indexOf('$') >= 0) {
			if (method.contains("mixinextras$")) {
				// MixinExtras bridges between a wrapper and the original code; not anyone's own work.
				return JVM;
			}
			String fromMixin = mixinOwner(method);
			if (fromMixin != null) {
				return fromMixin;
			}
		}
		return classOwner(frame.getClassName());
	}

	/** True for mixin wrappers that just pass through to the original code (they sit on the stack for the whole call). */
	static boolean isPassThrough(StackTraceElement frame) {
		String method = frame.getMethodName();
		return method.startsWith("wrapMethod$") || method.startsWith("wrapOperation$") || method.startsWith("wrapWithCondition$");
	}

	static boolean isMixinHandler(StackTraceElement frame) {
		return MIXIN_HANDLER.matcher(frame.getMethodName()).find();
	}

	private String mixinOwner(String method) {
		String cached = modByMethod.get(method);
		if (cached != null) {
			return cached.isEmpty() ? null : cached;
		}
		ensureIndexed();
		String owner = "";
		Matcher matcher = MIXIN_HANDLER.matcher(method);
		if (matcher.find()) {
			owner = topLevel(matcher.group(1));
		} else {
			// @Unique members are conventionally named "modid$name".
			int dollar = method.indexOf('$');
			String prefix = method.substring(0, dollar);
			if (modIds.contains(prefix)) {
				owner = topLevel(prefix);
			}
		}
		if (modByMethod.size() < 50_000) {
			modByMethod.put(method, owner);
		}
		return owner.isEmpty() ? null : owner;
	}

	private String classOwner(String className) {
		String cached = modByClass.get(className);
		if (cached != null) {
			return cached;
		}
		String owner = resolveClass(className);
		if (modByClass.size() < 100_000) {
			modByClass.put(className, owner);
		}
		return owner;
	}

	private String resolveClass(String className) {
		for (String prefix : JVM_PREFIXES) {
			if (className.startsWith(prefix)) {
				return JVM;
			}
		}
		if (className.startsWith("eu.explorerseden.nicecontrolcenter.")) {
			return SELF;
		}
		for (String prefix : MINECRAFT_PREFIXES) {
			if (className.startsWith(prefix)) {
				return MINECRAFT;
			}
		}
		ensureIndexed();
		int dot = className.lastIndexOf('.');
		String pkg = dot < 0 ? "" : className.substring(0, dot);
		while (!pkg.isEmpty()) {
			String mod = modByPackage.get(pkg);
			if (mod != null) {
				return mod;
			}
			int parent = pkg.lastIndexOf('.');
			pkg = parent < 0 ? "" : pkg.substring(0, parent);
		}
		if (className.startsWith("net.fabricmc.loader.")) {
			return "fabricloader";
		}
		return MINECRAFT;
	}

	private String topLevel(String modId) {
		ModContainer mod = FabricLoader.getInstance().getModContainer(modId).orElse(null);
		while (mod != null && mod.getContainingMod().isPresent()) {
			mod = mod.getContainingMod().get();
		}
		return mod == null ? modId : mod.getMetadata().getId();
	}

	/** Maps every package found in a mod's jar to the mod (nested library jars map to the mod that ships them). */
	private void ensureIndexed() {
		if (indexed) {
			return;
		}
		indexed = true;
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			String id = mod.getMetadata().getId();
			modIds.add(id);
			if (id.equals("minecraft") || id.equals("java") || id.equals(SELF)) {
				continue;
			}
			String owner = topLevel(id);
			for (Path root : mod.getRootPaths()) {
				try (Stream<Path> files = Files.walk(root)) {
					files.filter(path -> path.toString().endsWith(".class")).forEach(path -> {
						Path parent = root.relativize(path).getParent();
						if (parent != null) {
							String pkg = parent.toString().replace('/', '.').replace('\\', '.');
							modByPackage.putIfAbsent(pkg, owner);
						}
					});
				} catch (IOException | RuntimeException e) {
					// Unreadable root (e.g. a directory that went away); its classes fall back to "minecraft".
				}
			}
		}
	}
}
