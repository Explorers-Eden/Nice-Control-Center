package eu.explorerseden.nicecontrolcenter.pets;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Which items dropped because of a player (blocks they broke, mobs they killed, things they used on blocks or
 * entities), for the Fetch perk. While such an action runs, its player is the "cause"; every item entity
 * added meanwhile is theirs. Items thrown out of an inventory never are: throwing isn't one of those actions.
 * Server thread only; the items are forgotten with their entities.
 */
public final class PlayerDrops {
	/** "Nobody": pushed where drops must not count for an outer action (anyone's death drops). */
	private static final UUID NOBODY = new UUID(0, 0);
	private static final ArrayDeque<UUID> causes = new ArrayDeque<>();
	private static final Map<ItemEntity, UUID> owners = new WeakHashMap<>();

	private PlayerDrops() {
	}

	public static void push(ServerPlayer player) {
		causes.push(player == null ? NOBODY : player.getUUID());
	}

	public static void pushNobody() {
		causes.push(NOBODY);
	}

	public static void pop() {
		if (!causes.isEmpty()) causes.pop();
	}

	/** Runs the action with the player as cause (the pet's own mining, chopping and finds). */
	public static void during(ServerPlayer player, Runnable action) {
		push(player);
		try {
			action.run();
		} finally {
			pop();
		}
	}

	/** An entity is being added to a level (server thread). */
	public static void spawned(Entity entity) {
		if (causes.isEmpty() || !(entity instanceof ItemEntity item)) return;
		UUID who = causes.peek();
		if (!NOBODY.equals(who)) owners.put(item, who);
	}

	/** Whose doing the item is, or null. */
	public static UUID owner(ItemEntity item) {
		return owners.get(item);
	}

	/** End of every tick: a cause an exception left behind never lasts. */
	static void tick() {
		causes.clear();
	}
}
