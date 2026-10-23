package eu.explorerseden.nicecontrolcenter.players;

import eu.explorerseden.nicecontrolcenter.Claims;
import eu.explorerseden.nicecontrolcenter.Market;
import eu.explorerseden.nicecontrolcenter.claims.ClaimStore;
import eu.explorerseden.nicecontrolcenter.market.MarketStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The player card's Claims and Market parts: claims from this mod's own claim system and the player's
 * market listings, sales and mailbox. Server thread.
 */
public final class PlayerExtras {
	/** Sales shown per player (newest first). */
	private static final int SALES = 50;

	private PlayerExtras() {
	}

	/** Claims they own or are a member of, and their claim block balance; null when claims never loaded. */
	public static Map<String, Object> claims(String uuid) {
		Claims.Data data = ClaimStore.data();
		if (data == null || uuid == null) return null;
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("enabled", ClaimStore.enabled());
		List<Map<String, Object>> owned = new ArrayList<>();
		List<Map<String, Object>> member = new ArrayList<>();
		for (Claims.Claim c : data.claims.values()) {
			if (c.parent != null) continue;
			if (uuid.equals(c.owner)) {
				Map<String, Object> row = claimRow(c);
				List<Map<String, Object>> members = new ArrayList<>();
				c.members.forEach((id, m) -> {
					Map<String, Object> mm = new LinkedHashMap<>();
					mm.put("name", m.name == null || m.name.isBlank() ? id.substring(0, 8) : m.name);
					mm.put("role", m.role == null ? "" : m.role.label);
					members.add(mm);
				});
				row.put("members", members);
				row.put("subclaims", data.subclaims(c.id).size());
				owned.add(row);
			} else if (c.members.containsKey(uuid)) {
				Map<String, Object> row = claimRow(c);
				Claims.Member m = c.members.get(uuid);
				row.put("owner", c.owner == null ? "Server" : c.ownerName);
				row.put("role", m.role == null ? "" : m.role.label);
				member.add(row);
			}
		}
		out.put("owned", owned);
		out.put("member", member);
		Claims.PlayerBlocks p = data.player(uuid);
		Map<String, Object> blocks = new LinkedHashMap<>();
		blocks.put("earned", p == null ? 0 : p.accrued);
		blocks.put("bonus", p == null ? 0 : p.bonus);
		blocks.put("bought", p == null ? 0 : p.bought);
		blocks.put("used", data.used(uuid));
		blocks.put("available", data.available(uuid));
		blocks.put("playMinutes", p == null ? 0 : p.playMinutes);
		out.put("blocks", blocks);
		return out;
	}

	private static Map<String, Object> claimRow(Claims.Claim c) {
		Map<String, Object> row = new LinkedHashMap<>();
		Claims.Box box = c.box();
		row.put("id", c.id);
		row.put("name", c.name == null ? "" : c.name);
		row.put("dimensionId", c.dim);
		row.put("x", (box.minX() + box.maxX()) / 2);
		row.put("z", (box.minZ() + box.maxZ()) / 2);
		row.put("width", box.width());
		row.put("length", box.length());
		row.put("area", box.area());
		row.put("color", String.format("#%06X", c.color & 0xFFFFFF));
		return row;
	}

	/** Their listings now, recent sales and purchases, and what waits in their mailbox; null when the market never loaded. */
	public static Map<String, Object> market(String uuid) {
		Market.Data data = MarketStore.data();
		if (data == null || uuid == null) return null;
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("enabled", MarketStore.enabled());
		out.put("currency", MarketStore.currencyName());
		List<Map<String, Object>> listings = new ArrayList<>();
		for (Market.Listing l : data.listings.values()) {
			if (!uuid.equals(l.seller)) continue;
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", l.id);
			row.put("item", l.item == null ? "?" : l.item.name);
			row.put("itemId", l.item == null ? "" : l.item.id);
			row.put("amount", l.amount);
			row.put("price", l.price);
			row.put("per", Math.max(1, l.per));
			row.put("created", l.created);
			row.put("expires", l.expires);
			listings.add(row);
		}
		out.put("listings", listings);
		List<Map<String, Object>> sales = new ArrayList<>();
		for (int i = data.sales.size() - 1; i >= 0 && sales.size() < SALES; i--) {
			Market.Sale s = data.sales.get(i);
			boolean sold = uuid.equals(s.seller);
			if (!sold && !uuid.equals(s.buyer)) continue;
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("time", s.time);
			row.put("sold", sold);
			row.put("item", s.itemName);
			row.put("amount", s.amount);
			row.put("total", s.total);
			// Server shop purchases have no seller.
			row.put("with", sold ? s.buyerName : s.seller == null ? "Server shop" : s.sellerName);
			sales.add(row);
		}
		out.put("sales", sales);
		Market.Mailbox mail = data.mail.get(uuid);
		Map<String, Object> mailbox = new LinkedHashMap<>();
		mailbox.put("currency", mail == null ? 0 : mail.currency);
		mailbox.put("balance", mail == null ? 0 : mail.balance);
		mailbox.put("orders", mail == null ? List.of() : mail.orders.stream().map(o -> Map.of("name", o.name, "times", o.times)).toList());
		List<Map<String, Object>> parcels = new ArrayList<>();
		if (mail != null) {
			for (Market.Parcel p : mail.parcels) {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("item", p.item == null ? "?" : p.item.name);
				row.put("amount", p.amount);
				parcels.add(row);
			}
		}
		mailbox.put("parcels", parcels);
		out.put("mailbox", mailbox);
		return out;
	}
}
