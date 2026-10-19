package eu.explorerseden.nicecontrolcenter.api;

import eu.explorerseden.nicecontrolcenter.Market;
import eu.explorerseden.nicecontrolcenter.market.MarketStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The player market for other mods (Merl's price advice uses it by reflection, so it works without a compile
 * dependency). Server thread. Plain maps, no internal types.
 */
public final class MarketApi {
	private MarketApi() {
	}

	/** The market is open. */
	public static boolean enabled() {
		return MarketStore.enabled();
	}

	/** The currency item's id, e.g. "minecraft:diamond". */
	public static String currency() {
		return MarketStore.settings().currency;
	}

	/**
	 * What one item has been worth lately, prices always per single item:
	 * {sales (Integer, player sales of it still on record), median, low, high (Double; with sales), lastSold (Long),
	 * listings (Integer, for sale now), cheapest (Double; with listings), lot (Integer, the most common "per" of its listings and sales)}.
	 */
	public static Map<String, Object> prices(String itemId) {
		Market.Data data = MarketStore.data();
		List<Double> each = new ArrayList<>();
		Map<Integer, Integer> lots = new LinkedHashMap<>();
		long last = 0;
		for (Market.Sale s : data.sales) {
			// Server shop sales (claim blocks, commands) have no seller and no real item.
			if (s.seller == null || !itemId.equals(s.itemId) || s.amount <= 0) continue;
			each.add(s.total / (double) s.amount);
			last = Math.max(last, s.time);
		}
		double cheapest = Double.MAX_VALUE;
		int listings = 0;
		for (Market.Listing l : data.listings.values()) {
			if (l.item == null || !itemId.equals(l.item.id)) continue;
			listings++;
			cheapest = Math.min(cheapest, l.price / (double) l.lot());
			lots.merge(l.lot(), 1, Integer::sum);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("sales", each.size());
		if (!each.isEmpty()) {
			Collections.sort(each);
			int n = each.size();
			out.put("median", n % 2 == 1 ? each.get(n / 2) : (each.get(n / 2 - 1) + each.get(n / 2)) / 2);
			out.put("low", each.get(0));
			out.put("high", each.get(n - 1));
			out.put("lastSold", last);
		}
		out.put("listings", listings);
		if (listings > 0) out.put("cheapest", cheapest);
		int lot = 1;
		int seen = 0;
		for (Map.Entry<Integer, Integer> e : lots.entrySet()) {
			if (e.getValue() > seen) {
				seen = e.getValue();
				lot = e.getKey();
			}
		}
		out.put("lot", lot);
		return out;
	}
}
