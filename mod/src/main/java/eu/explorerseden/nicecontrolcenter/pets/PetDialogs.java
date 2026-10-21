package eu.explorerseden.nicecontrolcenter.pets;

import eu.explorerseden.nicecontrolcenter.Pets;
import eu.explorerseden.nicecontrolcenter.core.Plop;
import eu.explorerseden.nicecontrolcenter.market.MarketStore;
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
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.input.SingleOptionInput;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.ItemBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The pet menus, as dialogs: your pets (take one out, put it away, how it moves) and the pet shop (also
 * opened from /market). Buttons send "nicecontrolcenter:pets/&lt;what&gt;"; {@link #handle} checks everything
 * again, so nothing in the payload is trusted.
 */
public final class PetDialogs {
	private static final int WIDTH = 150;
	private static final int TEXT_WIDTH = 310;
	private static final int PAGE = 10;

	private PetDialogs() {
	}

	// ---------------------------------------------------------------- building blocks

	private static CompoundTag args(String... pairs) {
		CompoundTag tag = new CompoundTag();
		for (int i = 0; i + 1 < pairs.length; i += 2) tag.putString(pairs[i], pairs[i + 1]);
		return tag;
	}

	/** A button that sends "pets/&lt;action&gt;". */
	public static ActionButton button(String label, String tooltip, String action, CompoundTag args) {
		return new ActionButton(new CommonButtonData(Component.literal(label), tooltip == null ? Optional.empty() : Optional.of(Component.literal(tooltip)), WIDTH),
				Optional.of(new CustomAll(Identifier.fromNamespaceAndPath("nicecontrolcenter", "pets/" + action), Optional.of(args))));
	}

	private static DialogBody text(String s, ChatFormatting color) {
		return new PlainMessage(Component.literal(s).withStyle(color), TEXT_WIDTH);
	}

	/** A clickable label that looks like a small button ("[ View ]"). */
	private static MutableComponent link(String label, String hover, String action, CompoundTag args, ChatFormatting color) {
		return Component.literal("[ " + label + " ]").withStyle(st -> st.withColor(color).withBold(true)
				.withClickEvent(new ClickEvent.Custom(Identifier.fromNamespaceAndPath("nicecontrolcenter", "pets/" + action), Optional.of(args)))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	private static void menu(ServerPlayer player, String title, List<DialogBody> body, List<ActionButton> buttons, ActionButton exit) {
		menu(player, title, body, List.of(), buttons, exit);
	}

	private static void menu(ServerPlayer player, String title, List<DialogBody> body, List<Input> inputs, List<ActionButton> buttons, ActionButton exit) {
		CommonDialogData common = new CommonDialogData(Component.literal(title), Optional.empty(), true, false, DialogAction.CLOSE, body, inputs);
		if (buttons.isEmpty()) player.openDialog(Holder.direct(new NoticeDialog(common, exit)));
		else player.openDialog(Holder.direct(new MultiActionDialog(common, buttons, Optional.of(exit), 2)));
	}

	private static ActionButton close() {
		return new ActionButton(new CommonButtonData(Component.literal("Close"), WIDTH), Optional.empty());
	}

	/** A pet row: its head and a description next to it. */
	/** A player head with this skin (EMPTY when the value can't be used), also for the market's head icons. */
	public static ItemStack head(ServerPlayer player, String texture) {
		return PetFollowers.head(player.level().getServer(), texture);
	}

	private static DialogBody headRow(ServerPlayer player, String texture, Component description) {
		ItemStack head = PetFollowers.head(player.level().getServer(), texture);
		if (head.isEmpty()) return new PlainMessage(description, TEXT_WIDTH);
		return new ItemBody(ItemStackTemplate.fromNonEmptyStack(head), Optional.of(new PlainMessage(description, TEXT_WIDTH - 30)), false, false, 16, 16);
	}

	private static String money(int n) {
		return n == 0 ? "free" : amount(n);
	}

	/** When the shop stops selling a pet, if it does. */
	private static String saleLine(Pets.Pet pet) {
		return pet.saleEnds == 0 ? null : "on sale until " + Pets.date(pet.saleEnds);
	}

	private static String str(CompoundTag t, String k) {
		return t.getStringOr(k, "");
	}

	/** A line on top after something happened: green when it starts with a tick, else red. */
	private static void notice(ServerPlayer player, List<DialogBody> body, String notice) {
		if (notice == null) return;
		body.add(text(notice, notice.startsWith("✔") ? ChatFormatting.GREEN : ChatFormatting.RED));
		Plop.play(player);
	}

	/** "Perks: Lantern from level 2 · Fetch from level 5" (empty when perks are off or it has none). */
	private static String perkLine(Pets.Pet pet) {
		Pets.PerkSettings k = PetStore.settings().perks;
		if (pet == null || !k.enabled || pet.perks.isEmpty()) return "";
		StringBuilder b = new StringBuilder("Perks: ");
		for (int i = 0; i < pet.perks.size(); i++) {
			Pets.Perk perk = Pets.Perk.of(pet.perks.get(i));
			if (perk == null) continue;
			if (i > 0) b.append(" · ");
			b.append(perk.label);
			int at = Pets.slotLevel(i, k);
			if (at > 0) b.append(" from level ").append(at);
		}
		return b.toString();
	}

	/** "Bond level 3 · 1 h 20 min to level 4" (empty when perks are off). */
	private static String levelLine(Pets.Owned o) {
		Pets.PerkSettings k = PetStore.settings().perks;
		if (!k.enabled) return "";
		int level = Pets.level(o.bond, k);
		if (level >= k.maxLevel) return "Bond level " + level + " (the highest)";
		long left = Math.max(1, Pets.minutesFor(level + 1, k) - o.bond / 1200);
		return "Bond level " + level + " · " + (left >= 60 ? left / 60 + " h " + (left % 60 > 0 ? left % 60 + " min" : "") : left + " min").strip() + " out together to level " + (level + 1);
	}

	private static List<Pets.Pet> shopPets() {
		List<Pets.Pet> out = new ArrayList<>();
		for (Pets.Pet p : PetStore.settings().pets) if (p.onSale() && PetStore.data().left(p) != 0) out.add(p);
		return out;
	}

	// ---------------------------------------------------------------- menus

	public static void home(ServerPlayer player, String notice) {
		Pets.Owner owner = PetStore.data().owners.get(player.getStringUUID());
		List<Pets.Owned> mine = owner == null ? List.of() : owner.pets;
		List<DialogBody> body = new ArrayList<>();
		notice(player, body, notice);
		List<ActionButton> buttons = new ArrayList<>();
		if (mine.isEmpty()) {
			body.add(text("You don't have a pet yet: a floating head that follows you around and reacts to what you do. Get one in the pet shop.", ChatFormatting.GRAY));
		} else {
			Pets.Owned shown = shown(player, owner);
			if (shown == null) body.add(text("No pet is out.", ChatFormatting.GRAY));
			else body.add(headRow(player, PetStore.texture(shown), Component.literal("Out now: ").withStyle(ChatFormatting.GRAY).append(petTitle(shown))));
			buttons.add(button("Your pets (" + mine.size() + ")", "Take one out or put it away", "mine", args()));
		}
		if (mine.size() > 1) {
			buttons.add(button("Random pet: " + (owner.random ? "on" : "off"), owner.random ? "Show the pet you take out instead"
					: "A different one of your pets every time you teleport, die or take it out", "random", args()));
		}
		if (!mine.isEmpty()) {
			buttons.add(button("Moves: " + Pets.Behavior.of(owner.behavior).label, "Pick how your pet moves", "moves", args()));
			if (PetStore.settings().particles) buttons.add(button("Particles: " + Pets.Trail.of(owner.trail).label, "Pick a trail for your pet", "trails", args()));
			Pets.Owned out = shown(player, owner);
			if (out != null && PetStore.settings().perks.enabled && !PetStore.perks(out).isEmpty()) buttons.add(button("Perks", "What " + PetStore.displayName(out) + " helps with", "perks", args()));
			if (out != null && PetPerks.active(player, Pets.Perk.PACK_MULE)) buttons.add(button("Bag", "Open the bag " + PetStore.displayName(out) + " carries for you (also /pets bag)", "bag", args()));
			if (PetStore.settings().names) buttons.add(button("Name above pet: " + (owner.showName ? "shown" : "hidden"),
					owner.showName ? "Nobody sees your pets' names" : "Everyone near you sees your pet's name (give it one under Your pets)", "showname", args()));
			if (PetStore.settings().reactions.enabled) buttons.add(button("Reactions: " + (owner.reactions ? "on" : "off"),
					owner.reactions ? "Your pets stop reacting to what happens (and stop warning you)" : "Your pets react to what happens and warn you of danger", "reactions", args()));
		}
		buttons.add(button("Pet shop", "Pets you can get", "shop", args("page", "0")));
		menu(player, "Pets", body, buttons, close());
	}

	/** "Biscuit (Fox)": the name its owner gave it in its colour and the shop name, or just the shop name. */
	private static MutableComponent petTitle(Pets.Owned o) {
		if (o.nickname.isEmpty()) return Component.literal(PetStore.name(o)).withStyle(ChatFormatting.WHITE);
		return Component.literal(o.nickname).withColor(Math.max(0, eu.explorerseden.nicecontrolcenter.Claims.parseColor(o.nameColor)))
				.append(Component.literal(" (" + PetStore.name(o) + ")").withStyle(ChatFormatting.GRAY));
	}

	/** The pet showing now (with Random pet, the one it rolled), or null. */
	private static Pets.Owned shown(ServerPlayer player, Pets.Owner owner) {
		if (owner == null || owner.active == null) return null;
		return owner.owned(owner.random ? PetFollowers.shownPet(player.getUUID()) : owner.active);
	}

	/** The player's pets, each with Take out or Put away. */
	private static void mine(ServerPlayer player, String notice) {
		Pets.Owner owner = PetStore.data().owners.get(player.getStringUUID());
		if (owner == null || owner.pets.isEmpty()) {
			home(player, notice);
			return;
		}
		List<DialogBody> body = new ArrayList<>();
		notice(player, body, notice);
		Pets.Owned shown = shown(player, owner);
		for (Pets.Owned o : owner.pets) {
			boolean out = shown != null && o.pet.equals(shown.pet);
			String extra = String.join("\n", java.util.stream.Stream.of(PetStore.description(o), levelLine(o), perkLine(PetStore.settings().pet(o.pet)))
					.filter(x -> !x.isEmpty()).toList());
			MutableComponent title = petTitle(o);
			MutableComponent desc = Component.empty()
					.append(title)
					.append(out ? Component.literal("  out now").withStyle(ChatFormatting.GREEN) : Component.empty())
					.append(Component.literal("\n" + (extra.isEmpty() ? "" : extra + "\n")).withStyle(ChatFormatting.GRAY))
					.append(out ? link("Put away", "It stops following you", "away", args("id", o.pet), ChatFormatting.YELLOW)
							: link("Take out", "It follows you around; everyone near you sees it", "out", args("id", o.pet), ChatFormatting.GREEN));
			if (PetStore.settings().names) desc.append(Component.literal("  ")).append(link("Name", "Give it a name and a colour (shown above it)", "name", args("id", o.pet), ChatFormatting.AQUA));
			body.add(headRow(player, PetStore.texture(o), desc));
		}
		menu(player, "Pets · Your pets", body, List.of(), button("Back", "Back to the pets menu", "home", args()));
	}

	/** The shown pet's perks: what each does, from which level, and on/off for the owner. */
	private static void perks(ServerPlayer player, String notice) {
		Pets.Owner owner = PetStore.data().owners.get(player.getStringUUID());
		Pets.Owned out = shown(player, owner);
		if (out == null || !PetStore.settings().perks.enabled) {
			home(player, notice);
			return;
		}
		Pets.PerkSettings k = PetStore.settings().perks;
		int level = Pets.level(out.bond, k);
		List<DialogBody> body = new ArrayList<>();
		notice(player, body, notice);
		body.add(text(levelLine(out) + ". Perks only help while you're not AFK.", ChatFormatting.GRAY));
		List<Pets.Perk> perks = PetStore.perks(out);
		for (int i = 0; i < perks.size(); i++) {
			Pets.Perk perk = perks.get(i);
			int at = Pets.slotLevel(i, k);
			boolean open = level >= at;
			boolean off = owner.perksOff.contains(perk.name());
			MutableComponent row = Component.empty()
					.append(Component.literal(perk.label).withStyle(open ? ChatFormatting.WHITE : ChatFormatting.GRAY))
					.append(Component.literal(open ? (off ? "  off" : "  on") : "  from bond level " + at).withStyle(open && !off ? ChatFormatting.GREEN : ChatFormatting.GRAY))
					.append(Component.literal("\n" + perk.description + "  ").withStyle(ChatFormatting.GRAY));
			if (open) row.append(off ? link("Switch on", "Let it help with this again", "perk", args("perk", perk.name()), ChatFormatting.GREEN)
					: link("Switch off", "It stops helping with this (for all your pets)", "perk", args("perk", perk.name()), ChatFormatting.YELLOW));
			body.add(new PlainMessage(row, TEXT_WIDTH));
		}
		menu(player, "Pets · " + PetStore.displayName(out) + "'s perks", body, List.of(), button("Back", "Back to the pets menu", "home", args()));
	}

	/** Name and name colour for one of the player's pets (like a claim's). */
	private static void name(ServerPlayer player, Pets.Owned o, String notice) {
		List<DialogBody> body = new ArrayList<>();
		if (notice != null) {
			body.add(text(notice, ChatFormatting.RED));
			Plop.play(player);
		}
		body.add(text("Shown above " + PetStore.name(o) + " for everyone nearby (hidden while you sneak). Leave it empty for no name.", ChatFormatting.GRAY));
		int current = Math.max(0, eu.explorerseden.nicecontrolcenter.Claims.parseColor(o.nameColor));
		List<SingleOptionInput.Entry> colors = new ArrayList<>();
		boolean matched = false;
		for (Map.Entry<String, String> e : PetStore.settings().nameColors.entrySet()) {
			int rgb = eu.explorerseden.nicecontrolcenter.Claims.parseColor(e.getValue());
			if (rgb < 0) continue;
			boolean now = rgb == current && !matched;
			matched |= now;
			colors.add(new SingleOptionInput.Entry(eu.explorerseden.nicecontrolcenter.Claims.hex(rgb), Optional.of(Component.literal("■ " + e.getKey()).withColor(rgb)), now));
		}
		if (!matched) colors.add(0, new SingleOptionInput.Entry(eu.explorerseden.nicecontrolcenter.Claims.hex(current), Optional.of(Component.literal("■ Current").withColor(current)), true));
		List<Input> inputs = List.of(
				new Input("name", new TextInput(250, Component.literal("Name"), true, o.nickname, 16, Optional.empty())),
				new Input("color", new SingleOptionInput(250, colors, Component.literal("Colour"), true)),
				new Input("hex", new TextInput(250, Component.literal("Or your own colour (#RRGGBB)"), true, "", 7, Optional.empty())));
		menu(player, "Pets · Name " + PetStore.name(o), body, inputs, List.of(button("Save", "Save the name and its colour", "name_save", args("id", o.pet))), button("Back", "Back to your pets, without saving", "mine", args()));
	}

	private static void moves(ServerPlayer player, String notice) {
		Pets.Owner owner = PetStore.data().owners.get(player.getStringUUID());
		Pets.Behavior now = Pets.Behavior.of(owner == null ? null : owner.behavior);
		List<DialogBody> body = new ArrayList<>();
		notice(player, body, notice);
		body.add(text("How your pet moves: " + now.label + " · " + now.description.toLowerCase(Locale.ROOT) + ". Hover a button to see what it does.", ChatFormatting.GRAY));
		List<ActionButton> buttons = new ArrayList<>();
		for (Pets.Behavior b : Pets.Behavior.values()) buttons.add(button((b == now ? "● " : "") + b.label, b.description, "move", args("how", b.name())));
		menu(player, "Pets · Moves", body, buttons, button("Back", "Back to the pets menu", "home", args()));
	}

	private static void trails(ServerPlayer player, String notice) {
		Pets.Owner owner = PetStore.data().owners.get(player.getStringUUID());
		Pets.Trail now = Pets.Trail.of(owner == null ? null : owner.trail);
		List<DialogBody> body = new ArrayList<>();
		notice(player, body, notice);
		body.add(text("Particles that rise off your pet: " + now.label + ".", ChatFormatting.GRAY));
		List<ActionButton> buttons = new ArrayList<>();
		for (Pets.Trail t : Pets.Trail.values()) {
			if (!PetFollowers.available(t)) continue;
			buttons.add(button((t == now ? "● " : "") + t.label, t == Pets.Trail.RANDOM ? "A different one every time you teleport, die or take your pet out" : null, "trail", args("how", t.name())));
		}
		menu(player, "Pets · Particles", body, buttons, button("Back", "Back to the pets menu", "home", args()));
	}

	/** A pet's row in a shop list: its head, price and a link to its page. */
	private static DialogBody shopRow(ServerPlayer player, Pets.Pet pet, boolean market) {
		Pets.Owner owner = PetStore.data().owners.get(player.getStringUUID());
		int left = PetStore.data().left(pet);
		boolean have = owner != null && owner.owned(pet.id) != null;
		Component desc = Component.empty()
				.append(Component.literal(pet.name).withStyle(ChatFormatting.WHITE))
				.append(have ? Component.literal("  yours").withStyle(ChatFormatting.GREEN) : Component.empty())
				.append(Component.literal("\n" + (pet.price == 0 ? "Free" : amount(pet.price))).withStyle(ChatFormatting.GOLD))
				.append(Component.literal((saleLine(pet) != null ? " · until " + Pets.date(pet.saleEnds) : "") + (left > 0 ? " · " + left + " left" : "") + "  ").withStyle(ChatFormatting.GRAY))
				.append(perkLine(pet).isEmpty() ? Component.empty() : Component.literal("\n" + perkLine(pet) + "  ").withStyle(ChatFormatting.GRAY))
				// Like the market's listings: Buy, or View for one you already have.
				.append(have ? link("View", "You have it already", "item", market ? args("id", pet.id, "from", "market") : args("id", pet.id), ChatFormatting.AQUA)
						: link("Buy", "See it and buy", "item", market ? args("id", pet.id, "from", "market") : args("id", pet.id), ChatFormatting.GREEN));
		return headRow(player, pet.texture, desc);
	}

	/** Are pets for sale right now (for the market's Server shop)? */
	public static boolean forSale() {
		return PetStore.enabled() && !shopPets().isEmpty();
	}

	/** The pets for sale, as rows for the market's Server shop. */
	public static List<DialogBody> serverShopRows(ServerPlayer player) {
		List<DialogBody> out = new ArrayList<>();
		if (PetStore.enabled()) for (Pets.Pet pet : shopPets()) out.add(shopRow(player, pet, true));
		return out;
	}

	public static void shop(ServerPlayer player, int page, String notice) {
		List<Pets.Pet> pets = shopPets();
		int pages = Math.max(1, (pets.size() + PAGE - 1) / PAGE);
		int p = Math.max(0, Math.min(page, pages - 1));
		List<DialogBody> body = new ArrayList<>();
		notice(player, body, notice);
		if (pets.isEmpty()) {
			body.add(text("There are no pets for sale right now.", ChatFormatting.GRAY));
		} else {
			body.add(text("Floating heads that follow you · paid in " + MarketStore.currencyName(), ChatFormatting.GRAY));
			if (pages > 1) {
				MutableComponent nav = Component.empty();
				if (p > 0) nav.append(link("←", "Page " + p, "shop", args("page", String.valueOf(p - 1)), ChatFormatting.YELLOW));
				else nav.append(Component.literal("[ ← ]").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.BOLD));
				nav.append(Component.literal("  " + (p + 1) + " / " + pages + "  ").withStyle(ChatFormatting.GRAY));
				if (p < pages - 1) nav.append(link("→", "Page " + (p + 2), "shop", args("page", String.valueOf(p + 1)), ChatFormatting.YELLOW));
				else nav.append(Component.literal("[ → ]").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.BOLD));
				body.add(new PlainMessage(nav, TEXT_WIDTH));
			}
			for (Pets.Pet pet : pets.subList(p * PAGE, Math.min(pets.size(), (p + 1) * PAGE))) body.add(shopRow(player, pet, false));
		}
		menu(player, "Pet shop", body, List.of(), button("Back", "Back to the pets menu", "home", args()));
	}

	/** A pet's page; market: opened from the market's Server shop (Back goes there). */
	private static void item(ServerPlayer player, Pets.Pet pet, String notice, boolean market) {
		Pets.Data data = PetStore.data();
		Pets.Owner owner = data.owners.get(player.getStringUUID());
		Pets.Owned have = owner == null ? null : owner.owned(pet.id);
		int left = data.left(pet);
		List<DialogBody> body = new ArrayList<>();
		notice(player, body, notice);
		body.add(headRow(player, pet.texture, Component.literal(pet.name).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)
				.append(Component.literal("\n" + (pet.description.isEmpty() ? "A floating head that follows you" : pet.description)).withStyle(st -> st.withBold(false).withColor(ChatFormatting.GRAY)))));
		// The facts in one line: label grey, value bright.
		MutableComponent facts = Component.empty()
				.append(Component.literal("Price ").withStyle(ChatFormatting.GRAY)).append(Component.literal(pet.price == 0 ? "Free" : amount(pet.price)).withStyle(ChatFormatting.GOLD));
		if (pet.saleEnds != 0) facts.append(Component.literal("   Sold until ").withStyle(ChatFormatting.GRAY)).append(Component.literal(Pets.date(pet.saleEnds)).withStyle(ChatFormatting.WHITE));
		if (left > 0) facts.append(Component.literal("   Left ").withStyle(ChatFormatting.GRAY)).append(Component.literal(String.valueOf(left)).withStyle(ChatFormatting.WHITE));
		body.add(new PlainMessage(facts, TEXT_WIDTH));
		if (!perkLine(pet).isEmpty()) {
			body.add(text(perkLine(pet) + " (bond levels grow while it's out with you)", ChatFormatting.GRAY));
			for (String n : pet.perks) {
				Pets.Perk perk = Pets.Perk.of(n);
				if (perk != null) body.add(text("· " + perk.label + ": " + perk.description, ChatFormatting.GRAY));
			}
		}
		String why = data.cannotBuy(player.getStringUUID(), pet);
		if (have != null) {
			body.add(text("✔ You have it.", ChatFormatting.GREEN));
		} else if (why != null) {
			body.add(text(why, ChatFormatting.GRAY));
		} else if (pet.price > 0) {
			int carried = MarketStore.count(player, new ItemStack(MarketStore.currency()));
			body.add(carried >= pet.price ? text("You carry " + amount(carried) + ".", ChatFormatting.GRAY)
					: text("You carry " + amount(carried) + ": " + amount(pet.price - carried) + " more needed.", ChatFormatting.RED));
		}
		List<ActionButton> buttons = new ArrayList<>();
		if (why == null) {
			String price = pet.price == 0 ? "free" : amount(pet.price);
			String label = pet.price == 0 ? "Get it free" : "Buy · " + price;
			buttons.add(button(label, pet.price == 0 ? "It's yours for good" : "Pay " + price + " from your inventory; it's yours for good", "buy", market ? args("id", pet.id, "from", "market") : args("id", pet.id)));
		}
		ActionButton back = market ? new ActionButton(new CommonButtonData(Component.literal("Back"), Optional.of(Component.literal("Back to the server shop")), WIDTH),
				Optional.of(new CustomAll(Identifier.fromNamespaceAndPath("nicecontrolcenter", "market/server"), Optional.of(new CompoundTag()))))
				: button("Back", "Back to the pet shop", "shop", args("page", "0"));
		menu(player, pet.name, body, buttons, back);
	}

	/** "1 diamond", "12 diamonds", "0 diamonds" (never "free"). */
	private static String amount(int n) {
		String name = MarketStore.currencyName();
		return String.format(Locale.ROOT, "%,d %s", n, n == 1 ? name.replaceAll("s$", "") : name);
	}

	// ---------------------------------------------------------------- answers

	/** A button was clicked ({@code what} is the part after "pets/"). Server thread. */
	public static void handle(ServerPlayer player, String what, Optional<Tag> payload) {
		if (!PetStore.enabled()) {
			player.sendSystemMessage(Component.literal("There are no pets on this server.").withStyle(ChatFormatting.RED));
			Plop.play(player);
			return;
		}
		CompoundTag in = payload.orElse(null) instanceof CompoundTag t ? t : new CompoundTag();
		Pets.Data data = PetStore.data();
		Pets.Owner owner = data.owners.get(player.getStringUUID());
		Pets.Owned owned = owner == null ? null : owner.owned(str(in, "id"));
		switch (what) {
			case "home" -> home(player, null);
			case "mine" -> mine(player, null);
			case "shop" -> {
				int page;
				try {
					page = Integer.parseInt(str(in, "page"));
				} catch (NumberFormatException e) {
					page = 0;
				}
				shop(player, page, null);
			}
			case "item" -> {
				Pets.Pet pet = PetStore.settings().pet(str(in, "id"));
				if (pet == null || !pet.onSale()) shop(player, 0, "That pet isn't for sale any more.");
				else item(player, pet, null, "market".equals(str(in, "from")));
			}
			case "out" -> {
				if (owned == null) {
					mine(player, "You don't have that pet any more.");
					return;
				}
				owner.active = owned.pet;
				// Picking one pet means that one, not a random one.
				owner.random = false;
				PetStore.changed();
				mine(player, "✔ " + PetStore.name(owned) + " is out.");
			}
			case "random" -> {
				if (owner == null || owner.pets.size() < 2) {
					home(player, null);
					return;
				}
				owner.random = !owner.random;
				// Takes a pet out if none is, so you see it right away.
				if (owner.random && owner.active == null) owner.takeOut();
				PetStore.changed();
				home(player, owner.random ? "✔ Random pet: a different one of your pets every time you teleport, die or take it out." : "✔ Random pet is off.");
			}
			case "away" -> {
				if (owner != null) owner.putAway();
				PetStore.changed();
				mine(player, null);
			}
			case "moves", "view" -> {
				if (owner == null || owner.pets.isEmpty()) home(player, null);
				else moves(player, null);
			}
			case "move" -> {
				if (owner == null || owner.pets.isEmpty()) {
					home(player, null);
					return;
				}
				Pets.Behavior b = Pets.Behavior.of(str(in, "how"));
				owner.behavior = b.name();
				// Takes a pet out if none is, so you see it right away.
				if (owner.active == null) owner.active = owner.pets.get(0).pet;
				PetStore.changed();
				moves(player, "✔ Your pet moves like this now: " + b.label + ".");
			}
			case "trails" -> {
				if (owner == null || owner.pets.isEmpty() || !PetStore.settings().particles) home(player, null);
				else trails(player, null);
			}
			case "trail" -> {
				if (owner == null || owner.pets.isEmpty() || !PetStore.settings().particles) {
					home(player, null);
					return;
				}
				Pets.Trail t = Pets.Trail.of(str(in, "how"));
				owner.trail = t.name();
				if (owner.active == null) owner.active = owner.pets.get(0).pet;
				PetStore.changed();
				trails(player, t == Pets.Trail.OFF ? "✔ No particles." : "✔ Particles: " + t.label + ".");
			}
			case "buy" -> buy(player, PetStore.settings().pet(str(in, "id")), "market".equals(str(in, "from")));
			case "perks" -> perks(player, null);
			case "bag" -> {
				String why = PetBag.open(player);
				if (why != null) home(player, why);
			}
			case "perk" -> {
				Pets.Perk perk = Pets.Perk.of(str(in, "perk"));
				if (owner == null || perk == null) {
					home(player, null);
					return;
				}
				boolean off = !owner.perksOff.remove(perk.name());
				if (off) owner.perksOff.add(perk.name());
				PetStore.changed();
				perks(player, "✔ " + perk.label + (off ? " is off." : " is on."));
			}
			case "reactions" -> {
				if (owner == null) {
					home(player, null);
					return;
				}
				owner.reactions = !owner.reactions;
				PetStore.changed();
				home(player, owner.reactions ? "✔ Your pets react to what happens again." : "✔ Your pets don't react any more.");
			}
			case "showname" -> {
				if (owner == null || !PetStore.settings().names) {
					home(player, null);
					return;
				}
				owner.showName = !owner.showName;
				PetStore.changed();
				home(player, owner.showName ? "✔ Your pet's name shows above it." : "✔ Your pet's name is hidden.");
			}
			case "name" -> {
				if (owned == null || !PetStore.settings().names) mine(player, null);
				else name(player, owned, null);
			}
			case "name_save" -> {
				if (owned == null || !PetStore.settings().names) {
					mine(player, null);
					return;
				}
				String hex = str(in, "hex").strip();
				int rgb = hex.isEmpty() ? eu.explorerseden.nicecontrolcenter.Claims.parseColor(str(in, "color")) : eu.explorerseden.nicecontrolcenter.Claims.parseColor(hex);
				if (rgb < 0) {
					name(player, owned, "\"" + hex + "\" isn't a colour like #3FC9E8.");
					return;
				}
				owned.nickname = Pets.nickname(str(in, "name"));
				owned.nameColor = eu.explorerseden.nicecontrolcenter.Claims.hex(rgb);
				PetStore.changed();
				mine(player, owned.nickname.isEmpty() ? "✔ " + PetStore.name(owned) + " has no name now." : "✔ " + PetStore.name(owned) + " is called " + owned.nickname + " now.");
			}
			default -> {
			}
		}
	}

	private static void buy(ServerPlayer player, Pets.Pet pet, boolean market) {
		if (pet == null) {
			shop(player, 0, "That pet isn't for sale any more.");
			return;
		}
		Pets.Data data = PetStore.data();
		String why = data.cannotBuy(player.getStringUUID(), pet);
		if (why != null) {
			item(player, pet, why, market);
			return;
		}
		ItemStack coin = new ItemStack(MarketStore.currency());
		if (pet.price > 0 && !MarketStore.take(player, coin, pet.price)) {
			item(player, pet, "You need " + money(pet.price) + ".", market);
			return;
		}
		data.buy(player.getStringUUID(), player.getPlainTextName(), pet, System.currentTimeMillis());
		Pets.Owner owner = data.owners.get(player.getStringUUID());
		// A first pet comes out right away.
		boolean out = owner.active == null;
		if (out) owner.active = pet.id;
		PetStore.saveNow();
		home(player, "✔ " + (pet.price == 0 ? "Got " : "Bought ") + pet.name + (pet.price == 0 ? "" : " for " + money(pet.price)) + "." + (out ? " It's out now." : ""));
	}
}
