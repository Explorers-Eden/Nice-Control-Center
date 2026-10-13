package eu.explorerseden.nicecontrolcenter.market;

import eu.explorerseden.nicecontrolcenter.Market;
import eu.explorerseden.nicecontrolcenter.claims.ClaimMode;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import eu.explorerseden.nicecontrolcenter.core.Plop;

/**
 * The market menu (/market), as dialogs: browse and buy, sell from your inventory, your listings and your
 * mailbox. Buttons send "nicecontrolcenter:market/&lt;what&gt;"; {@link #handle} checks everything again, so
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
		body.add(text(data.listings.size() + (data.listings.size() == 1 ? " listing" : " listings") + " for sale · paid in " + MarketStore.currencyName(), ChatFormatting.GRAY));
		if (mail != null && !mail.isEmpty()) body.add(text("Your mailbox: " + (mail.currency > 0 ? money(mail.currency) : "") + (mail.currency > 0 && !mail.parcels.isEmpty() ? " and " : "")
				+ (mail.parcels.isEmpty() ? "" : mail.parcels.size() + " parcel" + (mail.parcels.size() == 1 ? "" : "s")), ChatFormatting.GOLD));
		List<ActionButton> buttons = new ArrayList<>();
		buttons.add(button("Browse", "Everything for sale", "browse", args("page", "0")));
		buttons.add(button("Sell", "List items from your inventory", "sell", args()));
		buttons.add(button("My listings (" + data.of(me).size() + ")", null, "mine", args()));
		buttons.add(button("Mailbox" + (mail != null && !mail.isEmpty() ? " ●" : ""), "Your earnings and items that came back", "mail", args()));
		if (eu.explorerseden.nicecontrolcenter.pets.PetStore.enabled()) buttons.add(eu.explorerseden.nicecontrolcenter.pets.PetDialogs.button("Pets", "Floating heads that follow you", "shop", args("page", "0")));
		menu(player, "Market", body, List.of(), buttons);
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
					.append(Component.literal(" · " + (own ? "you" : l.sellerName) + "  ").withStyle(ChatFormatting.DARK_GRAY))
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
			body.add(text("Your listing · " + days + (days == 1 ? " day" : " days") + " left. Taking it back sends it to your mailbox.", ChatFormatting.DARK_GRAY));
			buttons.add(button("Take it back", "Into your mailbox", "cancel", args("id", l.id)));
			menu(player, l.item.name, body, List.of(), buttons, back("mine", args()));
			return;
		}
		int carried = MarketStore.count(player, new ItemStack(MarketStore.currency()));
		int afford = Math.min(carried / Math.max(1, l.price), l.amount);
		body.add(afford > 0 ? text("You carry " + money(carried) + ": enough for " + String.format(Locale.ROOT, "%,d", afford) + ".", ChatFormatting.DARK_GRAY)
				: text("You carry " + money(carried) + ": not enough for one.", ChatFormatting.RED));
		List<Input> inputs = List.of(new Input("amount", new TextInput(120, Component.literal("How many"), true, "1", 9, Optional.empty())));
		buttons.add(button("Buy", money(l.price) + " each", "buy", args("id", l.id)));
		menu(player, l.item.name, body, inputs, buttons, back("browse", args("page", "0")));
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
		menu(player, "Market · Sell", body, inputs, List.of(button("List it", null, "list", args())), back("home", args()));
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
					.append(Component.literal(" · " + days + (days == 1 ? " day" : " days") + " left  ").withStyle(ChatFormatting.DARK_GRAY))
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
