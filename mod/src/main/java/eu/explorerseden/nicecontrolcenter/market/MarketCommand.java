package eu.explorerseden.nicecontrolcenter.market;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import eu.explorerseden.nicecontrolcenter.core.Plop;

/** /market: the market menu. */
public final class MarketCommand {
	public static final String PERMISSION_USE = "nicecontrolcenter.market.use";

	private MarketCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("market")
				.requires(eu.explorerseden.nicecontrolcenter.command.Everyone.unless(PERMISSION_USE))
				.executes(ctx -> {
					var player = ctx.getSource().getPlayerOrException();
					if (!MarketStore.enabled()) {
						player.sendSystemMessage(Component.literal("The market is closed.").withStyle(ChatFormatting.RED));
						Plop.play(player);
						return 0;
					}
					MarketDialogs.home(player, null);
					return 1;
				}));
	}
}
