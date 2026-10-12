package eu.explorerseden.nicecontrolcenter.web;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Every command the server knows, for the panel's Commands page: who may use it (the lowest operator
 * level that passes its check, and the permission node when a permissions mod check names one), how
 * it's written, and which mod added it (found from the classes its code lives in).
 */
final class CommandList {
	private static final PermissionLevel[] LEVELS = PermissionLevel.values();
	private static final int MAX_USAGE = 12;

	private CommandList() {
	}

	static Map<String, Object> build(MinecraftServer server) {
		CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
		CommandSourceStack[] sources = new CommandSourceStack[LEVELS.length];
		for (int i = 0; i < LEVELS.length; i++) sources[i] = server.createCommandSourceStack().withPermission(LevelBasedPermissionSet.forLevel(LEVELS[i]));
		CommandSourceStack top = sources[LEVELS.length - 1];
		Map<String, ModInfo> mods = new HashMap<>();
		List<Map<String, Object>> out = new ArrayList<>();
		for (CommandNode<CommandSourceStack> node : dispatcher.getRoot().getChildren()) {
			if (!(node instanceof LiteralCommandNode)) continue;
			Map<String, Object> c = new LinkedHashMap<>();
			c.put("name", node.getName());
			// An alias (/tp → /teleport, /ncc → /nicecontrolcenter) is described by what it points to.
			CommandNode<CommandSourceStack> target = node.getRedirect() != null && node.getRedirect() != node && node.getRedirect().getName() != null
					&& !node.getRedirect().getName().isEmpty() ? node.getRedirect() : node;
			if (target != node) c.put("aliasOf", target.getName());
			int level = level(node, sources);
			c.put("level", level);
			String permission = permissionNode(node.getRequirement());
			if (permission != null) c.put("permission", permission);
			ModInfo mod = owner(target, mods);
			c.put("mod", mod.id);
			c.put("modName", mod.name);
			c.put("usage", usage(dispatcher, target, top, "/" + node.getName()));
			// Parts that need more (or another node) than the command itself, like /ncc web.
			List<Map<String, Object>> subs = new ArrayList<>();
			for (CommandNode<CommandSourceStack> child : target == node ? node.getChildren() : List.<CommandNode<CommandSourceStack>>of()) {
				if (!(child instanceof LiteralCommandNode)) continue;
				int childLevel = Math.max(level, level(child, sources));
				String childPermission = permissionNode(child.getRequirement());
				if (childLevel == level && (childPermission == null || childPermission.equals(permission))) continue;
				Map<String, Object> s = new LinkedHashMap<>();
				s.put("name", node.getName() + " " + child.getName());
				s.put("level", childLevel);
				if (childPermission != null) s.put("permission", childPermission);
				s.put("usage", usage(dispatcher, child, top, "/" + node.getName() + " " + child.getName()));
				subs.add(s);
			}
			if (!subs.isEmpty()) c.put("subs", subs);
			out.add(c);
		}
		out.sort((a, b) -> a.get("name").toString().compareTo(b.get("name").toString()));
		return Map.of("commands", out);
	}

	/** The lowest level whose source passes the check; -1 when no level does (players only, or a permission node only). */
	private static int level(CommandNode<CommandSourceStack> node, CommandSourceStack[] sources) {
		for (int i = 0; i < sources.length; i++) {
			try {
				if (node.canUse(sources[i])) return LEVELS[i].id();
			} catch (RuntimeException e) {
				// Checks that need a player.
			}
		}
		return -1;
	}

	/** The node a fabric-permissions-api check carries ("nicecontrolcenter.command.web"), read from the check itself. */
	private static String permissionNode(Predicate<CommandSourceStack> requirement) {
		if (requirement == null) return null;
		Class<?> type = requirement.getClass();
		if (!type.getName().contains("permissions")) return null;
		try {
			for (Field f : type.getDeclaredFields()) {
				if (f.getType() != String.class) continue;
				f.setAccessible(true);
				Object v = f.get(requirement);
				if (v instanceof String s && s.contains(".")) return s;
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			// Not readable.
		}
		return null;
	}

	private static List<String> usage(CommandDispatcher<CommandSourceStack> dispatcher, CommandNode<CommandSourceStack> node, CommandSourceStack source, String prefix) {
		List<String> lines = new ArrayList<>();
		try {
			if (node.getCommand() != null) lines.add(prefix);
			for (String u : dispatcher.getSmartUsage(node, source).values()) {
				if (lines.size() >= MAX_USAGE) break;
				lines.add(prefix + " " + u);
			}
		} catch (RuntimeException e) {
			// Usage stays empty.
		}
		if (lines.isEmpty()) lines.add(prefix);
		return lines;
	}

	private record ModInfo(String id, String name) {
	}

	/** The mod whose classes hold the command's code (the first one found in the tree). */
	private static ModInfo owner(CommandNode<CommandSourceStack> node, Map<String, ModInfo> cache) {
		String host = host(node, 0);
		if (host == null) host = hostOf(node.getRequirement());
		if (host == null) return new ModInfo("unknown", "Other");
		if (host.startsWith("net.minecraft.")) return new ModInfo("minecraft", "Minecraft");
		String path = host.replace('.', '/') + ".class";
		String pkg = host.contains(".") ? host.substring(0, host.lastIndexOf('.')) : host;
		ModInfo known = cache.get(pkg);
		if (known != null) return known;
		ModInfo found = new ModInfo("unknown", "Other");
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			try {
				if (mod.findPath(path).isPresent()) {
					// Fabric API's modules count as Fabric API.
					ModContainer top = mod;
					while (top.getContainingMod().isPresent()) top = top.getContainingMod().get();
					found = new ModInfo(top.getMetadata().getId(), top.getMetadata().getName());
					break;
				}
			} catch (RuntimeException e) {
				// Next mod.
			}
		}
		cache.put(pkg, found);
		return found;
	}

	private static String host(CommandNode<CommandSourceStack> node, int depth) {
		String h = hostOf(node.getCommand());
		if (h != null || depth > 6) return h;
		for (CommandNode<CommandSourceStack> child : node.getChildren()) {
			h = host(child, depth + 1);
			if (h != null) return h;
		}
		return null;
	}

	/** "com.example.FooCommand$$Lambda/0x…" → "com.example.FooCommand"; Brigadier's and the permission API's own classes say nothing. */
	private static String hostOf(Object code) {
		if (code == null) return null;
		String n = code.getClass().getName();
		int lambda = n.indexOf("$$Lambda");
		if (lambda > 0) n = n.substring(0, lambda);
		int inner = n.indexOf('$');
		if (inner > 0) n = n.substring(0, inner);
		if (n.startsWith("com.mojang.brigadier.") || n.startsWith("me.lucko.fabric.api.permissions.") || n.startsWith("java.")) return null;
		return n;
	}
}
