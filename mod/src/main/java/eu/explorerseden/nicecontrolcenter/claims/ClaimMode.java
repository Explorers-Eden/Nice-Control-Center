package eu.explorerseden.nicecontrolcenter.claims;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import eu.explorerseden.nicecontrolcenter.core.Plop;

/**
 * Claim mode: entered from the claim menu or with /claim mode. The player gets the claim tool (a
 * marked golden hoe) in their hand; whatever was there moves to a free slot, or is kept by the mod
 * when the inventory is full. Leaving claim mode takes every claim tool away again and gives the kept
 * item back. The tool never stays outside claim mode: dropped tools vanish (and end claim mode), tools
 * put in a chest are removed when it's closed, and players who join with one lose it.
 */
public final class ClaimMode {
	private static final String MARKER = "nicecontrolcenter:claim_tool";
	private static final CompoundTag MARKER_TAG = new CompoundTag();

	static {
		MARKER_TAG.putBoolean(MARKER, true);
	}

	private ClaimMode() {
	}

	public static boolean active(ServerPlayer player) {
		return ClaimStore.data().claimMode.containsKey(player.getStringUUID());
	}

	public static boolean isTool(ItemStack stack) {
		if (stack.isEmpty()) return false;
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.matchedBy(MARKER_TAG);
	}

	public static ItemStack tool() {
		Item item = BuiltInRegistries.ITEM.getValue(Identifier.tryParse(ClaimStore.settings().toolItem));
		if (item == null || item == Items.AIR) item = Items.GOLDEN_HOE;
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Claim tool").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GOLD)));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
				lore("Right-click a block: first corner, then the opposite one"),
				lore("Right-click a corner of your claim: move it"),
				lore("Sneak + right-click: claim menu"),
				lore("/claim mode to put it away"))));
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
		CustomData.set(DataComponents.CUSTOM_DATA, stack, MARKER_TAG.copy());
		return stack;
	}

	private static Component lore(String text) {
		return Component.literal(text).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY));
	}

	public static void toggle(ServerPlayer player) {
		if (active(player)) exit(player, null);
		else enter(player);
	}

	public static void enter(ServerPlayer player) {
		if (!ClaimStore.enabled()) {
			player.sendSystemMessage(Component.literal("Claims are off on this server.").withStyle(ChatFormatting.RED));
			Plop.play(player);
			return;
		}
		if (active(player)) {
			player.sendSystemMessage(Component.literal("You're already in claim mode.").withStyle(ChatFormatting.GRAY));
			Plop.play(player);
			return;
		}
		if (player.isSpectator()) return;
		Inventory inv = player.getInventory();
		ItemStack held = inv.getSelectedItem();
		String kept = "";
		if (!held.isEmpty()) {
			int free = inv.getFreeSlot();
			if (free >= 0) inv.setItem(free, held.copy());
			else kept = encode(player, held);
		}
		inv.setItem(inv.getSelectedSlot(), tool());
		ClaimStore.data().claimMode.put(player.getStringUUID(), kept);
		ClaimStore.saveNow();
		ClaimVisuals.show(player, null);
		player.sendSystemMessage(Component.literal("Claim mode: ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal("right-click two opposite corners to claim land, or a corner of your claim to move it. "
						+ "Sneak + right-click opens the claim menu. ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal("You have " + ClaimStore.data().available(player.getStringUUID()) + " claim blocks.").withStyle(ChatFormatting.YELLOW)));
		Plop.play(player);
	}

	/** Leaves claim mode: takes the tools, gives the kept item back. {@code why} is shown when not null. */
	public static void exit(ServerPlayer player, String why) {
		String kept = ClaimStore.data().claimMode.remove(player.getStringUUID());
		removeTools(player);
		ClaimTool.clear(player);
		if (kept != null && !kept.isEmpty()) {
			ItemStack back = decode(player, kept);
			if (!back.isEmpty() && !player.getInventory().add(back)) dropAtFeet(player, back);
		}
		if (kept != null) ClaimStore.saveNow();
		if (kept != null) {
			player.sendSystemMessage(Component.literal(why == null ? "Claim mode off." : why).withStyle(ChatFormatting.GRAY));
			Plop.play(player);
		}
	}

	/** Claims were switched off: everyone leaves claim mode. */
	public static void endAll() {
		MinecraftServer server = ClaimStore.server();
		if (server == null) return;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (active(p)) exit(p, "Claims were switched off.");
		}
	}

	/** Takes every claim tool from the player's inventory and cursor. */
	public static boolean removeTools(ServerPlayer player) {
		boolean found = false;
		Inventory inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (isTool(inv.getItem(i))) {
				inv.setItem(i, ItemStack.EMPTY);
				found = true;
			}
		}
		AbstractContainerMenu menu = player.containerMenu;
		if (menu != null && isTool(menu.getCarried())) {
			menu.setCarried(ItemStack.EMPTY);
			found = true;
		}
		if (found) player.inventoryMenu.sendAllDataToRemote();
		return found;
	}

	private static boolean hasTool(ServerPlayer player) {
		Inventory inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (isTool(inv.getItem(i))) return true;
		return player.containerMenu != null && isTool(player.containerMenu.getCarried());
	}

	/** Every second: players in claim mode must still have the tool; nobody else may have one. */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0) return;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (active(p)) {
				if (!hasTool(p)) exit(p, "Claim mode off (the claim tool was put away).");
				else ClaimTool.statusBar(p);
			} else if (removeTools(p)) {
				p.sendSystemMessage(Component.literal("The claim tool only works in claim mode (/claim mode).").withStyle(ChatFormatting.GRAY));
				Plop.play(p);
			}
		}
	}

	/** At join: claim mode from before a crash ends (the kept item comes back), stray tools go. */
	public static void joined(ServerPlayer player) {
		if (active(player)) exit(player, null);
		else removeTools(player);
	}

	private static void dropAtFeet(ServerPlayer player, ItemStack stack) {
		net.minecraft.world.entity.item.ItemEntity item = new net.minecraft.world.entity.item.ItemEntity(player.level(), player.getX(), player.getY(), player.getZ(), stack);
		item.setNoPickUpDelay();
		player.level().addFreshEntity(item);
	}

	private static String encode(ServerPlayer player, ItemStack stack) {
		try {
			return ItemStack.CODEC.encodeStart(player.registryAccess().createSerializationContext(JsonOps.INSTANCE), stack).getOrThrow().toString();
		} catch (RuntimeException e) {
			// Can't keep it: better in a free spot on the ground than gone.
			NiceControlCenter.LOGGER.warn("Claims: couldn't keep {}'s item for claim mode: {}", player.getPlainTextName(), e.toString());
			dropAtFeet(player, stack.copy());
			return "";
		}
	}

	private static ItemStack decode(ServerPlayer player, String json) {
		try {
			return ItemStack.CODEC.parse(player.registryAccess().createSerializationContext(JsonOps.INSTANCE), JsonParser.parseString(json)).getOrThrow();
		} catch (RuntimeException e) {
			NiceControlCenter.LOGGER.warn("Claims: couldn't give {} back their item from claim mode: {} ({})", player.getPlainTextName(), e.toString(), json);
			return ItemStack.EMPTY;
		}
	}
}
