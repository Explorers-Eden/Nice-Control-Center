package eu.explorerseden.nicecontrolcenter.command;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;

import java.util.function.Predicate;

/**
 * Commands every player may use unless a permissions mod says no. Permissions.require(node, PermissionLevel.ALL)
 * means the same for players, but the game probes each command with a source that has no permissions at all, and
 * that fails even level 0: clients then count the command as needing permissions and ask before running it from
 * a dialog button or a chat link.
 */
public final class Everyone {
	private Everyone() {
	}

	public static Predicate<CommandSourceStack> unless(String node) {
		return source -> Permissions.getPermissionValue(source, node).orElse(true);
	}
}
