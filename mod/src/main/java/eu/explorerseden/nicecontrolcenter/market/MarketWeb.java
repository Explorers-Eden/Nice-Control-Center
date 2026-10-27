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
		// Shop coupons (rewards): worth → how many, for the website's buy dialog.
		var perks = eu.explorerseden.nicecontrolcenter.players.Perks.holder(uuid);
		if (perks != null && !perks.coupons.isEmpty()) out.put("coupons", perks.coupons);
		out.put("maxListings", data.maxListings(uuid, MarketStore.settings()));
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
		List<Map<String, Object>> listings = new ArrayList<>();
		for (Market.Listing l : data.listings.values()) {
			if (!uuid.equals(l.seller)) continue;
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", l.id);
			row.put("name", l.item.name);
			row.put("item", l.item.id);
			row.put("amount", l.amount);
			row.put("price", l.price);
			row.put("per", l.lot());
			row.put("expires", l.expires);
			row.put("lore", l.item.lore);
			row.put("enchantments", l.item.enchantments);
			int[] have = spare(server, l);
			row.put("canAdd", have[0] + have[1]);
			listings.add(row);
		}
		out.put("listings", listings);
		out.put("maxPrice", MarketStore.settings().maxPrice);
		// "Member since" and the last visit, for the website's profile.
		out.put("seen", eu.explorerseden.nicecontrolcenter.players.Seen.view(server, UUID.fromString(uuid)));
		return out;
	}

	/** Items the predicate counts as the listing's item (same item, same components). */
	private static java.util.function.Predicate<Market.Item> sameAs(MinecraftServer server, Market.Item item) {
		net.minecraft.world.item.ItemStack like = MarketStore.stack(server, item, 1);
		return other -> !like.isEmpty() && (other.stack.equals(item.stack)
				|| net.minecraft.world.item.ItemStack.isSameItemSameComponents(MarketStore.stack(server, other, 1), like));
	}

	/** How many more of the listing's item the seller has: in their mailbox, and in their inventory while online. */
	private static int[] spare(MinecraftServer server, Market.Listing l) {
		int mail = MarketStore.data().mailboxCount(l.seller, sameAs(server, l.item));
		ServerPlayer online = server.getPlayerList().getPlayer(UUID.fromString(l.seller));
		net.minecraft.world.item.ItemStack like = MarketStore.stack(server, l.item, 1);
		int carried = online == null || like.isEmpty() ? 0 : MarketStore.count(online, like);
		return new int[] { mail, carried };
	}

	/**
	 * The seller changes one of their listings: the price, how many it's for, and the amount. Fewer items go back to
	 * the mailbox; more are taken from the mailbox first, then (while online) the inventory, like "Add more" in the
	 * menu: whole lots only, and the listing runs for its full time again.
	 */
	public static Map<String, Object> editListing(MinecraftServer server, JsonObject in) {
		String uuid = in.has("uuid") ? in.get("uuid").getAsString() : "";
		if (!validUuid(uuid)) return Map.of("error", "Unknown player.");
		Market.Data data = MarketStore.data();
		String id = in.has("id") ? in.get("id").getAsString() : "";
		Market.Listing l = data.listings.get(id);
		if (l == null) l = data.listings.values().stream().filter(x -> id.equals(x.id)).findFirst().orElse(null);
		if (l == null) return Map.of("error", "That listing is gone (sold out or ended).");
		if (!uuid.equals(l.seller)) return Map.of("error", "That isn't your listing.");
		int price, per, amount;
		try {
			price = in.has("price") ? in.get("price").getAsInt() : l.price;
			per = in.has("per") ? in.get("per").getAsInt() : l.lot();
			amount = in.has("amount") ? in.get("amount").getAsInt() : l.amount;
		} catch (RuntimeException e) {
			return Map.of("error", "Type whole numbers.");
		}
		var s = MarketStore.settings();
		// The same checks update() makes, before any items move.
		if (amount < 1) return Map.of("error", "Keep at least one item on the market, or take the listing back.");
		if (price < 1 || price > s.maxPrice) return Map.of("error", "The price is 1 to " + s.maxPrice + ".");
		if (per < 1 || per > amount) return Map.of("error", "The price is for 1 to " + amount + " items.");
		if (amount % per != 0) return Map.of("error", amount + " don't split into lots of " + per + ": make the amount a multiple of \"For how many\".");
		int add = amount - l.amount;
		int fromMail = 0, fromInventory = 0;
		if (add > 0) {
			int[] have = spare(server, l);
			if (add > have[0] + have[1]) {
				return Map.of("error", String.format(Locale.ROOT, "You have %,d more %s (%,d in your inbox%s). Bring more in game with /market.",
						have[0] + have[1], l.item.name, have[0], server.getPlayerList().getPlayer(UUID.fromString(uuid)) == null ? "; your inventory counts while you're online" : ", " + have[1] + " in your inventory"));
			}
			fromMail = data.takeFromMailbox(uuid, sameAs(server, l.item), add);
			fromInventory = add - fromMail;
			if (fromInventory > 0) {
				ServerPlayer online = server.getPlayerList().getPlayer(UUID.fromString(uuid));
				net.minecraft.world.item.ItemStack like = MarketStore.stack(server, l.item, 1);
				if (online == null || like.isEmpty() || !MarketStore.take(online, like, fromInventory)) {
					// Put back what came out of the mailbox.
					if (fromMail > 0) {
						Market.Parcel p = new Market.Parcel();
						p.item = l.item;
						p.amount = fromMail;
						data.mailbox(uuid, l.sellerName).parcels.add(p);
					}
					return Map.of("error", "Those items aren't there any more. Try again.");
				}
			}
		}
		int spare = l.amount - amount;
		String problem = data.update(l, amount, price, per, s, System.currentTimeMillis());
		if (problem != null) return Map.of("error", problem);
		MarketStore.saveNow();
		ServerPlayer online = server.getPlayerList().getPlayer(UUID.fromString(uuid));
		if (online != null && fromInventory > 0) {
			online.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "%,d × %s from your inventory went onto your market listing (changed on the website).", fromInventory, l.item.name)).withStyle(ChatFormatting.GRAY));
		}
		String message = spare > 0 ? String.format(Locale.ROOT, "Saved. %,d × %s went to your inbox (/inbox in game).", spare, l.item.name)
				: add > 0 ? String.format(Locale.ROOT, "Saved. %,d more on the market (%s); it runs %d days again.", add,
						fromInventory == 0 ? "from your inbox" : fromMail == 0 ? "from your inventory" : fromMail + " from your inbox, " + fromInventory + " from your inventory", s.listingDays)
				: "Saved.";
		return Map.of("ok", true, "message", message);
	}

	/** Takes one of the player's own listings off the market; what's left goes to their mailbox. */
	public static Map<String, Object> withdraw(MinecraftServer server, JsonObject in) {
		String uuid = in.has("uuid") ? in.get("uuid").getAsString() : "";
		if (!validUuid(uuid)) return Map.of("error", "Unknown player.");
		Market.Data data = MarketStore.data();
		String id = in.has("id") ? in.get("id").getAsString() : "";
		Market.Listing l = data.listings.get(id);
		if (l == null) l = data.listings.values().stream().filter(x -> id.equals(x.id)).findFirst().orElse(null);
		if (l == null) return Map.of("error", "That listing is gone (sold out or ended).");
		if (!uuid.equals(l.seller)) return Map.of("error", "That isn't your listing.");
		int amount = l.amount;
		String name = l.item.name;
		data.withdraw(l);
		MarketStore.saveNow();
		ServerPlayer online = server.getPlayerList().getPlayer(UUID.fromString(uuid));
		if (online != null) {
			online.sendSystemMessage(Component.literal("Your " + name + " listing was taken off the market on the website; the items wait in your inbox: /inbox.").withStyle(ChatFormatting.GRAY));
		}
		return Map.of("ok", true, "message", String.format(Locale.ROOT, "%,d × %s went to your inbox. Collect them with /inbox in game.", amount, name));
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
		// A shop coupon (reward, worth N currency items off one server-shop purchase).
		int coupon = Math.max(0, num(in, "coupon", 0));
		if (coupon > 0 && !eu.explorerseden.nicecontrolcenter.players.Perks.hasCoupon(uuid, coupon)) return error("You don't have that coupon any more.");
		String done = switch (str(in, "kind")) {
			case "listing" -> listing(server, uuid, name, id, amount, price, num(in, "per", -1));
			case "claims" -> claims(uuid, name, id, amount, price, coupon);
			case "command" -> command(uuid, name, id, amount, price, coupon);
			case "pet" -> pet(uuid, name, id, price, coupon);
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
		return "Bought " + items + " × " + item.name + " for " + money(cost) + ". Collect it with /inbox in game.";
	}

	private static String claims(String uuid, String name, String id, int packs, int price, int coupon) {
		if (!MarketDialogs.sellsClaimBlocks()) return "!Claim blocks aren't for sale right now.";
		Market.ClaimOffer o = MarketStore.settings().claimBlocks.stream().filter(c -> c.id.equals(id) && c.onSale()).findFirst().orElse(null);
		if (o == null || o.price != price) return "!That listing is gone or changed.";
		if (packs < 1 || packs > 64) return "!Type a number from 1 to 64.";
		long blocks = (long) packs * o.blocks;
		if (blocks > ClaimStore.data().blocksLeft(uuid, MarketStore.settings().claimBlocksMax)) return "!You can't buy that many.";
		long cost = eu.explorerseden.nicecontrolcenter.players.Perks.discounted((long) packs * o.price, coupon);
		if (cost > 0 && !MarketStore.data().debit(uuid, cost)) return "!You need " + money(cost) + " in your bank.";
		if (coupon > 0) eu.explorerseden.nicecontrolcenter.players.Perks.useCoupon(uuid, coupon);
		ClaimStore.data().buy(uuid, name, (int) blocks, ClaimStore.settings());
		ClaimStore.touched();
		MarketStore.data().serverSale(o.id, false, String.format(Locale.ROOT, "%,d claim blocks", o.blocks), o.icon, MarketStore.settings().serverName, uuid, name, packs, (int) cost, System.currentTimeMillis());
		return String.format(Locale.ROOT, "Bought %,d claim blocks for %s", blocks, money(cost)) + (coupon > 0 ? " with a " + eu.explorerseden.nicecontrolcenter.players.Perks.couponName(coupon) : "") + ".";
	}

	private static String command(String uuid, String name, String id, int n, int price, int coupon) {
		Market.CommandOffer o = MarketStore.settings().commandOffer(id);
		if (o == null || !o.onSale() || o.price != price) return "!That listing is gone or changed.";
		if (n < 1 || n > 64) return "!Type a number from 1 to 64.";
		Market.Data data = MarketStore.data();
		int left = o.perPlayer > 0 ? Math.max(0, o.perPlayer - data.serverBought(o.id, uuid)) : Integer.MAX_VALUE;
		if (n > left) return left == 0 ? "!You've bought as many as you can." : "!You can only buy " + left + " more.";
		long cost = eu.explorerseden.nicecontrolcenter.players.Perks.discounted((long) n * o.price, coupon);
		if (cost > 0 && !data.debit(uuid, cost)) return "!You need " + money(cost) + " in your bank.";
		if (coupon > 0) eu.explorerseden.nicecontrolcenter.players.Perks.useCoupon(uuid, coupon);
		long now = System.currentTimeMillis();
		data.serverSale(o.id, true, o.name, o.icon, MarketStore.settings().serverName, uuid, name, n, (int) cost, now);
		data.order(uuid, name, o, n, now);
		return "Bought " + (n == 1 ? "" : n + " × ") + o.name + " for " + money(cost) + (coupon > 0 ? " with a " + eu.explorerseden.nicecontrolcenter.players.Perks.couponName(coupon) : "") + ". Claim it with /inbox in game.";
	}

	private static String pet(String uuid, String name, String id, int price, int coupon) {
		if (!PetStore.enabled()) return "!The pet shop is closed.";
		Pets.Pet pet = PetStore.settings().pet(id);
		if (pet == null) return "!That pet isn't for sale any more.";
		if (pet.price != price) return "!The price changed. Check it and buy again.";
		Pets.Data pets = PetStore.data();
		String why = pets.cannotBuy(uuid, pet);
		if (why != null) return "!" + why;
		long cost = eu.explorerseden.nicecontrolcenter.players.Perks.discounted(pet.price, coupon);
		if (cost > 0 && !MarketStore.data().debit(uuid, cost)) return "!You need " + money(cost) + " in your bank.";
		if (coupon > 0) eu.explorerseden.nicecontrolcenter.players.Perks.useCoupon(uuid, coupon);
		pets.buy(uuid, name, pet, System.currentTimeMillis());
		Pets.Owner owner = pets.owners.get(uuid);
		// A first pet comes out right away (or when they next join).
		if (owner.active == null) owner.active = pet.id;
		PetStore.saveNow();
		return (cost == 0 ? "Got " : "Bought ") + pet.name + (cost == 0 ? "" : " for " + money(cost)) + (coupon > 0 ? " with a " + eu.explorerseden.nicecontrolcenter.players.Perks.couponName(coupon) : "") + ". See /pets.";
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
