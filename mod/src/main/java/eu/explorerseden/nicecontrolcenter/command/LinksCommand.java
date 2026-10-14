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

import java.net.URI;

/** /info and /website: clickable links to the panel's public info page and the server's website (set in the panel). */
public final class LinksCommand {
	public static final String PERMISSION_USE = "nicecontrolcenter.links.use";

	private LinksCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("info")
				.requires(Permissions.require(PERMISSION_USE, PermissionLevel.ALL))
				.executes(ctx -> {
					Links.Settings l = links();
					if (!l.infoOn || l.info.isEmpty()) return none(ctx.getSource(), "There's no info page right now.");
					return send(ctx.getSource(), "Everything about how this server works: ", "Server info", l.info);
				}));
		dispatcher.register(Commands.literal("website")
				.requires(Permissions.require(PERMISSION_USE, PermissionLevel.ALL))
				.executes(ctx -> {
					Links.Settings l = links();
					if (l.website.isEmpty()) return none(ctx.getSource(), "There's no website set.");
					return send(ctx.getSource(), "Our website: ", l.website.replaceFirst("(?i)^https?://", "").replaceFirst("/$", ""), l.website);
				}));
	}

	private static Links.Settings links() {
		return Links.read(FabricLoader.getInstance().getConfigDir().resolve(Links.FILE));
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
