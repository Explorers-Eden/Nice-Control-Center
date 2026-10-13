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
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.ItemBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault());

	private PetDialogs() {
	}

	// ---------------------------------------------------------------- building blocks

	private static CompoundTag args(String... pairs) {
		CompoundTag tag = new CompoundTag();
		for (int i = 0; i + 1 < pairs.length; i += 2) tag.putString(pairs[i], pairs[i + 1]);
		return tag;
	}

	/** A button that sends "pets/&lt;action&gt;" (public for the market's Pets button). */
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
		CommonDialogData common = new CommonDialogData(Component.literal(title), Optional.empty(), true, false, DialogAction.CLOSE, body, List.of());
		if (buttons.isEmpty()) player.openDialog(Holder.direct(new NoticeDialog(common, exit)));
		else player.openDialog(Holder.direct(new MultiActionDialog(common, buttons, Optional.of(exit), 2)));
	}

	private static ActionButton close() {
		return new ActionButton(new CommonButtonData(Component.literal("Close"), WIDTH), Optional.empty());
	}

	/** A pet row: its head and a description next to it. */
	private static DialogBody headRow(ServerPlayer player, String texture, Component description) {
		ItemStack head = PetFollowers.head(player.level().getServer(), texture);
		if (head.isEmpty()) return new PlainMessage(description, TEXT_WIDTH);
		return new ItemBody(ItemStackTemplate.fromNonEmptyStack(head), Optional.of(new PlainMessage(description, TEXT_WIDTH - 30)), false, false, 16, 16);
	}

	private static String money(int n) {
		return n == 0 ? "free" : amount(n);
	}

	private static String left(Pets.Owned o) {
		if (o.expires == 0) return "yours forever";
		long ms = o.expires - System.currentTimeMillis();
		if (ms < 3_600_000L) return "less than an hour left";
		if (ms < 86_400_000L) return (ms / 3_600_000L) + " hours left";
		long days = ms / 86_400_000L;
		return days + (days == 1 ? " day" : " days") + " left (until " + DATE.format(Instant.ofEpochMilli(o.expires)) + ")";
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
		if (mine.isEmpty()) {
			body.add(text("You don't have a pet yet: a floating head that follows you around, just for looks. Get one in the pet shop.", ChatFormatting.GRAY));
		} else {
			for (Pets.Owned o : mine) {
				boolean out = o.pet.equals(owner.active);
				MutableComponent desc = Component.empty()
						.append(Component.literal(PetStore.name(o)).withStyle(ChatFormatting.WHITE))
						.append(out ? Component.literal("  out now").withStyle(ChatFormatting.GREEN) : Component.empty())
						.append(Component.literal("\n" + left(o) + "  ").withStyle(ChatFormatting.DARK_GRAY))
						.append(out ? link("Put away", "It stops following you", "away", args("id", o.pet), ChatFormatting.YELLOW)
								: link("Take out", "It follows you around; everyone near you sees it", "out", args("id", o.pet), ChatFormatting.GREEN));
				body.add(headRow(player, PetStore.texture(o), desc));
			}
		}
		List<ActionButton> buttons = new ArrayList<>();
		if (!mine.isEmpty()) {
			buttons.add(button("Moves: " + Pets.Behavior.of(owner.behavior).label, "Pick how your pet moves", "moves", args()));
			if (PetStore.settings().particles) buttons.add(button("Particles: " + Pets.Trail.of(owner.trail).label, "Pick a trail for your pet", "trails", args()));
		}
		buttons.add(button("Pet shop", "Pets you can get", "shop", args("page", "0")));
		menu(player, "Pets", body, buttons, close());
	}

	private static void moves(ServerPlayer player, String notice) {
		Pets.Owner owner = PetStore.data().owners.get(player.getStringUUID());
		Pets.Behavior now = Pets.Behavior.of(owner == null ? null : owner.behavior);
		List<DialogBody> body = new ArrayList<>();
		notice(player, body, notice);
		body.add(text("How your pet moves: " + now.label + " · " + now.description.toLowerCase(Locale.ROOT) + ". Hover a button to see what it does.", ChatFormatting.GRAY));
		List<ActionButton> buttons = new ArrayList<>();
		for (Pets.Behavior b : Pets.Behavior.values()) buttons.add(button((b == now ? "● " : "") + b.label, b.description, "move", args("how", b.name())));
		menu(player, "Pets · Moves", body, buttons, button("Back", null, "home", args()));
	}

	private static void trails(ServerPlayer player, String notice) {
		Pets.Owner owner = PetStore.data().owners.get(player.getStringUUID());
		Pets.Trail now = Pets.Trail.of(owner == null ? null : owner.trail);
		List<DialogBody> body = new ArrayList<>();
		notice(player, body, notice);
		body.add(text("Particles that rise off your pet: " + now.label + ".", ChatFormatting.GRAY));
		List<ActionButton> buttons = new ArrayList<>();
		for (Pets.Trail t : Pets.Trail.values()) buttons.add(button((t == now ? "● " : "") + t.label, null, "trail", args("how", t.name())));
		menu(player, "Pets · Particles", body, buttons, button("Back", null, "home", args()));
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
			body.add(text("Floating heads that follow you, just for looks · paid in " + MarketStore.currencyName(), ChatFormatting.GRAY));
			if (pages > 1) {
				MutableComponent nav = Component.empty();
				if (p > 0) nav.append(link("←", "Page " + p, "shop", args("page", String.valueOf(p - 1)), ChatFormatting.YELLOW));
				else nav.append(Component.literal("[ ← ]").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.BOLD));
				nav.append(Component.literal("  " + (p + 1) + " / " + pages + "  ").withStyle(ChatFormatting.GRAY));
				if (p < pages - 1) nav.append(link("→", "Page " + (p + 2), "shop", args("page", String.valueOf(p + 1)), ChatFormatting.YELLOW));
				else nav.append(Component.literal("[ → ]").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.BOLD));
				body.add(new PlainMessage(nav, TEXT_WIDTH));
			}
			Pets.Owner owner = PetStore.data().owners.get(player.getStringUUID());
			for (Pets.Pet pet : pets.subList(p * PAGE, Math.min(pets.size(), (p + 1) * PAGE))) {
				int left = PetStore.data().left(pet);
				boolean have = owner != null && owner.owned(pet.id) != null;
				Component desc = Component.empty()
						.append(Component.literal(pet.name).withStyle(ChatFormatting.WHITE))
						.append(have ? Component.literal("  yours").withStyle(ChatFormatting.GREEN) : Component.empty())
						.append(Component.literal("\n" + (pet.price == 0 ? "Free" : amount(pet.price))).withStyle(ChatFormatting.GOLD))
						.append(Component.literal(" · " + Pets.duration(pet.days) + (left > 0 ? " · " + left + " left" : "") + "  ").withStyle(ChatFormatting.DARK_GRAY))
						.append(link("View", "See it and buy", "item", args("id", pet.id), ChatFormatting.GREEN));
				body.add(headRow(player, pet.texture, desc));
			}
		}
		menu(player, "Pet shop", body, List.of(), button("Back", null, "home", args()));
	}

	private static void item(ServerPlayer player, Pets.Pet pet, String notice) {
		Pets.Data data = PetStore.data();
		Pets.Owner owner = data.owners.get(player.getStringUUID());
		Pets.Owned have = owner == null ? null : owner.owned(pet.id);
		int left = data.left(pet);
		List<DialogBody> body = new ArrayList<>();
		notice(player, body, notice);
		body.add(headRow(player, pet.texture, Component.literal(pet.name).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)
				.append(Component.literal("\nA floating head that follows you").withStyle(st -> st.withBold(false).withColor(ChatFormatting.GRAY)))));
		// The facts in one line: label grey, value bright.
		MutableComponent facts = Component.empty()
				.append(Component.literal("Price ").withStyle(ChatFormatting.GRAY)).append(Component.literal(pet.price == 0 ? "Free" : amount(pet.price)).withStyle(ChatFormatting.GOLD))
				.append(Component.literal("   Lasts ").withStyle(ChatFormatting.GRAY)).append(Component.literal(Pets.duration(pet.days)).withStyle(ChatFormatting.WHITE));
		if (left > 0) facts.append(Component.literal("   Left ").withStyle(ChatFormatting.GRAY)).append(Component.literal(String.valueOf(left)).withStyle(ChatFormatting.WHITE));
		body.add(new PlainMessage(facts, TEXT_WIDTH));
		String why = data.cannotBuy(player.getStringUUID(), pet);
		if (have != null) {
			body.add(text("✔ You have it: " + left(have), ChatFormatting.GREEN));
		}
		if (why != null) {
			body.add(text(why, ChatFormatting.GRAY));
		} else if (pet.price > 0) {
			int carried = MarketStore.count(player, new ItemStack(MarketStore.currency()));
			body.add(carried >= pet.price ? text("You carry " + amount(carried) + ".", ChatFormatting.DARK_GRAY)
					: text("You carry " + amount(carried) + ": " + amount(pet.price - carried) + " more needed.", ChatFormatting.RED));
		}
		List<ActionButton> buttons = new ArrayList<>();
		if (why == null) {
			String price = pet.price == 0 ? "free" : amount(pet.price);
			String label = have != null ? "Add " + Pets.duration(pet.days) + " · " + price : pet.price == 0 ? "Get it free" : "Buy · " + price;
			buttons.add(button(label, null, "buy", args("id", pet.id)));
		}
		menu(player, pet.name, body, buttons, button("Back", null, "shop", args("page", "0")));
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
				else item(player, pet, null);
			}
			case "out" -> {
				if (owned == null) {
					home(player, "You don't have that pet any more.");
					return;
				}
				owner.active = owned.pet;
				PetStore.changed();
				home(player, "✔ " + PetStore.name(owned) + " is out.");
			}
			case "away" -> {
				if (owner != null) owner.active = null;
				PetStore.changed();
				home(player, null);
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
			case "buy" -> buy(player, PetStore.settings().pet(str(in, "id")));
			default -> {
			}
		}
	}

	private static void buy(ServerPlayer player, Pets.Pet pet) {
		if (pet == null) {
			shop(player, 0, "That pet isn't for sale any more.");
			return;
		}
		Pets.Data data = PetStore.data();
		String why = data.cannotBuy(player.getStringUUID(), pet);
		if (why != null) {
			item(player, pet, why);
			return;
		}
		ItemStack coin = new ItemStack(MarketStore.currency());
		if (pet.price > 0 && !MarketStore.take(player, coin, pet.price)) {
			item(player, pet, "You need " + money(pet.price) + ".");
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
