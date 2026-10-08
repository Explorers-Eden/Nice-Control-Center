package eu.explorerseden.nicecontrolcenter.log;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import eu.explorerseden.nicecontrolcenter.NiceControlCenter;

/** Runs a command from the dashboard console with full rights, like the server console, and collects its output. */
public final class ConsoleRunner {
	private ConsoleRunner() {
	}

	/** Server thread. Returns the lines the command printed. */
	public static List<String> run(MinecraftServer server, String command) {
		return run(server, command, "Dashboard");
	}

	/** Output lines and whether the command succeeded. */
	public record Result(List<String> lines, boolean ok) {
	}

	/** Like {@link #run(MinecraftServer, String)}, logged as run by {@code who}. */
	public static List<String> run(MinecraftServer server, String command, String who) {
		return runChecked(server, command, who).lines();
	}

	/**
	 * Runs a command and reports whether it worked. A command that doesn't even parse (unknown or
	 * wrong syntax) never reaches the result callback, so it counts as failed.
	 */
	public static Result runChecked(MinecraftServer server, String command, String who) {
		String text = command.strip();
		if (text.startsWith("/")) {
			text = text.substring(1);
		}
		List<String> output = new ArrayList<>();
		if (text.isEmpty()) {
			return new Result(output, true);
		}
		CommandSource capture = new CommandSource() {
			@Override
			public void sendSystemMessage(Component message) {
				output.add(message.getString());
			}

			@Override
			public boolean acceptsSuccess() {
				return true;
			}

			@Override
			public boolean acceptsFailure() {
				return true;
			}

			@Override
			public boolean shouldInformAdmins() {
				// Like the console: operators see "[Server: …]" for commands that change things.
				return true;
			}
		};
		NiceControlCenter.LOGGER.info("{} ran: /{}", who, text);
		boolean[] result = {false, false};
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSource(capture).withCallback((success, value) -> {
			result[0] = true;
			result[1] |= success;
		}), text);
		return new Result(output, result[0] && result[1]);
	}
}
