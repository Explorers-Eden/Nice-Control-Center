package eu.explorerseden.nicecontrolcenter.pets;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import eu.explorerseden.nicecontrolcenter.Pets;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Pack Mule's bag: a chest menu whose slots are saved to the owner's entry in the pets file on every change, so
 * a crash or a server stop with the bag open loses nothing. The bag is the player's: opening it needs the perk,
 * but its items stay when the pet or the perk goes.
 */
final class PetBag {
	private static final List<MenuType<ChestMenu>> TYPES = List.of(MenuType.GENERIC_9x1, MenuType.GENERIC_9x2, MenuType.GENERIC_9x3,
			MenuType.GENERIC_9x4, MenuType.GENERIC_9x5, MenuType.GENERIC_9x6);

	private PetBag() {
	}

	/** Opens the bag; null when it did, else why not. */
	static String open(ServerPlayer player) {
		Pets.Owned out = PetFollowers.shownOwned(player.getUUID());
		Pets.Owner owner = out == null ? null : PetStore.data().owners.get(player.getStringUUID());
		if (owner == null || !PetPerks.active(player, Pets.Perk.PACK_MULE)) return "Only a pet with Pack Mule that's out with you can carry a bag (not while you're AFK).";
		int rows = Math.max(1, Math.min(6, PetStore.settings().perks.bagRows));
		Runnable[] saver = new Runnable[1];
		SimpleContainer box = new SimpleContainer(rows * 9) {
			@Override
			public void setChanged() {
				super.setChanged();
				if (saver[0] != null) saver[0].run();
			}
		};
		// Items from a bag that had more rows, and saved items this game version can't read: back to the player,
		// or kept in the file, never thrown away.
		List<String> unreadable = new ArrayList<>();
		for (int i = 0; i < owner.bag.size(); i++) {
			String json = owner.bag.get(i);
			if (json.isEmpty()) continue;
			ItemStack stack = read(player, json);
			if (stack == null) unreadable.add(json);
			else if (i < box.getContainerSize()) box.setItem(i, stack);
			else giveBack(player, stack);
		}
		Runnable save = () -> {
			List<String> slots = new ArrayList<>();
			for (int i = 0; i < box.getContainerSize(); i++) slots.add(write(player, box.getItem(i)));
			slots.addAll(unreadable);
			while (!slots.isEmpty() && slots.get(slots.size() - 1).isEmpty()) slots.remove(slots.size() - 1);
			owner.bag = slots;
			PetStore.changed();
		};
		save.run();
		saver[0] = save;
		player.openMenu(new SimpleMenuProvider((id, inv, p) -> new ChestMenu(TYPES.get(rows - 1), id, inv, box, rows) {
			@Override
			public boolean stillValid(Player who) {
				return who.isAlive();
			}

			@Override
			public void removed(Player who) {
				super.removed(who);
				save.run();
			}
		}, Component.literal(PetStore.displayName(out) + "'s bag")));
		return null;
	}

	private static ItemStack read(ServerPlayer player, String json) {
		try {
			return ItemStack.CODEC.parse(player.registryAccess().createSerializationContext(JsonOps.INSTANCE), JsonParser.parseString(json)).result().orElse(null);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String write(ServerPlayer player, ItemStack stack) {
		if (stack.isEmpty()) return "";
		return ItemStack.CODEC.encodeStart(player.registryAccess().createSerializationContext(JsonOps.INSTANCE), stack).getOrThrow().toString();
	}

	private static void giveBack(ServerPlayer player, ItemStack stack) {
		player.getInventory().add(stack);
		if (stack.isEmpty()) return;
		ItemEntity e = new ItemEntity(player.level(), player.getX(), player.getY() + 0.3, player.getZ(), stack);
		e.setNoPickUpDelay();
		player.level().addFreshEntity(e);
	}
}
