package eu.explorerseden.nicecontrolcenter.games;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import com.mojang.brigadier.StringReader;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;

import eu.explorerseden.nicecontrolcenter.Dungeons;
import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.Points;
import eu.explorerseden.nicecontrolcenter.claims.GameClaims;
import eu.explorerseden.nicecontrolcenter.core.AsyncFiles;
import eu.explorerseden.nicecontrolcenter.games.DungeonRuntime.Run;
import eu.explorerseden.nicecontrolcenter.log.ConsoleRunner;
import eu.explorerseden.nicecontrolcenter.players.PointsStore;

/**
 * Rogue runs' boons (picked after a room or bought in a shop, gone when the run ends for the player), run coins, the
 * shop and the unlocks bought with points between runs. Menus are dialogs whose buttons come back as
 * nicecontrolcenter:rogue/… (ClaimClickMixin); nothing in a click is trusted, it's checked against the run again.
 */
public final class RogueBoons {
	private static final int WIDTH = 200;
	private static final int TEXT_WIDTH = 300;
	private static final String PREFIX = "rogue_";
	private static final Random random = new Random();

	/** player uuid → unlock id → level (world/nicecontrolcenter-rogue-unlocks.json). */
	private static Map<String, Map<String, Integer>> unlocks = new LinkedHashMap<>();
	private static Path unlocksFile;

	private RogueBoons() {
	}

	private static Dungeons.Rogue cfg() {
		return DungeonRuntime.settings().rogue;
	}

	// ---------------------------------------------------------------- the unlocks file

	static void load(MinecraftServer server) {
		unlocksFile = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("nicecontrolcenter-rogue-unlocks.json");
		unlocks = new LinkedHashMap<>();
		try {
			if (Files.exists(unlocksFile)) {
				Map<String, Map<String, Integer>> m = Json.GSON.fromJson(Files.readString(unlocksFile),
						new com.google.gson.reflect.TypeToken<Map<String, Map<String, Integer>>>() { }.getType());
				if (m != null) unlocks = new LinkedHashMap<>(m);
			}
		} catch (java.io.IOException | RuntimeException e) {
			NiceControlCenter.LOGGER.warn("Dungeons: could not read the rogue unlocks: {}", e.getMessage());
		}
	}

	private static void saveUnlocks() {
		if (unlocksFile != null) AsyncFiles.write(unlocksFile, Json.GSON.toJson(unlocks));
	}

	static int level(UUID player, String unlock) {
		return unlocks.getOrDefault(player.toString(), Map.of()).getOrDefault(unlock, 0);
	}

	/** For the panel: every player's unlock levels. */
	public static Map<String, Map<String, Integer>> unlockLevels() {
		return unlocks;
	}

	/** The sum of a kind's levels (× amount for coins) a player has. */
	private static int unlocked(UUID player, String kind, boolean timesAmount) {
		int n = 0;
		for (Dungeons.Unlock u : cfg().unlocks) {
			if (!u.enabled || !u.kind.equals(kind)) continue;
			int lv = Math.min(u.maxLevel, level(player, u.id));
			n += timesAmount ? lv * u.amount : lv;
		}
		return n;
	}

	/** At the start of a run: coins, rerolls and starting boons from the player's unlocks. */
	static void start(Run r, ServerPlayer p) {
		RogueRuns.State st = r.rogue;
		UUID id = p.getUUID();
		st.coins.put(id, unlocked(id, "coins", true));
		st.rerolls.put(id, unlocked(id, "reroll", false));
		for (Dungeons.Unlock u : cfg().unlocks) {
			if (!u.enabled || !"boon".equals(u.kind)) continue;
			Dungeons.Boon b = cfg().boon(u.boon);
			int lv = Math.min(u.maxLevel, level(id, u.id));
			for (int i = 0; i < lv && b != null && b.enabled; i++) give(r, p, b, false);
		}
	}

	// ---------------------------------------------------------------- applying and taking off

	private static Holder<Attribute> attribute(String id) {
		try {
			return BuiltInRegistries.ATTRIBUTE.get(Identifier.parse(id)).map(h -> (Holder<Attribute>) h).orElse(null);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static Holder<MobEffect> effect(String id) {
		try {
			return BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(id)).map(h -> (Holder<MobEffect>) h).orElse(null);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** One more stack of the boon for the player (items and heals happen now; attributes and effects stay). */
	static void give(Run r, ServerPlayer p, Dungeons.Boon b, boolean loud) {
		RogueRuns.State st = r.rogue;
		Map<String, Integer> mine = st.stacks.computeIfAbsent(p.getUUID(), k -> new LinkedHashMap<>());
		int n = mine.merge(b.id, 1, Integer::sum);
		switch (b.kind) {
			case "attribute" -> {
				Holder<Attribute> a = attribute(b.target);
				AttributeInstance inst = a == null ? null : p.getAttribute(a);
				if (inst != null) {
					inst.addOrUpdateTransientModifier(new AttributeModifier(Identifier.fromNamespaceAndPath("nicecontrolcenter", PREFIX + b.id), b.amount * n,
							"percent".equals(b.op) ? AttributeModifier.Operation.ADD_MULTIPLIED_BASE : AttributeModifier.Operation.ADD_VALUE));
					if (b.target.endsWith("max_health") && b.amount > 0) p.heal((float) b.amount);
				}
			}
			case "effect" -> keepEffect(p, b, n);
			case "item" -> {
				try {
					var input = new net.minecraft.commands.arguments.item.ItemParser(p.level().registryAccess()).parse(new StringReader(b.target.strip()));
					ItemStack stack = input.createItemStack(Math.max(1, Math.min(64, (int) Math.round(b.amount))));
					GameClaims.giveKitItem(p, stack, "dungeon");
				} catch (com.mojang.brigadier.exceptions.CommandSyntaxException | RuntimeException e) {
					NiceControlCenter.LOGGER.warn("Dungeons: rogue boon {} has an item that doesn't work ({}): {}", b.id, b.target, e.getMessage());
				}
			}
			case "heal" -> p.heal((float) b.amount);
			case "command" -> {
				String cmd = b.target.strip().replaceFirst("^/", "").replace("{player}", p.getGameProfile().name());
				if (!cmd.isEmpty()) ConsoleRunner.run(p.level().getServer(), "execute as " + p.getStringUUID() + " at @s run " + cmd, "Rogue");
			}
			default -> {
			}
		}
		if (loud) {
			p.sendSystemMessage(Words.lit("✦ " + b.name + (n > 1 ? " ×" + n : "") + ": " + describe(b)).withStyle(ChatFormatting.AQUA));
			p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.6f);
		}
	}

	private static void keepEffect(ServerPlayer p, Dungeons.Boon b, int stacks) {
		Holder<MobEffect> e = effect(b.target);
		if (e == null) return;
		int amp = Math.max(0, Math.min(4, stacks - 1));
		MobEffectInstance now = p.getEffect(e);
		if (now != null && now.isInfiniteDuration() && now.getAmplifier() >= amp) return;
		p.addEffect(new MobEffectInstance(e, MobEffectInstance.INFINITE_DURATION, amp, false, false, true));
	}

	/** Every few seconds: effects drunk away with milk come back. */
	static void keep(Run r, ServerPlayer p) {
		Map<String, Integer> mine = r.rogue.stacks.get(p.getUUID());
		if (mine == null) return;
		for (Map.Entry<String, Integer> e : mine.entrySet()) {
			Dungeons.Boon b = cfg().boon(e.getKey());
			if (b != null && "effect".equals(b.kind)) keepEffect(p, b, e.getValue());
		}
	}

	/** The run is over for them: every boon's modifier and effect comes off. */
	static void strip(Run r, ServerPlayer p) {
		Map<String, Integer> mine = r.rogue.stacks.remove(p.getUUID());
		for (Holder<Attribute> a : BuiltInRegistries.ATTRIBUTE.asHolderIdMap()) {
			AttributeInstance inst = p.getAttribute(a);
			if (inst == null) continue;
			for (AttributeModifier m : List.copyOf(inst.getModifiers())) {
				if (m.id().getNamespace().equals("nicecontrolcenter") && m.id().getPath().startsWith(PREFIX)) inst.removeModifier(m.id());
			}
		}
		if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
		if (mine == null) return;
		for (String id : mine.keySet()) {
			Dungeons.Boon b = cfg().boon(id);
			Holder<MobEffect> e = b == null || !"effect".equals(b.kind) ? null : effect(b.target);
			MobEffectInstance now = e == null ? null : p.getEffect(e);
			if (now != null && now.isInfiniteDuration()) p.removeEffect(e);
		}
	}

	/** "+4 max health", "Strength I"… (or the boon's own text). */
	static String describe(Dungeons.Boon b) {
		if (!b.description.isBlank()) return b.description;
		String what = b.target.replaceFirst("^minecraft:", "").replace('_', ' ');
		return switch (b.kind) {
			case "attribute" -> ("percent".equals(b.op) ? (b.amount >= 0 ? "+" : "") + Math.round(b.amount * 100) + " %" : (b.amount >= 0 ? "+" : "") + num(b.amount)) + " " + what;
			case "effect" -> what + " for the run (stronger with each one)";
			case "item" -> num(Math.max(1, b.amount)) + " × " + what;
			case "heal" -> "heals " + num(b.amount / 2) + (b.amount == 2 ? " heart" : " hearts");
			default -> "a surprise";
		};
	}

	private static String num(double d) {
		return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(Math.round(d * 100) / 100.0);
	}

	private static boolean maxed(Run r, UUID player, Dungeons.Boon b) {
		if (b.maxStacks <= 0) return false;
		return r.rogue.stacks.getOrDefault(player, Map.of()).getOrDefault(b.id, 0) >= b.maxStacks;
	}

	// ---------------------------------------------------------------- picking boons

	/** A fresh choice of boons for the player (weighted, none they've maxed); empty when there's nothing to give. */
	private static List<String> roll(Run r, UUID player) {
		List<Dungeons.Boon> pool = new ArrayList<>();
		for (Dungeons.Boon b : cfg().boons) if (b.enabled && b.weight > 0 && !maxed(r, player, b)) pool.add(b);
		int want = cfg().choices + Math.min(3, unlocked(player, "choice", false));
		List<String> out = new ArrayList<>();
		while (out.size() < want && !pool.isEmpty()) {
			int total = pool.stream().mapToInt(b -> b.weight).sum(), roll = random.nextInt(total);
			for (Dungeons.Boon b : pool) {
				roll -= b.weight;
				if (roll < 0) {
					out.add(b.id);
					pool.remove(b);
					break;
				}
			}
		}
		return out;
	}

	/** After a room: everyone still in chooses a boon (the doors wait until they have, or the time is up). */
	static void offerAll(MinecraftServer server, Run r) {
		RogueRuns.State st = r.rogue;
		if (!r.dungeon.rogueBoons) return;
		st.offerToken++;
		st.offersUntil = System.currentTimeMillis() + cfg().pickSeconds * 1000L;
		for (ServerPlayer p : RogueRuns.inside(server, r)) {
			List<String> o = roll(r, p.getUUID());
			if (o.isEmpty()) continue;
			st.offers.put(p.getUUID(), o);
			menu(r, p, null);
		}
	}

	static boolean waiting(Run r) {
		return !r.rogue.offers.isEmpty() && System.currentTimeMillis() < r.rogue.offersUntil;
	}

	/** Time's up: who hasn't chosen gets one of theirs at random. */
	static void timeUp(MinecraftServer server, Run r) {
		RogueRuns.State st = r.rogue;
		if (st.offers.isEmpty() || System.currentTimeMillis() < st.offersUntil) return;
		for (Map.Entry<UUID, List<String>> e : new HashMap<>(st.offers).entrySet()) {
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			Dungeons.Boon b = e.getValue().isEmpty() ? null : cfg().boon(e.getValue().get(random.nextInt(e.getValue().size())));
			st.offers.remove(e.getKey());
			if (p != null && b != null) {
				give(r, p, b, true);
				p.connection.send(net.minecraft.network.protocol.common.ClientboundClearDialogPacket.INSTANCE);
			}
		}
	}

	private static ActionButton button(String label, String tooltip, String action, CompoundTag args) {
		return new ActionButton(new CommonButtonData(Component.literal(label), Optional.of(Component.literal(tooltip)), WIDTH),
				Optional.of(new CustomAll(Identifier.fromNamespaceAndPath("nicecontrolcenter", "rogue/" + action), Optional.of(args))));
	}

	private static DialogBody text(String s, ChatFormatting color) {
		return new PlainMessage(Component.literal(s).withStyle(color), TEXT_WIDTH);
	}

	private static void open(ServerPlayer p, String title, List<DialogBody> body, List<ActionButton> buttons, boolean closable) {
		CommonDialogData common = new CommonDialogData(Component.literal(title), Optional.empty(), closable, false, DialogAction.CLOSE, body, List.of());
		ActionButton close = new ActionButton(new CommonButtonData(Component.literal("Close"), WIDTH), Optional.empty());
		p.openDialog(Holder.direct(new MultiActionDialog(common, buttons, Optional.of(close), 1)));
	}

	/** The boon choice (again with /dungeon boon). */
	static void menu(Run r, ServerPlayer p, String notice) {
		RogueRuns.State st = r.rogue;
		List<String> offer = st.offers.get(p.getUUID());
		if (offer == null) return;
		List<DialogBody> body = new ArrayList<>();
		if (notice != null) body.add(text(notice, ChatFormatting.RED));
		long left = Math.max(0, (st.offersUntil - System.currentTimeMillis()) / 1000);
		body.add(text("Pick a boon for the rest of this run (" + left + " s, then one is picked for you). Closed this by mistake? /dungeon boon", ChatFormatting.GRAY));
		List<ActionButton> buttons = new ArrayList<>();
		for (int i = 0; i < offer.size(); i++) {
			Dungeons.Boon b = cfg().boon(offer.get(i));
			if (b == null) continue;
			int has = st.stacks.getOrDefault(p.getUUID(), Map.of()).getOrDefault(b.id, 0);
			CompoundTag t = new CompoundTag();
			t.putInt("pick", i);
			t.putInt("token", st.offerToken);
			buttons.add(button(b.name + (has > 0 ? " (" + (has + 1) + ")" : ""), describe(b), "boon", t));
		}
		int rerolls = st.rerolls.getOrDefault(p.getUUID(), 0);
		if (rerolls > 0) {
			CompoundTag t = new CompoundTag();
			t.putInt("token", st.offerToken);
			buttons.add(button("Reroll (" + rerolls + " left)", "Other boons to choose from", "reroll", t));
		}
		open(p, "Choose a boon", body, buttons, true);
	}

	// ---------------------------------------------------------------- coins and the shop

	/** A fight, elite or boss room is cleared: one reward rolled for the party (a boon choice, coins, health or food). */
	static void roomReward(MinecraftServer server, Run r) {
		List<Dungeons.RoomReward> all = cfg().rewards.stream().filter(x -> x.enabled && x.weight > 0 && (!"boon".equals(x.kind) || r.dungeon.rogueBoons)).toList();
		if (all.isEmpty()) return;
		int total = all.stream().mapToInt(x -> x.weight).sum(), roll = random.nextInt(total);
		Dungeons.RoomReward pick = all.get(0);
		for (Dungeons.RoomReward x : all) {
			roll -= x.weight;
			if (roll < 0) {
				pick = x;
				break;
			}
		}
		RogueRuns.State st = r.rogue;
		switch (pick.kind) {
			case "boon" -> offerAll(server, r);
			case "coins" -> {
				int coins = pick.amount + pick.perRoom * Math.max(0, st.depth - 1);
				for (ServerPlayer p : RogueRuns.inside(server, r)) st.coins.merge(p.getUUID(), coins, Integer::sum);
				DungeonRuntime.tellRun(server, r, Words.lit("✦ Room reward: +" + coins + " ⛁ for everyone").withStyle(ChatFormatting.GOLD));
			}
			case "health" -> {
				for (ServerPlayer p : RogueRuns.inside(server, r)) {
					int more = st.bonusHealth.merge(p.getUUID(), pick.amount, Integer::sum);
					Holder<Attribute> a = attribute("minecraft:max_health");
					AttributeInstance inst = a == null ? null : p.getAttribute(a);
					if (inst != null) inst.addOrUpdateTransientModifier(new AttributeModifier(Identifier.fromNamespaceAndPath("nicecontrolcenter", PREFIX + "_health"), more, AttributeModifier.Operation.ADD_VALUE));
					p.heal(pick.amount);
				}
				DungeonRuntime.tellRun(server, r, Words.lit("✦ Room reward: +" + num(pick.amount / 2.0) + (pick.amount == 2 ? " heart" : " hearts") + " of max health for everyone (this run)").withStyle(ChatFormatting.RED));
			}
			case "food" -> {
				for (ServerPlayer p : RogueRuns.inside(server, r)) {
					var food = p.getFoodData();
					food.setFoodLevel(Math.min(20, food.getFoodLevel() + pick.amount));
					food.setSaturation(Math.min(food.getFoodLevel(), food.getSaturationLevel() + pick.amount / 2f));
				}
				DungeonRuntime.tellRun(server, r, Words.lit("✦ Room reward: a meal, +" + num(pick.amount / 2.0) + " hunger for everyone").withStyle(ChatFormatting.GREEN));
			}
			default -> {
			}
		}
	}

	/** A foe of the run went down: coins for everyone still in. */
	static void killed(MinecraftServer server, Run r, boolean boss) {
		Dungeons.Rogue c = cfg();
		int coins = boss ? c.bossCoins : c.coinsPerKill * ("elite".equals(r.rogue.type) ? c.eliteCoins : 1);
		if (coins <= 0) return;
		for (ServerPlayer p : RogueRuns.inside(server, r)) {
			r.rogue.coins.merge(p.getUUID(), coins, Integer::sum);
			p.sendOverlayMessage(Words.lit("+" + coins + " ⛁ (" + r.rogue.coins.get(p.getUUID()) + ")").withStyle(ChatFormatting.GOLD));
		}
	}

	static int price(Run r, Dungeons.ShopEntry e) {
		return (int) Math.round(e.price * (1 + cfg().priceGrowth / 100.0 * Math.max(0, r.rogue.depth - 1)));
	}

	/** The shop NPC on a shop marker (wiped with the room); a right-click opens the shop. */
	static void shopNpc(MinecraftServer server, Run r, net.minecraft.core.BlockPos at, String roomTag) {
		Dungeons.Rogue g = cfg();
		String texture = eu.explorerseden.nicecontrolcenter.Pets.texture(g.shopSkin);
		StringBuilder nbt = new StringBuilder("{Tags:[\"" + SHOP_TAG + "\",\"" + roomTag + "\",\"" + MobLines.NMM_EXCLUDE + "\"]");
		nbt.append(",Invulnerable:1b,immovable:1b,NoGravity:1b,Silent:1b,CustomNameVisible:1b,PersistenceRequired:1b");
		nbt.append(",CustomName:").append(Json.GSON.toJson(g.shopName));
		nbt.append(",description:").append(Json.GSON.toJson("Run coins · right-click"));
		if (texture != null) nbt.append(",profile:{properties:[{name:\"textures\",value:\"").append(texture).append("\"}]}");
		nbt.append("}");
		ConsoleRunner.quiet(server, String.format(Locale.ROOT, "execute in %s run summon minecraft:mannequin %.2f %d %.2f %s", Dungeons.DIMENSION, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, nbt));
	}

	static final String SHOP_TAG = "ncc_rogue_shop";

	/** The shop (in a shop room). */
	static void shop(Run r, ServerPlayer p, String notice) {
		List<DialogBody> body = new ArrayList<>();
		if (notice != null) body.add(text(notice, notice.startsWith("✔") ? ChatFormatting.GREEN : ChatFormatting.RED));
		int coins = r.rogue.coins.getOrDefault(p.getUUID(), 0);
		body.add(text("You have " + coins + " ⛁ (coins are for this run only). Reopen: /dungeon shop", ChatFormatting.GOLD));
		List<ActionButton> buttons = new ArrayList<>();
		for (Dungeons.ShopEntry e : cfg().shop) {
			Dungeons.Boon b = cfg().boon(e.boon);
			if (!e.enabled || b == null || !b.enabled || maxed(r, p.getUUID(), b)) continue;
			CompoundTag t = new CompoundTag();
			t.putString("entry", e.id);
			t.putInt("room", r.rogue.depth);
			int price = price(r, e);
			buttons.add(button(b.name + " · " + price + " ⛁", describe(b) + (coins < price ? " (not enough coins)" : ""), "buy", t));
		}
		if (buttons.isEmpty()) body.add(text("Nothing for sale.", ChatFormatting.GRAY));
		open(p, "Shop", body, buttons, true);
	}

	// ---------------------------------------------------------------- unlocks (points)

	private static int cost(Dungeons.Unlock u, int nextLevel) {
		return u.cost * nextLevel;
	}

	/** NPCs open the unlocks with this as their shop. */
	public static final String NPC_SHOP = "rogue_unlocks";

	private static net.minecraft.network.chat.MutableComponent link(String label, String hover, String action, CompoundTag args, ChatFormatting color) {
		return Component.literal("[ " + label + " ]").withStyle(st -> st.withColor(color).withBold(true)
				.withClickEvent(new net.minecraft.network.chat.ClickEvent.Custom(Identifier.fromNamespaceAndPath("nicecontrolcenter", "rogue/" + action), Optional.of(args)))
				.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal(hover))));
	}

	private static ItemStack icon(Dungeons.Unlock u) {
		Identifier id = Identifier.tryParse(u.icon);
		net.minecraft.world.item.Item item = id == null ? net.minecraft.world.item.Items.AIR : BuiltInRegistries.ITEM.getValue(id);
		return new ItemStack(item == net.minecraft.world.item.Items.AIR ? net.minecraft.world.item.Items.EXPERIENCE_BOTTLE : item);
	}

	private static DialogBody row(Dungeons.Unlock u, Component description) {
		return new net.minecraft.server.dialog.body.ItemBody(net.minecraft.world.item.ItemStackTemplate.fromNonEmptyStack(icon(u)), Optional.of(new PlainMessage(description, TEXT_WIDTH - 30)), false, false, 16, 16);
	}

	private static void shopDialog(ServerPlayer p, String title, List<DialogBody> body, List<ActionButton> buttons, ActionButton exit) {
		CommonDialogData common = new CommonDialogData(Component.literal(title), Optional.empty(), true, false, DialogAction.CLOSE, body, List.of());
		if (buttons.isEmpty()) p.openDialog(Holder.direct(new net.minecraft.server.dialog.NoticeDialog(common, exit)));
		else p.openDialog(Holder.direct(new MultiActionDialog(common, buttons, Optional.of(exit), 2)));
	}

	private static String describe(Dungeons.Unlock u) {
		return u.description.isBlank() ? unlockText(u) : u.description;
	}

	/** The unlocks (/dungeon unlocks, or an NPC): balance on top, each unlock with its next level's price and a Buy link. */
	public static void unlocksMenu(ServerPlayer p, String notice) {
		unlocksMenu(p, notice, null);
	}

	public static void unlocksMenu(ServerPlayer p, String notice, String done) {
		Points.Settings ps = PointsStore.settings();
		Dungeons.Rogue g = cfg();
		List<DialogBody> body = new ArrayList<>();
		if (notice != null) {
			body.add(text(notice, ChatFormatting.RED));
			eu.explorerseden.nicecontrolcenter.core.Plop.play(p);
		}
		if (done != null) body.add(text(done, ChatFormatting.GREEN));
		if (!g.unlocksGreeting.isBlank()) body.add(text(g.unlocksGreeting, ChatFormatting.GRAY));
		long balance = PointsStore.balance(p.getStringUUID());
		body.add(new PlainMessage(Component.literal("You have ").withStyle(ChatFormatting.GRAY).append(Component.literal(Points.amount(ps, balance)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)), TEXT_WIDTH));
		boolean any = false;
		for (Dungeons.Unlock u : g.unlocks) {
			if (!u.enabled) continue;
			any = true;
			int lv = Math.min(u.maxLevel, level(p.getUUID(), u.id));
			boolean full = lv >= u.maxLevel;
			int price = cost(u, lv + 1);
			boolean afford = balance >= price;
			net.minecraft.network.chat.MutableComponent desc = Component.empty()
					.append(Component.literal(u.name).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
					.append(Component.literal("  level " + lv + "/" + u.maxLevel).withStyle(full ? ChatFormatting.GREEN : ChatFormatting.GRAY));
			if (!full) desc.append(Component.literal("  " + (price == 0 ? "free" : Points.shortAmount(ps, price))).withStyle(afford ? ChatFormatting.GOLD : ChatFormatting.RED));
			desc.append(Component.literal("\n" + describe(u)).withStyle(ChatFormatting.GRAY)).append(Component.literal("\n"));
			if (full) desc.append(Component.literal("[ every level unlocked ]").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.BOLD));
			else {
				CompoundTag t = new CompoundTag();
				t.putString("unlock", u.id);
				desc.append(link("Buy", afford ? "See level " + (lv + 1) + " and buy it" : "You need " + Points.amount(ps, price - balance) + " more", "unlockview", t, afford ? ChatFormatting.GREEN : ChatFormatting.GRAY));
			}
			body.add(row(u, desc));
		}
		if (!any) body.add(text("Nothing to unlock yet.", ChatFormatting.GRAY));
		shopDialog(p, g.unlocksTitle, body, List.of(), new ActionButton(new CommonButtonData(Component.literal("Close"), 150), Optional.empty()));
	}

	/** One unlock up close, with the Buy button for its next level. */
	private static void unlockView(ServerPlayer p, Dungeons.Unlock u) {
		Points.Settings ps = PointsStore.settings();
		List<DialogBody> body = new ArrayList<>();
		int lv = Math.min(u.maxLevel, level(p.getUUID(), u.id));
		body.add(row(u, Component.literal(u.name).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)
				.append(Component.literal("\n" + describe(u)).withStyle(st -> st.withBold(false).withColor(ChatFormatting.GRAY)))));
		body.add(text("You have level " + lv + " of " + u.maxLevel + ".", ChatFormatting.AQUA));
		long balance = PointsStore.balance(p.getStringUUID());
		List<ActionButton> buttons = new ArrayList<>();
		CompoundTag back = new CompoundTag();
		if (lv >= u.maxLevel) body.add(text("Every level is unlocked.", ChatFormatting.GREEN));
		else {
			int price = cost(u, lv + 1);
			body.add(text("Level " + (lv + 1) + ": " + (price == 0 ? "free" : Points.amount(ps, price)) + " · you have " + Points.amount(ps, balance), balance >= price ? ChatFormatting.GOLD : ChatFormatting.RED));
			if (balance >= price) {
				CompoundTag t = new CompoundTag();
				t.putString("unlock", u.id);
				t.putInt("price", price);
				buttons.add(new ActionButton(new CommonButtonData(Component.literal("Buy for " + Points.amount(ps, price)).withStyle(ChatFormatting.GREEN), Optional.of(Component.literal("Takes the " + ps.name + " and unlocks level " + (lv + 1))), 150),
						Optional.of(new CustomAll(Identifier.fromNamespaceAndPath("nicecontrolcenter", "rogue/unlock"), Optional.of(t)))));
			} else body.add(text("You need " + Points.amount(ps, price - balance) + " more.", ChatFormatting.RED));
		}
		shopDialog(p, cfg().unlocksTitle + " · " + u.name, body, buttons, new ActionButton(new CommonButtonData(Component.literal("Back"), Optional.of(Component.literal("Back to the unlocks")), 150),
				Optional.of(new CustomAll(Identifier.fromNamespaceAndPath("nicecontrolcenter", "rogue/unlocks"), Optional.of(back)))));
	}

	private static String unlockText(Dungeons.Unlock u) {
		return switch (u.kind) {
			case "coins" -> "+" + u.amount + " ⛁ at the start of each run, per level";
			case "boon" -> {
				Dungeons.Boon b = cfg().boon(u.boon);
				yield "start each run with " + (b == null ? "a boon" : b.name) + " (one more per level)";
			}
			case "reroll" -> "one boon reroll per run, per level";
			case "choice" -> "one more boon to choose from, per level";
			default -> "";
		};
	}

	// ---------------------------------------------------------------- clicks

	/** A menu button (nicecontrolcenter:rogue/…): checked against the run again here. */
	public static void handle(ServerPlayer me, String what, Optional<Tag> payload) {
		MinecraftServer server = me.level().getServer();
		CompoundTag in = payload.orElse(null) instanceof CompoundTag t ? t : new CompoundTag();
		if ("unlocks".equals(what)) {
			unlocksMenu(me, null);
			return;
		}
		if ("unlockview".equals(what)) {
			Dungeons.Unlock u = cfg().unlocks.stream().filter(x -> x.id.equals(in.getStringOr("unlock", "")) && x.enabled).findFirst().orElse(null);
			if (u == null) unlocksMenu(me, "That unlock is gone.");
			else unlockView(me, u);
			return;
		}
		if ("unlock".equals(what)) {
			String r = buyUnlock(me, in.getStringOr("unlock", ""), in.getIntOr("price", -1));
			if (r.startsWith("✔")) unlocksMenu(me, null, r);
			else unlocksMenu(me, r);
			return;
		}
		Run r = DungeonRuntime.runOf(me);
		if (r == null || r.rogue == null || r.out.contains(me.getUUID())) {
			me.connection.send(net.minecraft.network.protocol.common.ClientboundClearDialogPacket.INSTANCE);
			return;
		}
		RogueRuns.State st = r.rogue;
		switch (what) {
			case "boon" -> {
				List<String> offer = st.offers.get(me.getUUID());
				int i = in.getIntOr("pick", -1);
				if (offer == null || in.getIntOr("token", -1) != st.offerToken || i < 0 || i >= offer.size()) {
					me.connection.send(net.minecraft.network.protocol.common.ClientboundClearDialogPacket.INSTANCE);
					return;
				}
				Dungeons.Boon b = cfg().boon(offer.get(i));
				st.offers.remove(me.getUUID());
				me.connection.send(net.minecraft.network.protocol.common.ClientboundClearDialogPacket.INSTANCE);
				if (b != null) give(r, me, b, true);
			}
			case "reroll" -> {
				int left = st.rerolls.getOrDefault(me.getUUID(), 0);
				if (!st.offers.containsKey(me.getUUID()) || in.getIntOr("token", -1) != st.offerToken || left <= 0) return;
				st.rerolls.put(me.getUUID(), left - 1);
				List<String> o = roll(r, me.getUUID());
				if (!o.isEmpty()) st.offers.put(me.getUUID(), o);
				menu(r, me, null);
			}
			case "buy" -> shop(r, me, buy(r, me, in.getStringOr("entry", ""), in.getIntOr("room", -1)));
			default -> {
			}
		}
	}

	private static String buy(Run r, ServerPlayer p, String entryId, int room) {
		if (!"shop".equals(r.rogue.type) || room != r.rogue.depth) return "The shop is only in the shop room.";
		Dungeons.ShopEntry e = cfg().shop.stream().filter(x -> x.id.equals(entryId) && x.enabled).findFirst().orElse(null);
		Dungeons.Boon b = e == null ? null : cfg().boon(e.boon);
		if (b == null || !b.enabled) return "That isn't for sale any more.";
		if (maxed(r, p.getUUID(), b)) return "You have as many of " + b.name + " as you can.";
		int price = price(r, e), coins = r.rogue.coins.getOrDefault(p.getUUID(), 0);
		if (coins < price) return "Not enough coins: " + b.name + " costs " + price + " ⛁, you have " + coins + ".";
		r.rogue.coins.put(p.getUUID(), coins - price);
		give(r, p, b, true);
		return "✔ Bought " + b.name + " for " + price + " ⛁.";
	}

	private static String buyUnlock(ServerPlayer p, String id, int seenPrice) {
		Dungeons.Unlock u = cfg().unlocks.stream().filter(x -> x.id.equals(id) && x.enabled).findFirst().orElse(null);
		if (u == null) return "That unlock is gone.";
		int lv = level(p.getUUID(), u.id);
		if (lv >= u.maxLevel) return "You have every level of " + u.name + ".";
		int price = cost(u, lv + 1);
		Points.Settings ps = PointsStore.settings();
		// (The price changed while the menu was open: nothing taken, the menu shows the new one.)
		if (seenPrice >= 0 && seenPrice != price) return "The price of " + u.name + " changed: it's " + Points.amount(ps, price) + " now.";
		if (!PointsStore.take(p.getStringUUID(), p.getGameProfile().name(), price, "Rogue unlock: " + u.name + " " + (lv + 1))) {
			return "Not enough " + ps.name.toLowerCase(Locale.ROOT) + ": " + u.name + " " + (lv + 1) + " costs " + Points.amount(ps, price) + ".";
		}
		unlocks.computeIfAbsent(p.getStringUUID(), k -> new LinkedHashMap<>()).put(u.id, lv + 1);
		saveUnlocks();
		NiceControlCenter.LOGGER.info("Dungeons: {} unlocked {} {} for {}", p.getGameProfile().name(), u.name, lv + 1, Points.amount(ps, price));
		p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.7f, 1.2f);
		return "✔ " + u.name + " " + (lv + 1) + "/" + u.maxLevel + " unlocked.";
	}
}
