package eu.explorerseden.nicecontrolcenter.market;

import eu.explorerseden.nicecontrolcenter.Market;
import eu.explorerseden.nicecontrolcenter.Pets;
import eu.explorerseden.nicecontrolcenter.claims.ClaimMode;
import eu.explorerseden.nicecontrolcenter.claims.ClaimStore;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.ItemBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.SingleOptionInput;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import eu.explorerseden.nicecontrolcenter.core.Plop;

/**
 * The market menu (/market), as dialogs: browse and buy, sell from your inventory, your listings and your
 * mailbox, plus the server shop (claim blocks and command listings set in the panel). Buttons send
 * "nicecontrolcenter:market/&lt;what&gt;"; {@link #handle} checks everything again, so
 * nothing in the payload is trusted.
 */
public final class MarketDialogs {
	private static final int WIDTH = 150;
	private static final int TEXT_WIDTH = 310;
	private static final int PAGE = 10;

	private MarketDialogs() {
	}

	// ---------------------------------------------------------------- building blocks

	private static void show(ServerPlayer player, Dialog dialog) {
		player.openDialog(Holder.direct(dialog));
	}

	private static CompoundTag args(String... pairs) {
		CompoundTag tag = new CompoundTag();
		for (int i = 0; i + 1 < pairs.length; i += 2) tag.putString(pairs[i], pairs[i + 1]);
		return tag;
	}

	private static ActionButton button(Component label, String tooltip, String action, CompoundTag args) {
		return new ActionButton(new CommonButtonData(label, tooltip == null ? Optional.empty() : Optional.of(Component.literal(tooltip)), WIDTH),
				Optional.of(new CustomAll(Identifier.fromNamespaceAndPath("nicecontrolcenter", "market/" + action), Optional.of(args))));
	}

	private static ActionButton button(String label, String tooltip, String action, CompoundTag args) {
		return button(Component.literal(label), tooltip, action, args);
	}

	private static DialogBody text(String s, ChatFormatting color) {
		return new PlainMessage(Component.literal(s).withStyle(color), TEXT_WIDTH);
	}

	private static void menu(ServerPlayer player, String title, List<DialogBody> body, List<Input> inputs, List<ActionButton> buttons) {
		menu(player, title, body, inputs, buttons, new ActionButton(new CommonButtonData(Component.literal("Close"), WIDTH), Optional.empty()));
	}

	/** A menu whose bottom button (apart from the others) is exit, e.g. Back. */
	private static void menu(ServerPlayer player, String title, List<DialogBody> body, List<Input> inputs, List<ActionButton> buttons, ActionButton exit) {
		CommonDialogData common = new CommonDialogData(Component.literal(title), Optional.empty(), true, false, DialogAction.CLOSE, body, inputs);
		if (buttons.isEmpty()) show(player, new NoticeDialog(common, exit));
		else show(player, new MultiActionDialog(common, buttons, Optional.of(exit), 2));
	}

	private static ActionButton back(String action, CompoundTag args) {
		return button("Back", null, action, args);
	}

	/** A clickable label that looks like a small button ("[ View ]"): sends the same "market/…" click as a button. */
	private static MutableComponent link(String label, String hover, String action, CompoundTag args, ChatFormatting color) {
		return Component.literal("[ " + label + " ]").withStyle(st -> st.withColor(color).withBold(true)
				.withClickEvent(new ClickEvent.Custom(Identifier.fromNamespaceAndPath("nicecontrolcenter", "market/" + action), Optional.of(args)))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	/** An item row: its icon (with the item's own tooltip on hover) and a description next to it. */
	private static Optional<DialogBody> itemRow(ServerPlayer player, Market.Item item, int count, Component description) {
		ItemStack stack = MarketStore.stack(player.level().getServer(), item, Math.max(1, Math.min(count, 99)));
		if (stack.isEmpty()) return Optional.empty();
		return Optional.of(new ItemBody(ItemStackTemplate.fromNonEmptyStack(stack), Optional.of(new PlainMessage(description, TEXT_WIDTH - 30)), true, true, 16, 16));
	}

	private static String money(int n) {
		return String.format(Locale.ROOT, "%,d %s", n, n == 1 ? MarketStore.currencyName().replaceAll("s$", "") : MarketStore.currencyName());
	}

	private static String str(CompoundTag t, String k) {
		return t.getStringOr(k, "");
	}

	private static int num(CompoundTag t, String k) {
		try {
			return Integer.parseInt(str(t, k).strip().replace(",", ""));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	// ---------------------------------------------------------------- menus

	public static void home(ServerPlayer player, String notice) {
		Market.Data data = MarketStore.data();
		String me = player.getStringUUID();
		Market.Mailbox mail = data.mail.get(me);
		List<DialogBody> body = new ArrayList<>();
		if (notice != null) {
			body.add(text(notice, notice.startsWith("Bought") || notice.startsWith("Listed") || notice.startsWith("Collected") ? ChatFormatting.GREEN : ChatFormatting.RED));
			Plop.play(player);
		}
		body.add(text(data.listings.size() + (data.listings.size() == 1 ? " player listing" : " player listings") + " for sale · paid in " + MarketStore.currencyName(), ChatFormatting.GRAY));
		if (mail != null && !mail.isEmpty()) body.add(text("Your mailbox: " + (mail.currency > 0 ? money(mail.currency) : "") + (mail.currency > 0 && !mail.parcels.isEmpty() ? " and " : "")
				+ (mail.parcels.isEmpty() ? "" : mail.parcels.size() + " parcel" + (mail.parcels.size() == 1 ? "" : "s")), ChatFormatting.GOLD));
		List<ActionButton> buttons = new ArrayList<>();
		if (serverShopOpen()) buttons.add(button("Server shop", "What the server sells", "server", args()));
		buttons.add(button(serverShopOpen() ? "Player listings" : "Browse", "Everything players sell", "browse", args("page", "0")));
		buttons.add(button("Sell", "List items from your inventory", "sell", args()));
		buttons.add(button("My listings (" + data.of(me).size() + ")", null, "mine", args()));
		buttons.add(button("Mailbox" + (mail != null && !mail.isEmpty() ? " ●" : ""), "Your earnings and items that came back", "mail", args()));
		if (eu.explorerseden.nicecontrolcenter.pets.PetStore.enabled()) buttons.add(eu.explorerseden.nicecontrolcenter.pets.PetDialogs.button("Pets", "Floating heads that follow you", "shop", args("page", "0")));
		menu(player, "Market", body, List.of(), buttons);
	}

	/** What the server sells: claim blocks (while claims are on) and command listings. */
	private static void server(ServerPlayer player, String notice) {
		List<DialogBody> body = new ArrayList<>();
		if (notice != null) {
			body.add(text(notice, ChatFormatting.RED));
			Plop.play(player);
		}
		Market.Settings ms = MarketStore.settings();
		boolean any = false;
		if (ClaimStore.enabled()) {
			for (int i = 0; i < ms.claimBlocks.size(); i++) {
				Market.ClaimOffer o = ms.claimBlocks.get(i);
				if (!o.onSale()) continue;
				any = true;
				body.add(iconRow(o.icon, Component.empty()
						.append(Component.literal(String.format(Locale.ROOT, "%,d claim blocks", o.blocks)).withStyle(ChatFormatting.WHITE))
						.append(Component.literal("\n" + money(o.price)).withStyle(ChatFormatting.GOLD))
						.append(Component.literal((o.saleEnds != 0 ? " · until " + Pets.date(o.saleEnds) : "") + "  ").withStyle(ChatFormatting.GRAY))
						.append(link("Buy", "See it and buy", "claims", offerArgs(i, o), ChatFormatting.GREEN))));
			}
		}
		for (Market.CommandOffer o : ms.commandOffers) {
			if (!o.onSale()) continue;
			any = true;
			body.add(iconRow(o.icon, Component.empty()
					.append(Component.literal(o.name).withStyle(ChatFormatting.WHITE))
					.append(Component.literal("\n" + (o.description.isEmpty() ? "" : o.description + "\n")).withStyle(ChatFormatting.GRAY))
					.append(Component.literal(money(o.price)).withStyle(ChatFormatting.GOLD))
					.append(Component.literal((o.saleEnds != 0 ? " · until " + Pets.date(o.saleEnds) : "") + "  ").withStyle(ChatFormatting.GRAY))
					.append(link("Buy", "See it and buy", "command", args("id", o.id), ChatFormatting.GREEN))));
		}
		if (!any) body.add(text("The server sells nothing right now.", ChatFormatting.GRAY));
		menu(player, "Market · Server shop", body, List.of(), List.of(), back("home", args()));
	}

	private static void browse(ServerPlayer player, String query, String sort, int page) {
		String q = query.toLowerCase(Locale.ROOT).strip();
		List<Market.Listing> all = new ArrayList<>(MarketStore.data().listings.values());
		all.removeIf(l -> !q.isEmpty() && !(l.item.name.toLowerCase(Locale.ROOT).contains(q) || l.item.id.contains(q)
				|| l.sellerName != null && l.sellerName.toLowerCase(Locale.ROOT).contains(q)));
		Comparator<Market.Listing> order = switch (sort) {
			case "new" -> Comparator.comparingLong((Market.Listing l) -> l.created).reversed();
			case "name" -> Comparator.comparing((Market.Listing l) -> l.item.name.toLowerCase(Locale.ROOT));
			default -> Comparator.comparingInt((Market.Listing l) -> l.price);
		};
		all.sort(order);
		int pages = Math.max(1, (all.size() + PAGE - 1) / PAGE);
		int p = Math.max(0, Math.min(page, pages - 1));
		String sortName = switch (sort) {
			case "new" -> "newest first";
			case "name" -> "by name";
			default -> "cheapest first";
		};
		List<DialogBody> body = new ArrayList<>();
		// Searching and paging on top, apart from the listings.
		MutableComponent nav = Component.empty()
				.append(link("Search", "Search by item or seller, change the order", "search", args("keepq", query, "keepsort", sort), ChatFormatting.AQUA));
		if (pages > 1) {
			nav.append(Component.literal("     "));
			if (p > 0) nav.append(link("←", "Page " + p, "browse", args("page", String.valueOf(p - 1), "keepq", query, "keepsort", sort), ChatFormatting.YELLOW));
			else nav.append(Component.literal("[ ← ]").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.BOLD));
			nav.append(Component.literal("  " + (p + 1) + " / " + pages + "  ").withStyle(ChatFormatting.GRAY));
			if (p < pages - 1) nav.append(link("→", "Page " + (p + 2), "browse", args("page", String.valueOf(p + 1), "keepq", query, "keepsort", sort), ChatFormatting.YELLOW));
			else nav.append(Component.literal("[ → ]").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.BOLD));
		}
		body.add(new PlainMessage(nav, TEXT_WIDTH));
		body.add(text((all.isEmpty() ? "Nothing found" : all.size() + (all.size() == 1 ? " listing" : " listings")) + (q.isEmpty() ? "" : " for \"" + query.strip() + "\"")
				+ " · " + sortName, ChatFormatting.GRAY));
		for (Market.Listing l : all.subList(p * PAGE, Math.min(all.size(), (p + 1) * PAGE))) {
			boolean own = l.seller.equals(player.getStringUUID());
			Component desc = Component.empty()
					.append(Component.literal(l.item.name).withStyle(ChatFormatting.WHITE))
					.append(Component.literal("  ×" + String.format(Locale.ROOT, "%,d", l.amount)).withStyle(ChatFormatting.GRAY))
					.append(Component.literal("\n" + money(l.price) + " each").withStyle(ChatFormatting.GOLD))
					.append(Component.literal(" · " + (own ? "you" : l.sellerName) + "  ").withStyle(ChatFormatting.GRAY))
					.append(own ? link("View", "See it or take it back", "view", args("id", l.id), ChatFormatting.AQUA)
							: link("Buy", "See it and buy", "view", args("id", l.id), ChatFormatting.GREEN));
			itemRow(player, l.item, l.amount, desc).ifPresentOrElse(body::add, () -> body.add(new PlainMessage(desc, TEXT_WIDTH)));
		}
		menu(player, "Market · Browse", body, List.of(), List.of(), back("home", args()));
	}

	private static void search(ServerPlayer player, String query, String sort) {
		List<Input> inputs = List.of(
				new Input("q", new TextInput(250, Component.literal("Item or seller"), true, query, 50, Optional.empty())),
				new Input("sort", new SingleOptionInput(250, List.of(
						new SingleOptionInput.Entry("price", Optional.of(Component.literal("Cheapest first")), sort.equals("price")),
						new SingleOptionInput.Entry("new", Optional.of(Component.literal("Newest first")), sort.equals("new")),
						new SingleOptionInput.Entry("name", Optional.of(Component.literal("By name")), sort.equals("name"))), Component.literal("Order"), true)));
		List<ActionButton> buttons = List.of(button("Search", null, "browse", args("page", "0")),
				button("Show everything", null, "browse", args("page", "0", "keepq", "", "keepsort", sort, "clear", "1")));
		menu(player, "Market · Search", List.of(text("Leave the search empty to see everything.", ChatFormatting.GRAY)), inputs, buttons,
				back("browse", args("page", "0", "keepq", query, "keepsort", sort)));
	}

	private static void view(ServerPlayer player, Market.Listing l, String notice) {
		ItemStack stack = MarketStore.stack(player.level().getServer(), l.item, 1);
		boolean own = l.seller.equals(player.getStringUUID());
		List<DialogBody> body = new ArrayList<>();
		if (notice != null) {
			body.add(text(notice, ChatFormatting.RED));
			Plop.play(player);
		}
		// The item (hover it for its own tooltip) with what's special about it.
		List<String> extra = new ArrayList<>(l.item.enchantments);
		extra.addAll(l.item.lore);
		MutableComponent title = Component.literal(l.item.name).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD);
		if (!extra.isEmpty()) title.append(Component.literal("\n" + String.join(" · ", extra.subList(0, Math.min(3, extra.size()))) + (extra.size() > 3 ? " …" : ""))
				.withStyle(st -> st.withBold(false).withColor(ChatFormatting.LIGHT_PURPLE)));
		if (!stack.isEmpty()) body.add(new ItemBody(ItemStackTemplate.fromNonEmptyStack(stack), Optional.of(new PlainMessage(title, TEXT_WIDTH - 30)), true, true, 16, 16));
		else body.add(new PlainMessage(title, TEXT_WIDTH));
		// The facts in one line: label grey, value bright.
		body.add(new PlainMessage(Component.empty()
				.append(Component.literal("Price ").withStyle(ChatFormatting.GRAY)).append(Component.literal(money(l.price) + " each").withStyle(ChatFormatting.GOLD))
				.append(Component.literal("   Left ").withStyle(ChatFormatting.GRAY)).append(Component.literal(String.format(Locale.ROOT, "%,d", l.amount)).withStyle(ChatFormatting.WHITE))
				.append(Component.literal("   Seller ").withStyle(ChatFormatting.GRAY)).append(Component.literal(own ? "you" : l.sellerName).withStyle(ChatFormatting.WHITE)), TEXT_WIDTH));
		List<ActionButton> buttons = new ArrayList<>();
		if (own) {
			long days = Math.max(0, (l.expires - System.currentTimeMillis()) / 86_400_000L);
			body.add(text("Your listing · " + days + (days == 1 ? " day" : " days") + " left. Taking it back sends it to your mailbox.", ChatFormatting.GRAY));
			buttons.add(button("Take it back", "Into your mailbox", "cancel", args("id", l.id)));
			menu(player, l.item.name, body, List.of(), buttons, back("mine", args()));
			return;
		}
		int carried = MarketStore.count(player, new ItemStack(MarketStore.currency()));
		int afford = Math.min(carried / Math.max(1, l.price), l.amount);
		body.add(afford > 0 ? text("You carry " + money(carried) + ": enough for " + String.format(Locale.ROOT, "%,d", afford) + ".", ChatFormatting.GRAY)
				: text("You carry " + money(carried) + ": not enough for one.", ChatFormatting.RED));
		List<Input> inputs = List.of(new Input("amount", new TextInput(120, Component.literal("How many"), true, "1", 9, Optional.empty())));
		buttons.add(button("Buy", money(l.price) + " each", "buy", args("id", l.id)));
		menu(player, l.item.name, body, inputs, buttons, back("browse", args("page", "0")));
	}

	/** Does the market sell claim blocks right now? */
	public static boolean sellsClaimBlocks() {
		return MarketStore.enabled() && ClaimStore.enabled() && MarketStore.settings().claimBlocks.stream().anyMatch(Market.ClaimOffer::onSale);
	}

	/** Does the server sell anything right now? */
	private static boolean serverShopOpen() {
		return sellsClaimBlocks() || MarketStore.settings().commandOffers.stream().anyMatch(Market.CommandOffer::onSale);
	}

	/** An offer's position plus what it was, so a click after the offers changed is caught. */
	private static CompoundTag offerArgs(int i, Market.ClaimOffer o) {
		return args("offer", String.valueOf(i), "blocks", String.valueOf(o.blocks), "price", String.valueOf(o.price));
	}

	/** The offer a click means, or null when it's gone or changed. */
	private static Market.ClaimOffer offer(CompoundTag in) {
		Market.ClaimOffer o = MarketStore.settings().claimOffer(num(in, "offer"));
		return o != null && o.onSale() && o.blocks == num(in, "blocks") && o.price == num(in, "price") ? o : null;
	}

	/** A server listing's row: its icon (an item set in the panel, without that item's tooltip: it isn't that item) and the text. */
	private static DialogBody iconRow(String icon, Component text) {
		Identifier id = Identifier.tryParse(icon);
		Item item = id == null ? null : BuiltInRegistries.ITEM.getValue(id);
		if (item == null || item == Items.AIR) return new PlainMessage(text, TEXT_WIDTH);
		return new ItemBody(ItemStackTemplate.fromNonEmptyStack(new ItemStack(item)), Optional.of(new PlainMessage(text, TEXT_WIDTH - 30)), true, false, 16, 16);
	}

	/** Opens the server shop, where the claim block listings are. */
	public static void claimBlocks(ServerPlayer player) {
		server(player, null);
	}

	/** Buying one claim block offer, up to 64 times at once. */
	private static void claimOffer(ServerPlayer player, int index, Market.ClaimOffer o, String notice) {
		String me = player.getStringUUID();
		List<DialogBody> body = new ArrayList<>();
		if (notice != null) {
			body.add(text(notice, notice.startsWith("Bought") ? ChatFormatting.GREEN : ChatFormatting.RED));
			Plop.play(player);
		}
		body.add(iconRow(o.icon, Component.literal(String.format(Locale.ROOT, "%,d claim blocks", o.blocks)).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)
				.append(Component.literal("\nEvery claimed block costs one. Use them in /claim.").withStyle(st -> st.withBold(false).withColor(ChatFormatting.GRAY)))));
		body.add(new PlainMessage(Component.empty()
				.append(Component.literal("Price ").withStyle(ChatFormatting.GRAY)).append(Component.literal(money(o.price)).withStyle(ChatFormatting.GOLD))
				.append(Component.literal("   You have ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(String.format(Locale.ROOT, "%,d", ClaimStore.data().available(me))).withStyle(ChatFormatting.WHITE))
				.append(Component.literal("   Seller ").withStyle(ChatFormatting.GRAY)).append(Component.literal("the server").withStyle(ChatFormatting.WHITE)), TEXT_WIDTH));
		if (o.saleEnds != 0) body.add(text("On sale until " + Pets.date(o.saleEnds) + ".", ChatFormatting.GRAY));
		int left = ClaimStore.data().blocksLeft(me, MarketStore.settings().claimBlocksMax);
		int carried = MarketStore.count(player, new ItemStack(MarketStore.currency()));
		int max = Math.min(Math.min(carried / o.price, left / o.blocks), 64);
		List<Input> inputs = new ArrayList<>();
		List<ActionButton> buttons = new ArrayList<>();
		if (left < o.blocks) {
			body.add(text(left == 0 ? "You've bought as many claim blocks as you can." : "You can only buy " + String.format(Locale.ROOT, "%,d", left) + " more claim blocks.", ChatFormatting.YELLOW));
		} else if (max == 0) {
			body.add(text("You carry " + money(carried) + ": not enough.", ChatFormatting.RED));
		} else {
			body.add(text("You carry " + money(carried) + (left == Integer.MAX_VALUE ? "." : " · you can buy " + String.format(Locale.ROOT, "%,d", left) + " more claim blocks."), ChatFormatting.GRAY));
			List<SingleOptionInput.Entry> amounts = new ArrayList<>();
			for (int n : new int[] { 1, 2, 5, 10, 20, 32, 64 }) {
				if (n > max) break;
				amounts.add(new SingleOptionInput.Entry(Integer.toString(n), Optional.of(Component.literal(String.format(Locale.ROOT, "%,d blocks for %s", (long) n * o.blocks, money(n * o.price)))), n == 1));
			}
			if (max > 1 && amounts.stream().noneMatch(e -> e.id().equals(Integer.toString(max)))) {
				amounts.add(new SingleOptionInput.Entry(Integer.toString(max), Optional.of(Component.literal(String.format(Locale.ROOT, "%,d blocks for %s (all)", (long) max * o.blocks, money(max * o.price)))), false));
			}
			inputs.add(new Input("amount", new SingleOptionInput(250, amounts, Component.literal("Buy"), true)));
			buttons.add(button("Buy", null, "claims_buy", offerArgs(index, o)));
		}
		menu(player, "Claim blocks", body, inputs, buttons, back("server", args()));
	}

	private static void buyClaimBlocks(ServerPlayer player, CompoundTag in) {
		Market.ClaimOffer o = sellsClaimBlocks() ? offer(in) : null;
		if (o == null) {
			server(player, "That listing is gone.");
			return;
		}
		int index = num(in, "offer");
		String me = player.getStringUUID();
		int n = num(in, "amount");
		if (n < 1 || n > 64) {
			claimOffer(player, index, o, null);
			return;
		}
		long blocks = (long) n * o.blocks;
		if (blocks > ClaimStore.data().blocksLeft(me, MarketStore.settings().claimBlocksMax)) {
			claimOffer(player, index, o, "You can't buy that many.");
			return;
		}
		long cost = (long) n * o.price;
		ItemStack coin = new ItemStack(MarketStore.currency());
		if (cost > Integer.MAX_VALUE || !MarketStore.take(player, coin, (int) cost)) {
			claimOffer(player, index, o, "You need " + money((int) Math.min(cost, Integer.MAX_VALUE)) + ".");
			return;
		}
		ClaimStore.data().buy(me, player.getPlainTextName(), (int) blocks, ClaimStore.settings());
		ClaimStore.touched();
		MarketStore.data().serverSale(o.id, false, String.format(Locale.ROOT, "%,d claim blocks", o.blocks), o.icon, me, player.getPlainTextName(), n, (int) cost, System.currentTimeMillis());
		MarketStore.saveNow();
		claimOffer(player, index, o, String.format(Locale.ROOT, "Bought %,d claim blocks for %s.", blocks, money((int) cost)));
	}

	/** Buying a command listing, up to 64 at once (its commands run once per one bought). */
	private static void commandOffer(ServerPlayer player, Market.CommandOffer o, String notice) {
		String me = player.getStringUUID();
		List<DialogBody> body = new ArrayList<>();
		if (notice != null) {
			body.add(text(notice, notice.startsWith("Bought") ? ChatFormatting.GREEN : ChatFormatting.RED));
			Plop.play(player);
		}
		MutableComponent title = Component.literal(o.name).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD);
		if (!o.description.isEmpty()) title.append(Component.literal("\n" + o.description).withStyle(st -> st.withBold(false).withColor(ChatFormatting.GRAY)));
		body.add(iconRow(o.icon, title));
		int bought = MarketStore.data().serverBought(o.id, me);
		MutableComponent facts = Component.empty()
				.append(Component.literal("Price ").withStyle(ChatFormatting.GRAY)).append(Component.literal(money(o.price)).withStyle(ChatFormatting.GOLD))
				.append(Component.literal("   Seller ").withStyle(ChatFormatting.GRAY)).append(Component.literal("the server").withStyle(ChatFormatting.WHITE));
		if (o.perPlayer > 0) facts.append(Component.literal("   Bought ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(bought + " of " + o.perPlayer).withStyle(ChatFormatting.WHITE));
		body.add(new PlainMessage(facts, TEXT_WIDTH));
		if (o.saleEnds != 0) body.add(text("On sale until " + Pets.date(o.saleEnds) + ".", ChatFormatting.GRAY));
		int left = o.perPlayer > 0 ? Math.max(0, o.perPlayer - bought) : Integer.MAX_VALUE;
		int carried = MarketStore.count(player, new ItemStack(MarketStore.currency()));
		int max = Math.min(Math.min(carried / o.price, left), 64);
		List<Input> inputs = new ArrayList<>();
		List<ActionButton> buttons = new ArrayList<>();
		if (left == 0) {
			body.add(text("You've bought as many as you can.", ChatFormatting.YELLOW));
		} else if (max == 0) {
			body.add(text("You carry " + money(carried) + ": not enough.", ChatFormatting.RED));
		} else {
			body.add(text("You carry " + money(carried) + ".", ChatFormatting.GRAY));
			if (max > 1) {
				List<SingleOptionInput.Entry> amounts = new ArrayList<>();
				for (int n : new int[] { 1, 2, 5, 10, 20, 32, 64 }) {
					if (n > max) break;
					amounts.add(new SingleOptionInput.Entry(Integer.toString(n), Optional.of(Component.literal(n + " for " + money(n * o.price))), n == 1));
				}
				if (amounts.stream().noneMatch(e -> e.id().equals(Integer.toString(max)))) {
					amounts.add(new SingleOptionInput.Entry(Integer.toString(max), Optional.of(Component.literal(max + " for " + money(max * o.price) + " (all)")), false));
				}
				inputs.add(new Input("amount", new SingleOptionInput(250, amounts, Component.literal("Buy"), true)));
			}
			buttons.add(button("Buy", money(o.price) + " each", "command_buy", args("id", o.id, "price", String.valueOf(o.price))));
		}
		menu(player, o.name, body, inputs, buttons, back("server", args()));
	}

	private static void buyCommand(ServerPlayer player, CompoundTag in) {
		Market.CommandOffer o = MarketStore.settings().commandOffer(str(in, "id"));
		if (o == null || !o.onSale() || o.price != num(in, "price")) {
			server(player, "That listing is gone or changed.");
			return;
		}
		String me = player.getStringUUID();
		// No amount picker when only one can be bought.
		int n = in.contains("amount") ? num(in, "amount") : 1;
		int left = o.perPlayer > 0 ? Math.max(0, o.perPlayer - MarketStore.data().serverBought(o.id, me)) : Integer.MAX_VALUE;
		if (n < 1 || n > 64) {
			commandOffer(player, o, null);
			return;
		}
		if (n > left) {
			commandOffer(player, o, "You can't buy that many.");
			return;
		}
		long cost = (long) n * o.price;
		ItemStack coin = new ItemStack(MarketStore.currency());
		if (cost > Integer.MAX_VALUE || !MarketStore.take(player, coin, (int) cost)) {
			commandOffer(player, o, "You need " + money((int) Math.min(cost, Integer.MAX_VALUE)) + ".");
			return;
		}
		// Counted before the commands run, so a command that reopens the menu sees it.
		MarketStore.data().serverSale(o.id, true, o.name, o.icon, me, player.getPlainTextName(), n, (int) cost, System.currentTimeMillis());
		MarketStore.saveNow();
		runCommands(player, o, n);
		if (player.isRemoved()) return;
		commandOffer(player, o, "Bought " + (n == 1 ? "" : n + " × ") + o.name + " for " + money((int) cost) + ".");
	}

	/** Runs the listing's commands as the console, once per one bought, with the variables filled in for the buyer. */
	private static void runCommands(ServerPlayer player, Market.CommandOffer o, int times) {
		var server = player.level().getServer();
		var pos = player.blockPosition();
		String name = player.getPlainTextName();
		eu.explorerseden.nicecontrolcenter.NiceControlCenter.LOGGER.info("[market] {} bought {} × {}: running its commands", name, times, o.name);
		for (int i = 0; i < times; i++) {
			for (String c : o.commands) {
				String cmd = c.replace("{player}", name).replace("{uuid}", player.getStringUUID())
						.replace("{x}", String.valueOf(pos.getX())).replace("{y}", String.valueOf(pos.getY())).replace("{z}", String.valueOf(pos.getZ()))
						.replace("{dimension}", player.level().dimension().identifier().toString());
				try {
					server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
				} catch (RuntimeException e) {
					eu.explorerseden.nicecontrolcenter.NiceControlCenter.LOGGER.warn("[market] Command for {} failed: {}", name, cmd, e);
				}
			}
		}
	}

	/** One entry per kind of item the player carries (same item and components). */
	private static List<ItemStack> kinds(ServerPlayer player) {
		List<ItemStack> kinds = new ArrayList<>();
		Inventory inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || ClaimMode.isTool(s)) continue;
			if (kinds.stream().noneMatch(k -> ItemStack.isSameItemSameComponents(k, s))) kinds.add(s.copyWithCount(1));
		}
		return kinds;
	}

	/** Which items the sell menu listed, in order: "slot" is a position in that list, which changes with the inventory. */
	private static String signature(List<ItemStack> kinds) {
		int h = 1;
		for (ItemStack k : kinds) h = 31 * h + ItemStack.hashItemAndComponents(k);
		return Integer.toHexString(h);
	}

	private static void sell(ServerPlayer player, String notice) {
		List<ItemStack> kinds = kinds(player);
		List<DialogBody> body = new ArrayList<>();
		if (notice != null) {
			body.add(text(notice, ChatFormatting.RED));
			Plop.play(player);
		}
		if (kinds.isEmpty()) {
			body.add(text("Your inventory is empty.", ChatFormatting.GRAY));
			menu(player, "Market · Sell", body, List.of(), List.of(), back("home", args()));
			return;
		}
		body.add(text("The price is per item, in " + MarketStore.currencyName() + ". More than a stack is fine. "
				+ "Unsold items come back to your mailbox after " + MarketStore.settings().listingDays + " days.", ChatFormatting.GRAY));
		List<SingleOptionInput.Entry> entries = new ArrayList<>();
		for (int i = 0; i < kinds.size() && i < 36; i++) {
			ItemStack k = kinds.get(i);
			entries.add(new SingleOptionInput.Entry(String.valueOf(i), Optional.of(Component.literal(k.getHoverName().getString() + " ×" + MarketStore.count(player, k))), i == 0));
		}
		List<Input> inputs = List.of(
				new Input("slot", new SingleOptionInput(250, entries, Component.literal("Item"), true)),
				new Input("amount", new TextInput(120, Component.literal("How many"), true, "", 9, Optional.empty())),
				new Input("price", new TextInput(120, Component.literal("Price each"), true, "", 9, Optional.empty())));
		menu(player, "Market · Sell", body, inputs, List.of(button("List it", null, "list", args("kinds", signature(kinds)))), back("home", args()));
	}

	private static void mine(ServerPlayer player) {
		List<Market.Listing> mine = MarketStore.data().of(player.getStringUUID());
		List<DialogBody> body = new ArrayList<>();
		if (mine.isEmpty()) body.add(text("You're not selling anything.", ChatFormatting.GRAY));
		for (Market.Listing l : mine) {
			long days = Math.max(0, (l.expires - System.currentTimeMillis()) / 86_400_000L);
			Component desc = Component.empty()
					.append(Component.literal(l.item.name).withStyle(ChatFormatting.WHITE))
					.append(Component.literal("  ×" + String.format(Locale.ROOT, "%,d", l.amount)).withStyle(ChatFormatting.GRAY))
					.append(Component.literal("\n" + money(l.price) + " each").withStyle(ChatFormatting.GOLD))
					.append(Component.literal(" · " + days + (days == 1 ? " day" : " days") + " left  ").withStyle(ChatFormatting.GRAY))
					.append(link("View", "See it or take it back", "view", args("id", l.id), ChatFormatting.AQUA));
			itemRow(player, l.item, l.amount, desc).ifPresentOrElse(body::add, () -> body.add(new PlainMessage(desc, TEXT_WIDTH)));
		}
		menu(player, "Market · My listings", body, List.of(), List.of(), back("home", args()));
	}

	private static void mail(ServerPlayer player) {
		Market.Mailbox m = MarketStore.data().mail.get(player.getStringUUID());
		List<DialogBody> body = new ArrayList<>();
		if (m == null || m.isEmpty()) {
			body.add(text("Your mailbox is empty.", ChatFormatting.GRAY));
		} else {
			if (m.currency > 0) body.add(text("Earnings: " + money(m.currency), ChatFormatting.GOLD));
			for (Market.Parcel p : m.parcels) {
				Component desc = Component.literal(p.item.name).withStyle(ChatFormatting.WHITE)
						.append(Component.literal(" ×" + String.format(Locale.ROOT, "%,d", p.amount)).withStyle(ChatFormatting.GRAY));
				itemRow(player, p.item, p.amount, desc).ifPresentOrElse(body::add, () -> body.add(new PlainMessage(desc, TEXT_WIDTH)));
			}
		}
		List<ActionButton> buttons = new ArrayList<>();
		if (m != null && !m.isEmpty()) buttons.add(button("Collect", "What fits in your inventory", "collect", args()));
		menu(player, "Market · Mailbox", body, List.of(), buttons, back("home", args()));
	}

	// ---------------------------------------------------------------- answers

	/** A button was clicked ({@code what} is the part after "market/"). Server thread. */
	public static void handle(ServerPlayer player, String what, Optional<Tag> payload) {
		if (!MarketStore.enabled()) {
			player.sendSystemMessage(Component.literal("The market is closed.").withStyle(ChatFormatting.RED));
			Plop.play(player);
			return;
		}
		CompoundTag in = payload.orElse(null) instanceof CompoundTag t ? t : new CompoundTag();
		Market.Data data = MarketStore.data();
		switch (what) {
			case "home" -> home(player, null);
			case "browse" -> {
				String q = in.contains("clear") ? "" : in.contains("q") ? str(in, "q") : str(in, "keepq");
				String sort = in.contains("sort") ? str(in, "sort") : str(in, "keepsort");
				browse(player, q, sort.isEmpty() ? "price" : sort, Math.max(0, num(in, "page")));
			}
			case "search" -> search(player, str(in, "keepq"), str(in, "keepsort").isEmpty() ? "price" : str(in, "keepsort"));
			case "sell" -> sell(player, null);
			case "mine" -> mine(player);
			case "mail" -> mail(player);
			case "view" -> {
				Market.Listing l = data.listings.get(str(in, "id"));
				if (l == null) home(player, "That listing is gone.");
				else view(player, l, null);
			}
			case "buy" -> buy(player, in);
			case "claims" -> {
				Market.ClaimOffer o = sellsClaimBlocks() ? offer(in) : null;
				if (o == null) server(player, "That listing is gone.");
				else claimOffer(player, num(in, "offer"), o, null);
			}
			case "server" -> server(player, null);
			case "command" -> {
				Market.CommandOffer o = MarketStore.settings().commandOffer(str(in, "id"));
				if (o == null || !o.onSale()) server(player, "That listing is gone.");
				else commandOffer(player, o, null);
			}
			case "command_buy" -> buyCommand(player, in);
			case "claims_buy" -> buyClaimBlocks(player, in);
			case "list" -> list(player, in);
			case "cancel" -> {
				Market.Listing l = data.listings.get(str(in, "id"));
				if (l == null || !l.seller.equals(player.getStringUUID())) {
					home(player, "That listing is gone.");
					return;
				}
				data.withdraw(l);
				MarketStore.saveNow();
				mail(player);
			}
			case "collect" -> collect(player);
			default -> {
			}
		}
	}

	private static void buy(ServerPlayer player, CompoundTag in) {
		Market.Data data = MarketStore.data();
		Market.Listing l = data.listings.get(str(in, "id"));
		if (l == null) {
			home(player, "That listing is gone.");
			return;
		}
		if (l.seller.equals(player.getStringUUID())) return;
		int amount = num(in, "amount");
		if (amount < 1) {
			view(player, l, "Type how many you want.");
			return;
		}
		if (amount > l.amount) {
			view(player, l, "Only " + l.amount + " left.");
			return;
		}
		long cost = (long) amount * l.price;
		ItemStack coin = new ItemStack(MarketStore.currency());
		if (cost > Integer.MAX_VALUE || MarketStore.count(player, coin) < cost) {
			view(player, l, "You need " + money((int) Math.min(cost, Integer.MAX_VALUE)) + ".");
			return;
		}
		ItemStack goods = MarketStore.stack(player.level().getServer(), l.item, 1);
		if (goods.isEmpty()) {
			view(player, l, "This item can't be bought any more (a mod it needs is gone).");
			return;
		}
		MarketStore.take(player, coin, (int) cost);
		long now = System.currentTimeMillis();
		Market.Item item = l.item;
		String sellerId = l.seller;
		data.buy(l, player.getStringUUID(), player.getPlainTextName(), amount, now);
		int left = MarketStore.give(player, goods, amount);
		if (left > 0) {
			// What didn't fit waits in the buyer's mailbox.
			Market.Mailbox m = data.mailbox(player.getStringUUID(), player.getPlainTextName());
			Market.Parcel p = new Market.Parcel();
			p.item = item;
			p.amount = left;
			m.parcels.add(p);
			data.mailChanged(m);
		}
		MarketStore.saveNow();
		ServerPlayer seller = player.level().getServer().getPlayerList().getPlayer(java.util.UUID.fromString(sellerId));
		if (seller != null) {
			seller.sendSystemMessage(Component.literal(player.getPlainTextName() + " bought " + amount + " × " + item.name + " for " + money((int) cost)
					+ ". Collect it in /market.").withStyle(ChatFormatting.GOLD));
			Plop.play(seller);
		}
		home(player, "Bought " + amount + " × " + item.name + " for " + money((int) cost) + (left > 0 ? "; " + left + " didn't fit and wait in your mailbox." : "."));
	}

	private static void list(ServerPlayer player, CompoundTag in) {
		List<ItemStack> kinds = kinds(player);
		int slot = num(in, "slot");
		if (slot < 0 || slot >= kinds.size()) {
			sell(player, "Pick an item.");
			return;
		}
		// The inventory changed while the menu was open (something used up or picked up): the same slot is another item now.
		if (!signature(kinds).equals(str(in, "kinds"))) {
			sell(player, "Your inventory changed: pick the item again.");
			return;
		}
		ItemStack like = kinds.get(slot);
		int amount = num(in, "amount");
		int price = num(in, "price");
		int have = MarketStore.count(player, like);
		if (amount < 1 || amount > have) {
			sell(player, amount < 1 ? "Type how many to sell." : "You only have " + have + ".");
			return;
		}
		Market.Settings s = MarketStore.settings();
		if (price < 1 || price > s.maxPrice) {
			sell(player, "The price each is 1 to " + s.maxPrice + ".");
			return;
		}
		Market.Item item = MarketStore.store(player, like);
		// Check everything before taking the items.
		if (s.bannedItems.contains(item.id)) {
			sell(player, "That item can't be sold here.");
			return;
		}
		if (MarketStore.data().of(player.getStringUUID()).size() >= s.maxListings) {
			sell(player, "You already have " + s.maxListings + " listings.");
			return;
		}
		if (!MarketStore.take(player, like, amount)) {
			sell(player, "You don't have that many any more.");
			return;
		}
		String err = MarketStore.data().list(player.getStringUUID(), player.getPlainTextName(), item, amount, price, s, System.currentTimeMillis());
		if (err != null) {
			MarketStore.give(player, like, amount);
			sell(player, err);
			return;
		}
		MarketStore.saveNow();
		home(player, "Listed " + amount + " × " + item.name + " for " + money(price) + " each.");
	}

	private static void collect(ServerPlayer player) {
		Market.Data data = MarketStore.data();
		Market.Mailbox m = data.mail.get(player.getStringUUID());
		if (m == null || m.isEmpty()) {
			mail(player);
			return;
		}
		boolean full = false;
		if (m.currency > 0) {
			int left = MarketStore.give(player, new ItemStack(MarketStore.currency()), m.currency);
			full = left > 0;
			m.currency = left;
		}
		var it = m.parcels.iterator();
		while (it.hasNext() && !full) {
			Market.Parcel p = it.next();
			ItemStack like = MarketStore.stack(player.level().getServer(), p.item, 1);
			if (like.isEmpty()) continue;
			int left = MarketStore.give(player, like, p.amount);
			if (left > 0) {
				p.amount = left;
				full = true;
			} else {
				it.remove();
			}
		}
		data.mailChanged(m);
		MarketStore.saveNow();
		home(player, full ? "Your inventory is full: the rest waits in your mailbox." : "Collected everything.");
	}
}
