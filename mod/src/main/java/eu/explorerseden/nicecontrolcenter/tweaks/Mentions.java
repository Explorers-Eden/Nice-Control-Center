package eu.explorerseden.nicecontrolcenter.tweaks;

import eu.explorerseden.nicecontrolcenter.Tweaks;
import eu.explorerseden.nicecontrolcenter.core.Plop;
import net.fabricmc.fabric.api.message.v1.ServerMessageDecoratorEvent;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @mentions: "@Name" of an online player in chat (or in a Discord message relayed by the panel) plays the
 * plop for that player and tells them who mentioned them; optionally the mention is coloured in the message.
 */
public final class Mentions {
	private static final Pattern AT = Pattern.compile("@([A-Za-z0-9_]{3,16})");

	private Mentions() {
	}

	static void register() {
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, type) -> {
			if (TweaksControl.settings().chat.mentions) notify(sender.level().getServer(), message.signedContent(), sender.getPlainTextName(), sender);
		});
		ServerMessageDecoratorEvent.EVENT.register(ServerMessageDecoratorEvent.STYLING_PHASE, (sender, message) -> {
			Tweaks.Chat c = TweaksControl.settings().chat;
			if (!c.mentions || !c.highlight || sender == null) return message;
			return highlight(sender.level().getServer(), message, c.highlightColor);
		});
	}

	/** Online players mentioned in text (each once). */
	private static Set<ServerPlayer> mentioned(MinecraftServer server, String text) {
		Set<ServerPlayer> out = new LinkedHashSet<>();
		Matcher m = AT.matcher(text);
		while (m.find()) {
			String name = m.group(1).toLowerCase(Locale.ROOT);
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (p.getGameProfile().name().toLowerCase(Locale.ROOT).equals(name)) out.add(p);
			}
		}
		return out;
	}

	/** Plop and an action bar note for everyone mentioned (never for the sender themselves). */
	public static void notify(MinecraftServer server, String text, String from, ServerPlayer sender) {
		if (!TweaksControl.settings().chat.mentions) return;
		for (ServerPlayer p : mentioned(server, text)) {
			if (p == sender) continue;
			Plop.play(p);
			p.sendSystemMessage(Component.literal(from + " mentioned you").withStyle(ChatFormatting.GOLD), true);
		}
	}

	/** The text with its mentions coloured when that's switched on (for messages that aren't player chat). */
	public static Component decorate(MinecraftServer server, Component message) {
		Tweaks.Chat c = TweaksControl.settings().chat;
		return c.mentions && c.highlight ? highlight(server, message, c.highlightColor) : message;
	}

	/** The message with each @Name of an online player coloured (plain text in, so only simple messages). */
	private static Component highlight(MinecraftServer server, Component message, String color) {
		String text = message.getString();
		if (mentioned(server, text).isEmpty()) return message;
		int rgb = Integer.parseInt(color.substring(1), 16);
		MutableComponent out = Component.empty().withStyle(message.getStyle());
		Matcher m = AT.matcher(text);
		int at = 0;
		while (m.find()) {
			String name = m.group(1).toLowerCase(Locale.ROOT);
			boolean online = server.getPlayerList().getPlayers().stream().anyMatch(p -> p.getGameProfile().name().toLowerCase(Locale.ROOT).equals(name));
			if (!online) continue;
			out.append(Component.literal(text.substring(at, m.start())));
			out.append(Component.literal(m.group()).withStyle(st -> st.withColor(TextColor.fromRgb(rgb)).withBold(true)));
			at = m.end();
		}
		out.append(Component.literal(text.substring(at)));
		return out;
	}
}
