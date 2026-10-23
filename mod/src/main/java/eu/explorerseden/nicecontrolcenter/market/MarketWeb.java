package eu.explorerseden.nicecontrolcenter.market;

import com.google.gson.JsonObject;
import eu.explorerseden.nicecontrolcenter.Market;
import eu.explorerseden.nicecontrolcenter.Pets;
import eu.explorerseden.nicecontrolcenter.claims.ClaimStore;
import eu.explorerseden.nicecontrolcenter.core.Plop;
import eu.explorerseden.nicecontrolcenter.pets.PetStore;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Buying on the website (through the panel, which already checked who the player is): paid from the
 * player's bank only. Items go to the mailbox, claim blocks and pets are theirs right away, command listings
 * wait in the mailbox until they're claimed in game (their commands need the player). Same checks as the
 * menus. Server thread.
 */
public final class MarketWeb {
	private MarketWeb() {
	}

	/** What the website shows a logged-in player: their bank, mailbox and recent sales. */
	public static Map<String, Object> account(MinecraftServer server, String uuid) {
		Map<String, Object> out = new LinkedHashMap<>();
		if (!validUuid(uuid)) return Map.of("error", "Unknown player.");
		Market.Data data = MarketStore.data();
		out.put("open", MarketStore.enabled());
		out.put("currency", MarketStore.settings().currency);
		out.put("currencyName", MarketStore.currencyName());
		out.put("balance", data.balance(uuid));
		out.put("online", server.getPlayerList().getPlayer(UUID.fromString(uuid)) != null);
		Market.Mailbox m = data.mail.get(uuid);
		List<Map<String, Object>> parcels = new ArrayList<>();
		List<Map<String, Object>> orders = new ArrayList<>();
		if (m != null) {
			for (Market.Parcel p : m.parcels) parcels.add(Map.of("name", p.item.name, "item", p.item.id, "amount", p.amount));
			for (Market.Order o : m.orders) orders.add(Map.of("name", o.name, "times", o.times, "bought", o.bought));
		}
		out.put("parcels", parcels);
		out.put("orders", orders);
		List<Map<String, Object>> sales = new ArrayList<>();
		for (int i = data.sales.size() - 1; i >= 0 && sales.size() < 20; i--) {
			Market.Sale s = data.sales.get(i);
			boolean bought = uuid.equals(s.buyer);
			if (!bought && !uuid.equals(s.seller)) continue;
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("role", bought ? "bought" : "sold");
			row.put("name", s.itemName);
			row.put("amount", s.amount);
			row.put("total", s.total);
			row.put("other", bought ? (s.sellerName == null ? MarketStore.settings().serverName : s.sellerName) : s.buyerName);
			row.put("time", s.time);
			sales.add(row);
		}
		out.put("sales", sales);
		if (ClaimStore.enabled()) {
			out.put("claimBlocks", ClaimStore.data().available(uuid));
			int left = ClaimStore.data().blocksLeft(uuid, MarketStore.settings().claimBlocksMax);
			out.put("claimBlocksLeft", left == Integer.MAX_VALUE ? -1 : left);
		}
		Map<String, Integer> bought = new LinkedHashMap<>();
		for (Market.CommandOffer o : MarketStore.settings().commandOffers) {
			int n = data.serverBought(o.id, uuid);
			if (n > 0) bought.put(o.id, n);
		}
		out.put("commandsBought", bought);
		Pets.Owner owner = PetStore.data().owners.get(uuid);
		out.put("pets", owner == null ? List.of() : owner.pets.stream().map(p -> p.pet).toList());
		return out;
	}

	/**
	 * A purchase: kind listing (id, amount in lots, price, per), claims (id, amount in packs, price), command
	 * (id, amount, price) or pet (id, price). Returns ok + message + the new balance, or error.
	 */
	public static Map<String, Object> buy(MinecraftServer server, JsonObject in) {
		String uuid = str(in, "uuid");
		String name = str(in, "name");
		if (!validUuid(uuid)) return error("Unknown player.");
		if (name.isEmpty()) name = uuid;
		if (!MarketStore.enabled()) return error("The market is closed.");
		MarketStore.reloadSettings();
		String id = str(in, "id");
		int amount = num(in, "amount", 1);
		int price = num(in, "price", -1);
		String done = switch (str(in, "kind")) {
			case "listing" -> listing(server, uuid, name, id, amount, price, num(in, "per", -1));
			case "claims" -> claims(uuid, name, id, amount, price);
			case "command" -> command(uuid, name, id, amount, price);
			case "pet" -> pet(uuid, name, id, price);
			default -> "!What should be bought?";
		};
		if (done.startsWith("!")) return error(done.substring(1));
		MarketStore.saveNow();
		eu.explorerseden.nicecontrolcenter.NiceControlCenter.LOGGER.info("[market] {} on the website: {}", name, done);
		ServerPlayer online = server.getPlayerList().getPlayer(UUID.fromString(uuid));
		if (online != null) {
			online.sendSystemMessage(Component.literal("Website: " + done).withStyle(ChatFormatting.GOLD));
			Plop.play(online);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("ok", true);
		out.put("message", done);
		out.put("balance", MarketStore.data().balance(uuid));
		return out;
	}

	private static String listing(MinecraftServer server, String uuid, String name, String id, int lots, int price, int per) {
		Market.Data data = MarketStore.data();
		Market.Listing l = data.listings.get(id);
		if (l == null) return "!That listing is gone.";
		if (l.seller.equals(uuid)) return "!That's your own listing.";
		int lot = l.lot();
		if (price != l.price || per != lot) return "!The seller just changed the price. Check it and buy again.";
		if (lots < 1) return "!Type how many you want.";
		if ((long) lots * lot > l.amount) return "!Only " + (lot == 1 ? l.amount : l.amount / lot + (l.amount / lot == 1 ? " lot" : " lots")) + " left.";
		if (MarketStore.stack(server, l.item, 1).isEmpty()) return "!This item can't be bought any more (a mod it needs is gone).";
		int items = lots * lot;
		long cost = (long) lots * l.price;
		if (!data.debit(uuid, cost)) return "!You need " + money(cost) + " in your bank.";
		Market.Item item = l.item;
		String sellerId = l.seller;
		data.buy(l, uuid, name, items, System.currentTimeMillis());
		Market.Mailbox m = data.mailbox(uuid, name);
		Market.Parcel p = new Market.Parcel();
		p.item = item;
		p.amount = items;
		m.parcels.add(p);
		data.mailChanged(m);
		MarketDialogs.tellSeller(server, sellerId, name + " bought " + items + " × " + item.name + " for " + money(cost) + " on the website.");
		return "Bought " + items + " × " + item.name + " for " + money(cost) + ". Collect it from your mailbox in /market.";
	}

	private static String claims(String uuid, String name, String id, int packs, int price) {
		if (!MarketDialogs.sellsClaimBlocks()) return "!Claim blocks aren't for sale right now.";
		Market.ClaimOffer o = MarketStore.settings().claimBlocks.stream().filter(c -> c.id.equals(id) && c.onSale()).findFirst().orElse(null);
		if (o == null || o.price != price) return "!That listing is gone or changed.";
		if (packs < 1 || packs > 64) return "!Type a number from 1 to 64.";
		long blocks = (long) packs * o.blocks;
		if (blocks > ClaimStore.data().blocksLeft(uuid, MarketStore.settings().claimBlocksMax)) return "!You can't buy that many.";
		long cost = (long) packs * o.price;
		if (!MarketStore.data().debit(uuid, cost)) return "!You need " + money(cost) + " in your bank.";
		ClaimStore.data().buy(uuid, name, (int) blocks, ClaimStore.settings());
		ClaimStore.touched();
		MarketStore.data().serverSale(o.id, false, String.format(Locale.ROOT, "%,d claim blocks", o.blocks), o.icon, MarketStore.settings().serverName, uuid, name, packs, (int) cost, System.currentTimeMillis());
		return String.format(Locale.ROOT, "Bought %,d claim blocks for %s.", blocks, money(cost));
	}

	private static String command(String uuid, String name, String id, int n, int price) {
		Market.CommandOffer o = MarketStore.settings().commandOffer(id);
		if (o == null || !o.onSale() || o.price != price) return "!That listing is gone or changed.";
		if (n < 1 || n > 64) return "!Type a number from 1 to 64.";
		Market.Data data = MarketStore.data();
		int left = o.perPlayer > 0 ? Math.max(0, o.perPlayer - data.serverBought(o.id, uuid)) : Integer.MAX_VALUE;
		if (n > left) return left == 0 ? "!You've bought as many as you can." : "!You can only buy " + left + " more.";
		long cost = (long) n * o.price;
		if (!data.debit(uuid, cost)) return "!You need " + money(cost) + " in your bank.";
		long now = System.currentTimeMillis();
		data.serverSale(o.id, true, o.name, o.icon, MarketStore.settings().serverName, uuid, name, n, (int) cost, now);
		data.order(uuid, name, o, n, now);
		return "Bought " + (n == 1 ? "" : n + " × ") + o.name + " for " + money(cost) + ". Claim it from your mailbox in /market.";
	}

	private static String pet(String uuid, String name, String id, int price) {
		if (!PetStore.enabled()) return "!The pet shop is closed.";
		Pets.Pet pet = PetStore.settings().pet(id);
		if (pet == null) return "!That pet isn't for sale any more.";
		if (pet.price != price) return "!The price changed. Check it and buy again.";
		Pets.Data pets = PetStore.data();
		String why = pets.cannotBuy(uuid, pet);
		if (why != null) return "!" + why;
		if (pet.price > 0 && !MarketStore.data().debit(uuid, pet.price)) return "!You need " + money(pet.price) + " in your bank.";
		pets.buy(uuid, name, pet, System.currentTimeMillis());
		Pets.Owner owner = pets.owners.get(uuid);
		// A first pet comes out right away (or when they next join).
		if (owner.active == null) owner.active = pet.id;
		PetStore.saveNow();
		return (pet.price == 0 ? "Got " : "Bought ") + pet.name + (pet.price == 0 ? "" : " for " + money(pet.price)) + ". See /pets.";
	}

	private static Map<String, Object> error(String message) {
		return Map.of("error", message);
	}

	private static boolean validUuid(String s) {
		try {
			return s != null && UUID.fromString(s).toString().equals(s.toLowerCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static String money(long n) {
		return String.format(Locale.ROOT, "%,d %s", n, n == 1 ? MarketStore.currencyName().replaceAll("s$", "") : MarketStore.currencyName());
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString().strip() : "";
	}

	private static int num(JsonObject o, String k, int fallback) {
		try {
			return Integer.parseInt(str(o, k));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
