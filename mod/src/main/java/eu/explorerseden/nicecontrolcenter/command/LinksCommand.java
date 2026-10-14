package eu.explorerseden.nicecontrolcenter.command;

import com.mojang.brigadier.CommandDispatcher;
import eu.explorerseden.nicecontrolcenter.Links;
import eu.explorerseden.nicecontrolcenter.core.Plop;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.permissions.PermissionLevel;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.CommandNode;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * /info: a clickable link to the panel's public info page, plus the server's own link commands (like
 * /website or /discord, with aliases), all set in the panel. Own commands follow the
 * file while the server runs: removed ones go, new ones come, and players get the new command list.
 */
public final class LinksCommand {
	public static final String PERMISSION_USE = "nicecontrolcenter.links.use";
	/** Own link commands registered in the current dispatcher, with what they run (to tell them from other mods'). */
	private static final Map<String, Command<CommandSourceStack>> registered = new HashMap<>();
	private static long modified = -1;
	/** A new dispatcher waits for the own links until the next check, after every mod has added its commands. */
	private static boolean pending;

	private LinksCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		// A new dispatcher (server start or /reload): nothing of ours is in it yet. The own links come a moment
		// later, so a name another mod registers after us is seen as taken.
		registered.clear();
		pending = true;
		dispatcher.register(Commands.literal("info")
				.requires(Permissions.require(PERMISSION_USE, PermissionLevel.ALL))
				.executes(ctx -> {
					Links.Settings l = links();
					if (!l.infoOn || l.info.isEmpty()) return none(ctx.getSource(), "There's no info page right now.");
					return send(ctx.getSource(), l.infoText.isEmpty() ? "" : l.infoText + " ", l.infoLabel, l.info);
				}));
	}

	/** Every few seconds: when the panel changed the own links, the commands change with them. */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 100 != 40) return;
		Path f = file();
		long now;
		try {
			now = Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() : 0;
		} catch (IOException e) {
			return;
		}
		if (now == modified && !pending) return;
		modified = now;
		pending = false;
		CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
		List<Links.Custom> custom = links().custom;
		Set<String> wanted = new HashSet<>();
		for (Links.Custom c : custom) wanted.addAll(c.names());
		boolean changed = false;
		for (String name : new ArrayList<>(registered.keySet())) {
			if (wanted.contains(name)) continue;
			// Only if it's still ours (another mod may have added to the same name).
			CommandNode<CommandSourceStack> node = dispatcher.getRoot().getChild(name);
			if (node != null && node.getCommand() == registered.get(name)) remove(dispatcher.getRoot(), name);
			registered.remove(name);
			changed = true;
		}
		int before = registered.size();
		addCustom(dispatcher, custom);
		changed |= registered.size() != before;
		if (changed) for (ServerPlayer p : server.getPlayerList().getPlayers()) server.getCommands().sendCommands(p);
	}

	/** Registers the own link commands that aren't there yet; names other commands already use are left alone. */
	private static void addCustom(CommandDispatcher<CommandSourceStack> dispatcher, List<Links.Custom> custom) {
		for (Links.Custom c : custom) {
			for (String name : c.names()) {
				if (registered.containsKey(name)) continue;
				if (dispatcher.getRoot().getChild(name) != null) {
					NiceControlCenter.LOGGER.warn("The link command /{} isn't added: another command has that name", name);
					continue;
				}
				Command<CommandSourceStack> run = ctx -> custom(ctx.getSource(), name);
				dispatcher.register(Commands.literal(name)
						.requires(Permissions.require(PERMISSION_USE, PermissionLevel.ALL))
						.executes(run));
				registered.put(name, run);
			}
		}
	}

	/** Answers an own link command with what the file says now. */
	private static int custom(CommandSourceStack source, String name) {
		for (Links.Custom c : links().custom) {
			if (!c.names().contains(name)) continue;
			String label = c.label.isEmpty() ? c.url.replaceFirst("(?i)^https?://", "").replaceFirst("/$", "") : c.label;
			return send(source, c.text.isEmpty() ? "" : c.text + " ", label, c.url);
		}
		return none(source, "That link is gone.");
	}

	/** Takes a command out of the dispatcher (brigadier has no way to do that itself). */
	private static void remove(CommandNode<CommandSourceStack> root, String name) {
		for (String field : List.of("children", "literals", "arguments")) {
			try {
				Field f = CommandNode.class.getDeclaredField(field);
				f.setAccessible(true);
				((Map<?, ?>) f.get(root)).remove(name);
			} catch (ReflectiveOperationException | RuntimeException e) {
				NiceControlCenter.LOGGER.warn("Couldn't remove the link command /{}: {}", name, e.toString());
			}
		}
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(Links.FILE);
	}

	private static Links.Settings links() {
		return Links.read(file());
	}

	private static int none(CommandSourceStack source, String text) {
		source.sendFailure(Component.literal(text));
		if (source.getPlayer() != null) Plop.play(source.getPlayer());
		return 0;
	}

	private static int send(CommandSourceStack source, String lead, String label, String url) {
		URI uri;
		try {
			uri = URI.create(url);
		} catch (IllegalArgumentException e) {
			return none(source, "The link set in the panel isn't a web address.");
		}
		Component link = Component.literal(label).withStyle(st -> st.withColor(ChatFormatting.AQUA).withUnderlined(true)
				.withClickEvent(new ClickEvent.OpenUrl(uri))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Open " + url))));
		source.sendSuccess(() -> Component.literal(lead).withStyle(ChatFormatting.GOLD).append(link), false);
		if (source.getPlayer() != null) Plop.play(source.getPlayer());
		return 1;
	}
}
