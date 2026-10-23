package eu.explorerseden.nicecontrolcenter.market;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import eu.explorerseden.nicecontrolcenter.Market;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import eu.explorerseden.nicecontrolcenter.core.Plop;

/**
 * The market while the server runs: the world file in memory, the settings from the panel, expiry, and
 * turning stacks into the stored form and back (all components kept, so custom models, names and
 * enchantments survive the trip). Server thread.
 */
public final class MarketStore {
	private static Market.Data data = new Market.Data().fill();
	private static Market.Settings settings = new Market.Settings().fill();
	private static long settingsModified = -1;
	private static Path file;
	private static MinecraftServer server;
	private static boolean dirty;

	private MarketStore() {
	}

	public static Market.Data data() {
		return data;
	}

	public static Market.Settings settings() {
		return settings;
	}

	public static boolean enabled() {
		return settings.enabled;
	}

	public static MinecraftServer server() {
		return server;
	}

	public static void register() {
		net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((d, a, e) -> MarketCommand.register(d));
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(MarketStore::load);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			save();
			eu.explorerseden.nicecontrolcenter.core.AsyncFiles.flush();
			file = null;
			server = null;
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(MarketStore::tick);
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((h, sender, s) -> {
			if (server == null || !settings.enabled) return;
			Market.Mailbox m = data.mail.get(h.player.getStringUUID());
			if (m != null && !m.isEmpty()) {
				String what = !m.parcels.isEmpty() && !m.orders.isEmpty() ? "Items and purchases from the website are"
						: !m.orders.isEmpty() ? (m.orders.size() == 1 ? "A purchase from the website is" : "Purchases from the website are") : "Items are";
				h.player.sendSystemMessage(Component.literal(what + " waiting in your market mailbox: /market").withStyle(ChatFormatting.GOLD));
				Plop.play(h.player);
			}
		});
	}

	private static Path settingsFile() {
		return FabricLoader.getInstance().getConfigDir().resolve(Market.FILE);
	}

	private static void load(MinecraftServer s) {
		server = s;
		file = s.getWorldPath(LevelResource.ROOT).resolve(Market.DATA_FILE);
		try {
			data = Market.readData(file);
		} catch (IOException | RuntimeException e) {
			NiceControlCenter.LOGGER.error("Couldn't read {}; the market starts empty and the file is kept as .broken", file, e);
			try {
				Files.copy(file, file.resolveSibling(file.getFileName() + ".broken"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
				// Nothing more to do.
			}
			data = new Market.Data().fill();
		}
		settingsModified = -1;
		reloadSettings();
		dirty = false;
	}

	private static void tick(MinecraftServer s) {
		if (server == null) return;
		int tick = s.getTickCount();
		if (tick % 100 == 0) reloadSettings();
		if (tick % 1200 == 600 && data.expire(System.currentTimeMillis()) > 0) changed();
		if (dirty && tick % 100 == 50) save();
	}

	/** Reads the settings again if the panel changed them (also from the dashboard API, which works while paused). */
	public static void reloadSettings() {
		Path f = settingsFile();
		long now;
		try {
			now = eu.explorerseden.nicecontrolcenter.core.FileWatch.modified(f);
		} catch (IOException e) {
			return;
		}
		if (now == settingsModified) return;
		settingsModified = now;
		settings = Market.read(f);
	}

	public static void changed() {
		dirty = true;
	}

	/** Right away (handed to the file writer at once, on disk within moments): items players handed over must never be lost to a crash. */
	public static void saveNow() {
		dirty = true;
		save();
	}

	private static void save() {
		Path f = file;
		if (f == null || !dirty) return;
		// Turned into text here (the data only changes on this thread), written to disk in the background.
		eu.explorerseden.nicecontrolcenter.core.AsyncFiles.write(f, eu.explorerseden.nicecontrolcenter.Json.GSON.toJson(data));
		dirty = false;
	}

	// ---------------------------------------------------------------- items

	public static Item currency() {
		Item item = BuiltInRegistries.ITEM.getValue(Identifier.tryParse(settings.currency));
		return item == null || item == Items.AIR ? Items.DIAMOND : item;
	}

	public static String currencyName() {
		return new ItemStack(currency()).getHoverName().getString().toLowerCase(java.util.Locale.ROOT) + "s";
	}

	/** The stack (count 1) in the stored form, with what the web shows. */
	public static Market.Item store(ServerPlayer player, ItemStack stack) {
		ItemStack one = stack.copyWithCount(1);
		Market.Item item = new Market.Item();
		item.stack = ItemStack.CODEC.encodeStart(player.registryAccess().createSerializationContext(JsonOps.INSTANCE), one).getOrThrow().toString();
		item.id = BuiltInRegistries.ITEM.getKey(one.getItem()).toString();
		item.name = one.getHoverName().getString();
		item.maxStack = one.getMaxStackSize();
		ItemLore lore = one.get(DataComponents.LORE);
		if (lore != null) for (Component line : lore.lines()) item.lore.add(line.getString());
		for (var type : java.util.List.of(DataComponents.ENCHANTMENTS, DataComponents.STORED_ENCHANTMENTS)) {
			ItemEnchantments e = one.get(type);
			if (e != null) for (var entry : e.entrySet()) item.enchantments.add(Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString());
		}
		return item;
	}

	/** A stored item back as a stack of count (EMPTY when it can't be read any more, e.g. its mod is gone). */
	public static ItemStack stack(MinecraftServer s, Market.Item item, int count) {
		try {
			ItemStack one = ItemStack.CODEC.parse(s.registryAccess().createSerializationContext(JsonOps.INSTANCE), JsonParser.parseString(item.stack)).getOrThrow();
			return one.copyWithCount(count);
		} catch (RuntimeException e) {
			NiceControlCenter.LOGGER.warn("A market item can't be read any more ({}): {}", item.id, e.toString());
			return ItemStack.EMPTY;
		}
	}

	// ---------------------------------------------------------------- paying

	/** How much currency the player carries. */
	public static int carried(ServerPlayer player) {
		return count(player, new ItemStack(currency()));
	}

	/** What the player can spend: their balance, then what they carry. */
	public static long funds(ServerPlayer player) {
		return data.balance(player.getStringUUID()) + carried(player);
	}

	/**
	 * Pays cost: from the balance first, the rest from the currency the player carries. False (and nothing taken)
	 * when both together aren't enough.
	 */
	public static boolean pay(ServerPlayer player, long cost) {
		if (cost < 0) return false;
		String me = player.getStringUUID();
		long fromBalance = Math.min(cost, data.balance(me));
		long rest = cost - fromBalance;
		if (rest > carried(player)) return false;
		if (fromBalance > 0 && !data.debit(me, fromBalance)) return false;
		if (rest > 0 && !take(player, new ItemStack(currency()), (int) rest)) {
			data.credit(me, player.getPlainTextName(), fromBalance);
			return false;
		}
		return true;
	}

	/** How many items like this the player carries (same item, same components). */
	public static int count(ServerPlayer player, ItemStack like) {
		Inventory inv = player.getInventory();
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, like)) n += s.getCount();
		}
		return n;
	}

	/** Takes amount items like this from the player; false (and nothing taken) when they don't have that many. */
	public static boolean take(ServerPlayer player, ItemStack like, int amount) {
		if (count(player, like) < amount) return false;
		Inventory inv = player.getInventory();
		int left = amount;
		for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || !ItemStack.isSameItemSameComponents(s, like)) continue;
			int t = Math.min(left, s.getCount());
			s.shrink(t);
			left -= t;
		}
		player.inventoryMenu.sendAllDataToRemote();
		return true;
	}

	/** Gives up to amount items; returns how many didn't fit. */
	public static int give(ServerPlayer player, ItemStack like, int amount) {
		int left = amount;
		int max = Math.max(1, like.getMaxStackSize());
		while (left > 0) {
			int n = Math.min(max, left);
			ItemStack part = like.copyWithCount(n);
			player.getInventory().add(part);
			int given = n - part.getCount();
			left -= given;
			if (given < n) break;
		}
		player.inventoryMenu.sendAllDataToRemote();
		return left;
	}
}
