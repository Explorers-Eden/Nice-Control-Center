package eu.explorerseden.nicecontrolcenter.players;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;

import eu.explorerseden.nicecontrolcenter.Games;
import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.Market;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.Pets;
import eu.explorerseden.nicecontrolcenter.Points;
import eu.explorerseden.nicecontrolcenter.Rewards;
import eu.explorerseden.nicecontrolcenter.log.ConsoleRunner;
import eu.explorerseden.nicecontrolcenter.market.MarketStore;
import eu.explorerseden.nicecontrolcenter.rewards.RewardActions;

/**
 * Reward shops (see {@link Points}): NPCs (mannequins with the shop's skin) standing where the admin placed them; a
 * right-click opens the shop's dialog, where points buy what the shop offers (items, commands, Rewards' non-item
 * rewards). The NPCs are kept in place: missing ones come back once their chunk is loaded, changed ones (skin, name)
 * are made again, ones no shop wants any more go. Buttons send "nicecontrolcenter:shop/…" (checked again here).
 */
public final class RewardShops {
	private static final String TAG = "ncc_shop";
	private static final int WIDTH = 150;
	private static final int TEXT_WIDTH = 310;
	/** NPCs seen in the last pass and their spot (kept there, heads turned, between passes). */
	private static final Map<Entity, Games.Pos> npcs = new HashMap<>();

	private RewardShops() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((d, access, env) -> commands(d));
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 100 == 0) keep(server);
			if (server.getTickCount() % 4 == 0) guard();
		});
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!(player instanceof ServerPlayer p) || !entity.entityTags().contains(TAG)) return InteractionResult.PASS;
			if (hand != InteractionHand.MAIN_HAND) return InteractionResult.SUCCESS;
			Points.Shop shop = shopOf(entity);
			if (shop == null || !PointsStore.settings().enabled) {
				p.sendOverlayMessage(Component.literal("This shop is closed").withStyle(ChatFormatting.GRAY));
				return InteractionResult.SUCCESS;
			}
			open(p, shop, null, null);
			return InteractionResult.SUCCESS;
		});
		// Nobody hurts or knocks over a shop NPC (admins take one away with /points shop npc remove).
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> entity.entityTags().contains(TAG) ? InteractionResult.FAIL : InteractionResult.PASS);
	}

	/** A shop NPC: the claims let anyone talk to it. */
	public static boolean isNpc(Entity e) {
		return e.entityTags().contains(TAG);
	}

	// ---------------------------------------------------------------- the NPCs

	/** The NPC's tag for a spot: which shop and where, so moving or removing one in the list doesn't mix them up. */
	private static String spotTag(Points.Shop s, Games.Pos at) {
		return "ncc_npc_" + Integer.toHexString((s.id + "|" + at.dim + "|" + Math.round(at.x * 2) + "|" + Math.round(at.y * 2) + "|" + Math.round(at.z * 2)).hashCode());
	}

	/** What the NPC looks like: made again when it changes. */
	private static String looksTag(Points.Shop s, Games.Pos at) {
		return "ncc_look_" + Integer.toHexString((s.texture + "|" + s.shown() + "|" + at.yaw + "|" + PointsStore.settings().name).hashCode());
	}

	/** Every five seconds: NPCs where they should be (once their chunk is loaded), the rest gone. */
	static void keep(MinecraftServer server) {
		Points.Settings settings = PointsStore.settings();
		Map<String, Entity> seen = new HashMap<>();
		npcs.clear();
		for (ServerLevel level : server.getAllLevels()) {
			for (Entity e : level.getAllEntities()) {
				if (e == null || !e.entityTags().contains(TAG) || e.isRemoved()) continue;
				Points.Shop shop = shopOf(e);
				String spot = e.entityTags().stream().filter(t -> t.startsWith("ncc_npc_")).findFirst().orElse("");
				Games.Pos at = shop == null ? null : shop.npcs.stream().filter(p -> spotTag(shop, p).equals(spot)).findFirst().orElse(null);
				// Not wanted any more, changed, or a second one on the same spot.
				if (!settings.enabled || at == null || !e.entityTags().contains(looksTag(shop, at)) || seen.containsKey(spot)) {
					e.discard();
					continue;
				}
				seen.put(spot, e);
				npcs.put(e, at);
			}
		}
		if (!settings.enabled) return;
		for (Points.Shop shop : settings.shops) {
			for (Games.Pos at : shop.npcs) {
				if (seen.containsKey(spotTag(shop, at))) continue;
				ServerLevel level = eu.explorerseden.nicecontrolcenter.games.GameRuntime.levelOf(server, at.dim);
				if (level == null || !level.isPositionEntityTicking(BlockPos.containing(at.x, at.y, at.z))) continue;
				summon(server, shop, at);
			}
		}
	}

	private static void summon(MinecraftServer server, Points.Shop shop, Games.Pos at) {
		String texture = Pets.texture(shop.texture);
		StringBuilder nbt = new StringBuilder("{Tags:[\"" + TAG + "\",\"" + TAG + "_" + shop.id + "\",\"" + spotTag(shop, at) + "\",\"" + looksTag(shop, at) + "\"]");
		nbt.append(",Rotation:[").append(at.yaw).append("f,0f],Invulnerable:1b,immovable:1b,NoGravity:1b,Silent:1b,CustomNameVisible:1b");
		nbt.append(",CustomName:").append(Json.GSON.toJson(shop.shown()));
		nbt.append(",description:").append(Json.GSON.toJson("Spend your " + PointsStore.settings().name + " · right-click"));
		if (texture != null) nbt.append(",profile:{properties:[{name:\"textures\",value:\"").append(texture).append("\"}]}");
		nbt.append("}");
		ConsoleRunner.quiet(server, String.format(Locale.ROOT, "execute in %s run summon minecraft:mannequin %.2f %.2f %.2f %s", at.dim, at.x, at.y, at.z, nbt));
	}

	/**
	 * NPCs stay put and unharmed: out of boats and minecarts, back on their spot when something moved them (pistons,
	 * water, a fishing rod, the block under them gone), no fire or effects; and they turn their head to the nearest
	 * player close by.
	 */
	private static void guard() {
		for (Map.Entry<Entity, Games.Pos> n : npcs.entrySet()) {
			Entity e = n.getKey();
			Games.Pos at = n.getValue();
			if (e.isRemoved() || !(e.level() instanceof ServerLevel level)) continue;
			if (e.isPassenger()) e.stopRiding();
			if (e.isVehicle()) e.ejectPassengers();
			if (e.distanceToSqr(at.x, at.y, at.z) > 0.01) {
				e.teleportTo(at.x, at.y, at.z);
				e.setDeltaMovement(0, 0, 0);
				e.setYRot(at.yaw);
			}
			e.clearFire();
			if (e instanceof net.minecraft.world.entity.LivingEntity living) {
				if (!living.getActiveEffects().isEmpty()) living.removeAllEffects();
				if (living.getHealth() < living.getMaxHealth()) living.setHealth(living.getMaxHealth());
				living.setYBodyRot(at.yaw);
			}
			Points.Shop shop = shopOf(e);
			if (shop == null || !shop.lookAtPlayers) continue;
			var p = level.getNearestPlayer(e, 8);
			if (p == null) continue;
			double dx = p.getX() - e.getX(), dz = p.getZ() - e.getZ(), dy = p.getEyeY() - e.getEyeY();
			float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
			float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
			e.setYHeadRot(yaw);
			e.setXRot(Math.max(-40, Math.min(40, pitch)));
		}
	}

	private static Points.Shop shopOf(Entity e) {
		for (Points.Shop s : PointsStore.settings().shops) if (e.entityTags().contains(TAG + "_" + s.id)) return s;
		return null;
	}

	// ---------------------------------------------------------------- the dialog

	private static CompoundTag args(String... pairs) {
		CompoundTag tag = new CompoundTag();
		for (int i = 0; i + 1 < pairs.length; i += 2) tag.putString(pairs[i], pairs[i + 1]);
		return tag;
	}

	private static ActionButton button(Component label, String tooltip, String action, CompoundTag args) {
		return new ActionButton(new CommonButtonData(label, Optional.of(Component.literal(tooltip)), WIDTH),
				Optional.of(new CustomAll(Identifier.fromNamespaceAndPath("nicecontrolcenter", "shop/" + action), Optional.of(args))));
	}

	private static MutableComponent link(String label, String hover, String action, CompoundTag args, ChatFormatting color) {
		return Component.literal("[ " + label + " ]").withStyle(st -> st.withColor(color).withBold(true)
				.withClickEvent(new ClickEvent.Custom(Identifier.fromNamespaceAndPath("nicecontrolcenter", "shop/" + action), Optional.of(args)))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	private static DialogBody text(String s, ChatFormatting color) {
		return new PlainMessage(Component.literal(s).withStyle(color), TEXT_WIDTH);
	}

	private static void menu(ServerPlayer player, String title, List<DialogBody> body, List<ActionButton> buttons, ActionButton exit) {
		CommonDialogData common = new CommonDialogData(Component.literal(title), Optional.empty(), true, false, DialogAction.CLOSE, body, List.of());
		if (buttons.isEmpty()) player.openDialog(Holder.direct(new NoticeDialog(common, exit)));
		else player.openDialog(Holder.direct(new MultiActionDialog(common, buttons, Optional.of(exit), 2)));
	}

	private static ActionButton close() {
		return new ActionButton(new CommonButtonData(Component.literal("Close"), WIDTH), Optional.empty());
	}

	/** The entry's icon: its head skin, else its item (a chest when neither works). */
	private static ItemStack icon(ServerPlayer player, Points.Entry e) {
		String texture = Pets.texture(e.iconTexture);
		if (texture != null) {
			ItemStack head = eu.explorerseden.nicecontrolcenter.pets.PetDialogs.head(player, texture);
			if (!head.isEmpty()) return head;
		}
		Identifier id = Identifier.tryParse(e.icon);
		Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(id);
		return new ItemStack(item == Items.AIR ? Items.CHEST : item);
	}

	private static DialogBody row(ServerPlayer player, Points.Entry e, Component description) {
		return new ItemBody(ItemStackTemplate.fromNonEmptyStack(icon(player, e)), Optional.of(new PlainMessage(description, TEXT_WIDTH - 30)), false, false, 16, 16);
	}

	/** Why the player can't buy it now (null when they can). */
	private static String blocked(ServerPlayer p, Points.Entry e) {
		if (e.stock > 0 && PointsStore.sold(e.id) >= e.stock) return "sold out";
		if (e.perPlayer > 0 && PointsStore.bought(p.getStringUUID(), e.id) >= e.perPlayer) return e.perPlayer == 1 ? "you have it" : "you bought all " + e.perPlayer;
		return null;
	}

	/** The shop: balance on top, every reward with its price and a Buy link. */
	public static void open(ServerPlayer p, Points.Shop shop, String notice, String done) {
		Points.Settings s = PointsStore.settings();
		List<DialogBody> body = new ArrayList<>();
		if (notice != null) {
			body.add(text(notice, ChatFormatting.RED));
			eu.explorerseden.nicecontrolcenter.core.Plop.play(p);
		}
		if (done != null) body.add(text(done, ChatFormatting.GREEN));
		if (!shop.greeting.isBlank()) body.add(text(shop.greeting, ChatFormatting.GRAY));
		long balance = PointsStore.balance(p.getStringUUID());
		body.add(new PlainMessage(Component.literal("You have ").withStyle(ChatFormatting.GRAY).append(Component.literal(Points.amount(s, balance)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)), TEXT_WIDTH));
		if (shop.entries.isEmpty()) body.add(text("Nothing for sale here yet.", ChatFormatting.GRAY));
		for (Points.Entry e : shop.entries) {
			String why = blocked(p, e);
			boolean afford = balance >= e.price;
			MutableComponent desc = Component.empty()
					.append(Component.literal(e.name).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
					.append(Component.literal("  " + (e.price == 0 ? "free" : Points.shortAmount(s, e.price))).withStyle(afford ? ChatFormatting.GOLD : ChatFormatting.RED));
			List<String> limits = new ArrayList<>();
			if (e.stock > 0) limits.add(Math.max(0, e.stock - PointsStore.sold(e.id)) + " left");
			if (e.perPlayer > 0) limits.add("bought " + PointsStore.bought(p.getStringUUID(), e.id) + " of " + e.perPlayer);
			if (!limits.isEmpty()) desc.append(Component.literal(" · " + String.join(" · ", limits)).withStyle(ChatFormatting.GRAY));
			if (!e.description.isBlank()) desc.append(Component.literal("\n" + e.description).withStyle(ChatFormatting.GRAY));
			desc.append(Component.literal("\n"));
			if (why != null) desc.append(Component.literal("[ " + why + " ]").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.BOLD));
			else desc.append(link("Buy", afford ? "See what it gives and buy it" : "You need " + Points.amount(s, e.price - balance) + " more", "view", args("shop", shop.id, "entry", e.id), afford ? ChatFormatting.GREEN : ChatFormatting.GRAY));
			body.add(row(p, e, desc));
		}
		menu(p, shop.shown(), body, List.of(), close());
	}

	/** One reward up close, with what it gives and the Buy button. */
	private static void view(ServerPlayer p, Points.Shop shop, Points.Entry e) {
		Points.Settings s = PointsStore.settings();
		List<DialogBody> body = new ArrayList<>();
		MutableComponent title = Component.literal(e.name).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD);
		if (!e.description.isBlank()) title.append(Component.literal("\n" + e.description).withStyle(st -> st.withBold(false).withColor(ChatFormatting.GRAY)));
		body.add(row(p, e, title));
		List<String> gives = new ArrayList<>();
		for (Points.Stack st : e.items) gives.add(st.count + " × " + st.item.name);
		for (Rewards.Action a : e.actions) {
			Rewards.ActionType t = Rewards.actionType(a.type);
			gives.add(t == null ? a.type : t.label() + (a.amount != 0 ? " " + a.amount : "") + (a.seconds > 0 ? " (" + Perks.human(a.seconds * 1000) + ")" : "") + (a.target.isBlank() ? "" : " " + a.target));
		}
		if (!gives.isEmpty()) body.add(text("You get: " + String.join(", ", gives) + (e.commands.isEmpty() ? "" : " and more"), ChatFormatting.AQUA));
		long balance = PointsStore.balance(p.getStringUUID());
		body.add(text("Price " + (e.price == 0 ? "free" : Points.amount(s, e.price)) + " · you have " + Points.amount(s, balance), balance >= e.price ? ChatFormatting.GOLD : ChatFormatting.RED));
		String why = blocked(p, e);
		List<ActionButton> buttons = new ArrayList<>();
		if (why == null && balance >= e.price) buttons.add(button(Component.literal("Buy for " + Points.amount(s, e.price)).withStyle(ChatFormatting.GREEN), "Takes the " + s.name + " and gives it to you now", "buy", args("shop", shop.id, "entry", e.id, "price", String.valueOf(e.price))));
		else body.add(text(why != null ? "You can't buy it: " + why + "." : "You need " + Points.amount(s, e.price - balance) + " more.", ChatFormatting.RED));
		menu(p, shop.shown() + " · " + e.name, body, buttons, button(Component.literal("Back"), "Back to the shop", "home", args("shop", shop.id)));
	}

	/** A button was clicked ({@code what} after "shop/"). Server thread. */
	public static void handle(ServerPlayer p, String what, Optional<Tag> payload) {
		CompoundTag in = payload.orElse(null) instanceof CompoundTag t ? t : new CompoundTag();
		Points.Shop shop = PointsStore.settings().shop(in.getStringOr("shop", ""));
		if (shop == null || !PointsStore.settings().enabled) {
			p.sendSystemMessage(Component.literal("This shop is closed.").withStyle(ChatFormatting.RED));
			return;
		}
		Points.Entry e = shop.entry(in.getStringOr("entry", ""));
		switch (what) {
			case "home" -> open(p, shop, null, null);
			case "view" -> {
				if (e == null) open(p, shop, "That reward isn't for sale any more.", null);
				else view(p, shop, e);
			}
			case "buy" -> buy(p, shop, e, in.getStringOr("price", ""));
			default -> {
			}
		}
	}

	private static void buy(ServerPlayer p, Points.Shop shop, Points.Entry e, String seenPrice) {
		Points.Settings s = PointsStore.settings();
		if (e == null) {
			open(p, shop, "That reward isn't for sale any more.", null);
			return;
		}
		if (!String.valueOf(e.price).equals(seenPrice)) {
			view(p, shop, e);
			return;
		}
		String why = blocked(p, e);
		if (why != null) {
			open(p, shop, e.name + ": " + why + ".", null);
			return;
		}
		String uuid = p.getStringUUID();
		String name = p.getGameProfile().name();
		if (PointsStore.balance(uuid) < e.price) {
			open(p, shop, "You need " + Points.amount(s, e.price - PointsStore.balance(uuid)) + " more for " + e.name + ".", null);
			return;
		}
		MinecraftServer server = p.level().getServer();
		// Non-item rewards first: when the first one can't be given (a pet they have already…), nothing is charged.
		List<String> got = new ArrayList<>();
		for (Rewards.Action a : e.actions) {
			try {
				got.add(RewardActions.give(server, p.getUUID(), name, p, a, "shop " + shop.name));
			} catch (RewardActions.Refused r) {
				if (got.isEmpty()) {
					open(p, shop, e.name + ": " + r.getMessage(), null);
					return;
				}
			}
		}
		PointsStore.take(uuid, name, e.price, "bought " + e.name + " at " + shop.shown());
		boolean inbox = false;
		for (Points.Stack st : e.items) {
			int left = st.count;
			while (left > 0) {
				ItemStack one = MarketStore.stack(server, st.item, 1);
				if (one.isEmpty()) break;
				int n = Math.min(left, one.getMaxStackSize());
				ItemStack piece = one.copyWithCount(n);
				left -= n;
				// What doesn't fit goes to the market inbox.
				p.getInventory().add(piece);
				if (!piece.isEmpty()) {
					Market.Data market = MarketStore.data();
					Market.Mailbox m = market.mailbox(uuid, name);
					Market.Parcel parcel = new Market.Parcel();
					parcel.item = st.item;
					parcel.amount = piece.getCount();
					m.parcels.add(parcel);
					market.mailChanged(m);
					inbox = true;
				}
			}
		}
		if (inbox) MarketStore.saveNow();
		p.inventoryMenu.broadcastChanges();
		for (String line : e.commands) {
			String cmd = line.strip().replaceFirst("^/", "").replace("{player}", name).replace("{uuid}", uuid);
			if (!cmd.isEmpty()) ConsoleRunner.run(server, "execute as " + uuid + " at @s run " + cmd, "Points");
		}
		PointsStore.countPurchase(uuid, name, e.id);
		PointsStore.saveSoon();
		p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.7f, 1.4f);
		NiceControlCenter.LOGGER.info("Points: {} bought {} at the {} shop for {}", name, e.name, shop.name, Points.amount(s, e.price));
		open(p, shop, null, "Bought " + e.name + (got.isEmpty() ? "" : ": " + String.join(", ", got)) + "." + (inbox ? " Some of it didn't fit: it's in your inbox (/inbox)." : ""));
	}

	// ---------------------------------------------------------------- setting up (admins)

	private static void commands(CommandDispatcher<CommandSourceStack> d) {
		var admin = Permissions.require(PointsStore.PERMISSION, PermissionLevel.GAMEMASTERS);
		var shopArg = (java.util.function.Supplier<com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>>) () -> Commands.argument("shop", StringArgumentType.word())
				.suggests((ctx, b) -> SharedSuggestionProvider.suggest(PointsStore.settings().shops.stream().map(x -> x.name), b));
		d.register(Commands.literal("points").then(Commands.literal("shop").requires(admin)
				.then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					String name = StringArgumentType.getString(ctx, "name");
					if (!name.matches("[A-Za-z0-9_-]{1,32}")) return fail(ctx, "One word: letters, digits, - and _.");
					if (PointsStore.settings().shop(name) != null) return fail(ctx, "There's a shop called " + name + " already.");
					Points.Shop s = new Points.Shop();
					s.name = name;
					s.npcs.add(spot(p));
					PointsStore.settings().shops.add(s);
					PointsStore.settings().fill();
					return saved(ctx, "Made the shop " + name + " with an NPC here. Add rewards: hold an item and /points shop item " + name + " <price> <name>, or in the panel (Games → Reward shops), where its skin is set too.");
				})))
				.then(Commands.literal("delete").then(shopArg.get().executes(ctx -> {
					Points.Shop s = shop(ctx);
					if (s == null) return fail(ctx, "There's no such shop.");
					PointsStore.settings().shops.remove(s);
					return saved(ctx, "Removed the shop " + s.name + " and its NPCs.");
				})))
				.then(Commands.literal("npc")
						.then(Commands.literal("add").then(shopArg.get().executes(ctx -> {
							Points.Shop s = shop(ctx);
							if (s == null) return fail(ctx, "There's no such shop.");
							s.npcs.add(spot(ctx.getSource().getPlayerOrException()));
							return saved(ctx, s.name + ": NPC " + s.npcs.size() + " here.");
						})))
						.then(Commands.literal("remove").executes(ctx -> {
							ServerPlayer p = ctx.getSource().getPlayerOrException();
							Entity near = null;
							for (Entity e : npcs.keySet()) if (e.level() == p.level() && e.distanceToSqr(p) < 25 && (near == null || e.distanceToSqr(p) < near.distanceToSqr(p))) near = e;
							if (near == null) return fail(ctx, "Stand next to the NPC (5 blocks at most).");
							Points.Shop s = shopOf(near);
							String spot = near.entityTags().stream().filter(t -> t.startsWith("ncc_npc_")).findFirst().orElse("");
							if (s == null || !s.npcs.removeIf(at -> spotTag(s, at).equals(spot))) return fail(ctx, "That NPC isn't part of a shop.");
							near.discard();
							return saved(ctx, s.name + ": NPC removed (" + s.npcs.size() + " left).");
						})))
				.then(Commands.literal("skin").then(shopArg.get().then(Commands.argument("texture", StringArgumentType.greedyString()).executes(ctx -> {
					Points.Shop s = shop(ctx);
					if (s == null) return fail(ctx, "There's no such shop.");
					String t = StringArgumentType.getString(ctx, "texture").strip();
					if (Pets.texture(t) == null) return fail(ctx, "That isn't a skin: use a textures.minecraft.net URL, its hash, or the base64 value.");
					s.texture = t;
					return saved(ctx, s.name + ": skin set; its NPCs change within a few seconds.");
				}))))
				.then(Commands.literal("item").then(shopArg.get().then(Commands.argument("price", IntegerArgumentType.integer(0, 1_000_000_000))
						.then(Commands.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
							ServerPlayer p = ctx.getSource().getPlayerOrException();
							Points.Shop s = shop(ctx);
							if (s == null) return fail(ctx, "There's no such shop.");
							ItemStack held = p.getMainHandItem();
							if (held.isEmpty()) return fail(ctx, "Hold the item it should give.");
							Points.Entry e = new Points.Entry();
							e.name = StringArgumentType.getString(ctx, "name").strip();
							e.price = IntegerArgumentType.getInteger(ctx, "price");
							e.icon = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
							Points.Stack st = new Points.Stack();
							st.item = MarketStore.store(p, held);
							st.count = held.getCount();
							e.items.add(st);
							s.entries.add(e);
							PointsStore.settings().fill();
							return saved(ctx, s.name + ": " + e.name + " (" + st.count + " × " + st.item.name + ") for " + Points.amount(PointsStore.settings(), e.price) + ".");
						})))))
				.then(Commands.literal("open").then(shopArg.get().executes(ctx -> {
					Points.Shop s = shop(ctx);
					if (s == null) return fail(ctx, "There's no such shop.");
					open(ctx.getSource().getPlayerOrException(), s, null, null);
					return 1;
				})))));
	}

	/** Where an NPC goes: the player's spot, facing the way they look (to the nearest 45°). */
	private static Games.Pos spot(ServerPlayer p) {
		return new Games.Pos(p.level().dimension().identifier().toString(), Math.floor(p.getX()) + 0.5, Math.floor(p.getY()), Math.floor(p.getZ()) + 0.5, Math.round(p.getYRot() / 45f) * 45f);
	}

	private static Points.Shop shop(CommandContext<CommandSourceStack> ctx) {
		return PointsStore.settings().shop(StringArgumentType.getString(ctx, "shop"));
	}

	private static int saved(CommandContext<CommandSourceStack> ctx, String msg) {
		PointsStore.saveSettings();
		keep(ctx.getSource().getServer());
		ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
		return 1;
	}

	private static int fail(CommandContext<CommandSourceStack> ctx, String why) {
		ctx.getSource().sendFailure(Component.literal(why));
		return 0;
	}
}
