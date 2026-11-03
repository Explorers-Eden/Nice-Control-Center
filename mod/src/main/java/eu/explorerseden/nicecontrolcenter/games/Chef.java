package eu.explorerseden.nicecontrolcenter.games;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import eu.explorerseden.nicecontrolcenter.Games;
import eu.explorerseden.nicecontrolcenter.Games.Arena;
import eu.explorerseden.nicecontrolcenter.claims.GameClaims;

/**
 * Cauldron chef (quick round "chef"): each player gets an order of items (chefOrders) from the cook, an NPC from
 * Community → NPCs; they gather and cook it with what the supply chests hold (refilled at the start and every 30
 * seconds) and hand it over by right-clicking the cook. A served order scores its points (+1 when done within 20
 * seconds); the next comes a moment later. Their own things are always put aside.
 */
final class Chef {
	/** Items (with how many) and what it's worth. */
	record Order(Map<Item, Integer> items, int points) {
	}

	private Chef() {
	}

	/** "bread x2, cookie x4 = 5"; unknown items make the line unusable. */
	static List<Order> orders(Arena a) {
		List<Order> out = new ArrayList<>();
		for (String line : a.chefOrders) {
			int eq = line.lastIndexOf('=');
			int points = 1;
			try {
				if (eq >= 0) points = Math.max(1, Integer.parseInt(line.substring(eq + 1).strip()));
			} catch (NumberFormatException e) {
				points = 1;
			}
			Map<Item, Integer> items = new LinkedHashMap<>();
			boolean ok = true;
			for (String part : (eq < 0 ? line : line.substring(0, eq)).split(",")) {
				String t = part.strip().toLowerCase(Locale.ROOT);
				if (t.isEmpty()) continue;
				int n = 1;
				java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.+?)\\s*x\\s*(\\d+)$").matcher(t);
				if (m.matches()) {
					t = m.group(1).strip();
					n = Integer.parseInt(m.group(2));
				}
				Identifier id = Identifier.tryParse(t.contains(":") ? t : "minecraft:" + t);
				Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(id);
				if (item == Items.AIR) {
					ok = false;
					break;
				}
				items.merge(item, Math.max(1, Math.min(64 * 36, n)), Integer::sum);
			}
			if (ok && !items.isEmpty()) out.add(new Order(items, points));
		}
		return out;
	}

	/** "2 × Bread, Cake" (names in the player's own language). */
	static MutableComponent text(Map<Item, Integer> items) {
		MutableComponent c = Component.empty();
		boolean first = true;
		for (Map.Entry<Item, Integer> e : items.entrySet()) {
			if (!first) c.append(", ");
			first = false;
			if (e.getValue() > 1) c.append(e.getValue() + " × ");
			c.append(new ItemStack(e.getKey()).getHoverName());
		}
		return c;
	}

	/** At the start: the supply chests filled, nobody has an order yet (the first comes right away). */
	static void start(ServerLevel level, QuickRuntime.Round r) {
		restock(level, r.arena);
		r.restockAt = System.currentTimeMillis() + 30_000;
		long now = System.currentTimeMillis();
		for (UUID id : r.players) r.orderDue.put(id, now + 1500);
	}

	static void tick(ServerLevel level, QuickRuntime.Round r, List<ServerPlayer> alive) {
		long now = System.currentTimeMillis();
		if (now >= r.restockAt) {
			restock(level, r.arena);
			r.restockAt = now + 30_000;
		}
		List<Order> orders = orders(r.arena);
		if (orders.isEmpty()) return;
		for (ServerPlayer p : alive) {
			UUID id = p.getUUID();
			if (r.orders.containsKey(id) || now < r.orderDue.getOrDefault(id, 0L)) continue;
			Order o = orders.get(QuickRuntime.random().nextInt(orders.size()));
			r.orders.put(id, o);
			r.orderSince.put(id, now);
			p.sendSystemMessage(Words.lit("■ " + cook(r.arena) + " wants: ").withStyle(ChatFormatting.GOLD).append(text(o.items()).withStyle(ChatFormatting.WHITE))
					.append(Component.literal(" (" + o.points() + (o.points() == 1 ? " point)" : " points)")).withStyle(ChatFormatting.GRAY)));
			QuickRuntime.ding(p, SoundEvents.NOTE_BLOCK_BELL.value(), 0.7f, 1.4f);
		}
	}

	/** The action bar while playing: their order and score (null without an order). */
	static Component line(QuickRuntime.Round r, ServerPlayer p) {
		Order o = r.orders.get(p.getUUID());
		long score = r.score.getOrDefault(p.getUUID(), 0L);
		if (o == null) return Words.lit("Next order coming… · " + score + (score == 1 ? " point" : " points")).withStyle(ChatFormatting.AQUA);
		return Words.lit("Order: ").withStyle(ChatFormatting.GOLD).append(text(o.items()).withStyle(ChatFormatting.WHITE))
				.append(Component.literal(" · " + score + (score == 1 ? " point" : " points")).withStyle(ChatFormatting.AQUA));
	}

	private static String cook(Arena a) {
		return a.chefNpc.isBlank() ? "The cook" : a.chefNpc;
	}

	/** A player right-clicks an NPC: true when it's the cook of their chef round (handled here, nothing else runs). */
	static boolean serve(ServerPlayer p, String npcName) {
		QuickRuntime.Round r = QuickRuntime.roundOf(p);
		if (r == null || !r.arena.type.equals("chef") || !r.arena.chefNpc.equalsIgnoreCase(npcName)) return false;
		UUID id = p.getUUID();
		Order o = r.orders.get(id);
		if (o == null) {
			p.sendOverlayMessage(Words.lit("Your next order is coming").withStyle(ChatFormatting.GRAY));
			return true;
		}
		var inv = p.getInventory();
		Map<Item, Integer> missing = new LinkedHashMap<>();
		for (Map.Entry<Item, Integer> e : o.items().entrySet()) {
			int have = inv.countItem(e.getKey());
			if (have < e.getValue()) missing.put(e.getKey(), e.getValue() - have);
		}
		if (!missing.isEmpty()) {
			p.sendOverlayMessage(Words.lit("Still missing: ").withStyle(ChatFormatting.RED).append(text(missing).withStyle(ChatFormatting.WHITE)));
			r.holdBar.put(id, System.currentTimeMillis() + 2500);
			QuickRuntime.ding(p, SoundEvents.VILLAGER_NO, 0.7f, 1.0f);
			return true;
		}
		for (Map.Entry<Item, Integer> e : o.items().entrySet()) {
			int left = e.getValue();
			for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
				ItemStack st = inv.getItem(i);
				if (!st.is(e.getKey())) continue;
				int take = Math.min(left, st.getCount());
				st.shrink(take);
				left -= take;
			}
		}
		p.inventoryMenu.broadcastChanges();
		long now = System.currentTimeMillis();
		boolean quick = now - r.orderSince.getOrDefault(id, now) <= 20_000;
		r.orders.remove(id);
		r.orderDue.put(id, now + r.arena.chefPause * 1000L);
		QuickRuntime.add(r, p, o.points() + (quick ? 1 : 0), quick ? "(quick!)" : "served");
		ServerLevel level = (ServerLevel) p.level();
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getY() + 1.2, p.getZ(), 10, 0.4, 0.4, 0.4, 0);
		QuickRuntime.ding(p, SoundEvents.VILLAGER_YES, 0.8f, 1.1f);
		QuickRuntime.ding(p, SoundEvents.PLAYER_LEVELUP, 0.4f, 1.6f);
		return true;
	}

	/** Every supply chest filled again with what it held when it was set. */
	static void restock(ServerLevel level, Arena a) {
		for (Games.Station st : a.chefStations) {
			if (!st.pos.dim.equals(level.dimension().identifier().toString())) continue;
			BlockPos at = BlockPos.containing(st.pos.x, st.pos.y, st.pos.z);
			if (!level.isLoaded(at) || !(level.getBlockEntity(at) instanceof Container c)) continue;
			c.clearContent();
			for (String json : st.items) {
				ItemStack item = GameClaims.itemFrom(level.registryAccess(), json);
				int slot = GameClaims.itemSlot(json);
				if (item.isEmpty()) continue;
				if (slot >= 0 && slot < c.getContainerSize() && c.getItem(slot).isEmpty()) c.setItem(slot, item);
				else for (int i = 0; i < c.getContainerSize(); i++) {
					if (c.getItem(i).isEmpty()) {
						c.setItem(i, item);
						break;
					}
				}
			}
			c.setChanged();
		}
	}

	/** The chest's contents now (a supply chest's snapshot). */
	static List<String> snapshot(ServerLevel level, Container c) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack st = c.getItem(i);
			if (!st.isEmpty()) out.add(GameClaims.itemJson(level.registryAccess(), st.copy(), i));
		}
		return out;
	}

	/** Why it can't run yet (null when it can). */
	static String check(Arena a) {
		if (a.chefNpc.isBlank()) return a.name + " has no cook yet (an NPC's name, in the panel).";
		if (eu.explorerseden.nicecontrolcenter.npcs.NpcRuntime.settings().npc(a.chefNpc) == null) return a.name + "'s cook " + a.chefNpc + " isn't an NPC (Community → NPCs).";
		if (orders(a).isEmpty()) return a.name + " has no orders that can be made (panel).";
		return null;
	}

	/** Round over: orders forgotten. */
	static void clear(QuickRuntime.Round r) {
		r.orders.clear();
		r.orderSince.clear();
		r.orderDue.clear();
	}
}
