package eu.explorerseden.nicecontrolcenter.claims;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.scores.Objective;

import eu.explorerseden.nicecontrolcenter.Claims;
import eu.explorerseden.nicecontrolcenter.Claims.Claim;
import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.games.GameRuntime;
import eu.explorerseden.nicecontrolcenter.market.MarketStore;
import eu.explorerseden.nicecontrolcenter.tweaks.TweaksControl;

/**
 * Game claims (Claims.AdminOptions, set by admins on server or admin claims): on entering, what the player carries
 * (inventory, armour, off hand, XP, effects) is put aside and a kit handed out; on leaving, kit items vanish, items
 * picked up inside vanish or go to the market inbox, and their own things come back exactly. Dying inside makes no
 * grave; logging out inside sends them home. Players in survival play game claims and dungeon runs in adventure mode
 * (kit items with can_break / can_place_on still mine and build), and kit items left anywhere they carry (ender chest,
 * shulker boxes, bundles) vanish once they're out of the game. Also: no homes or spawn points inside, the Nice Actions back teleport (dialog) can't lead
 * back in, and the Nice Actions home whitelist (Tweaks.homes).
 */
public final class GameClaims {
	/** Custom-data marks on items: which claim they belong to and how they came (kit or pickup). */
	public static final String MARK = "ncc_claim";
	private static final String KIND = "ncc_claim_kind";
	/**
	 * Any item with this in its custom data (custom_data={ncc_game_only:1b}: loot tables, chests, mob drops, /give)
	 * is a game item like a kit: it vanishes when its player leaves the dungeon, wave game, quick round or game area,
	 * and anywhere outside one.
	 */
	public static final String GAME_ONLY = "ncc_game_only";

	/** What a player had before entering a game claim. */
	static final class Stash {
		String claim;
		/** Slot → the stack as the game writes it. */
		Map<Integer, String> items = new LinkedHashMap<>();
		int xpLevel;
		float xpProgress;
		int xpTotal;
		List<String> effects = new ArrayList<>();
		/** Health, hunger and saturation on entering (-1: not noted, older files). */
		float health = -1;
		int food = -1;
		float saturation = -1;
	}

	/** Health, hunger and saturation on entering a game claim without "inventory aside" (the stash keeps them otherwise). */
	private static final Map<UUID, float[]> vitals = new HashMap<>();

	private static final Map<String, Stash> stashes = new HashMap<>();
	/** The game claim each online player is in right now (null outside). */
	private static final Map<UUID, String> inside = new HashMap<>();
	/** Died where the hub spawn takes over: moved there once they respawn. */
	private static final Set<UUID> toHubOnRespawn = new java.util.HashSet<>();
	/**
	 * Items picked up inside, held through a death (the claim doesn't drop them): back if they respawn inside, gone
	 * if the death sends them out. Claim id, or null for a dungeon run (DungeonRuntime gives them back).
	 */
	private record Kept(String claim, List<ItemStack> items) { }
	private static final Map<UUID, Kept> keptOnDeath = new HashMap<>();
	/** Deaths in the game claim they're in, this visit. */
	private static final Map<UUID, Integer> deathCount = new HashMap<>();
	/** Where they walked in this visit (x, y, z, yaw): the respawn point when the claim has none set. */
	private static final Map<UUID, double[]> entryPoint = new HashMap<>();
	/** Died with deaths left: respawn inside this claim. */
	private static final Map<UUID, String> respawnIn = new HashMap<>();
	private static Path file;
	/** Players put into another game mode by a game (uuid → the mode they were put in; survival again once they're out); kept across restarts. */
	private static final Map<String, String> adventure = new java.util.HashMap<>();
	private static Path adventureFile;

	private GameClaims() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(GameClaims::load);
		ServerTickEvents.START_SERVER_TICK.register(GameClaims::triggers);
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 5 == 0) tick(server);
		});
		// Before death drops: game items are dealt with here, so no grave and nothing of theirs is lost.
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			// A quick round knocks the player out instead (healed, nothing dropped).
			if (entity instanceof ServerPlayer p && eu.explorerseden.nicecontrolcenter.games.QuickRuntime.knockOut(p)) return false;
			if (entity instanceof ServerPlayer p) {
				// (Not while a dungeon brings them back to its checkpoint.)
				if (GameRuntime.hubCovers(p) && !eu.explorerseden.nicecontrolcenter.games.DungeonRuntime.respawnsInside(p) && !eu.explorerseden.nicecontrolcenter.games.WaveRuntime.inGame(p) && !eu.explorerseden.nicecontrolcenter.games.QuickRuntime.inGame(p)) {
					toHubOnRespawn.add(p.getUUID());
					NiceControlCenter.LOGGER.info("Games: {} died in {}: respawns at the hub spawn", p.getGameProfile().name(), p.level().dimension().identifier());
				} else if (GameRuntime.hubCovers(p)) {
					NiceControlCenter.LOGGER.info("Games: {} died in {}: their game brings them back ({})", p.getGameProfile().name(), p.level().dimension().identifier(),
							eu.explorerseden.nicecontrolcenter.games.DungeonRuntime.respawnsInside(p) ? "dungeon checkpoint" : eu.explorerseden.nicecontrolcenter.games.WaveRuntime.inGame(p) ? "wave arena" : "quick round");
				}
				died(p);
			}
			return true;
		});
		// Died in a game area or the hub's dimension: they respawn at the hub spawn instead of their bed or home.
		ServerPlayerEvents.AFTER_RESPAWN.register((old, now, alive) -> {
			respawnInside(now);
			Kept kept = keptOnDeath.get(now.getUUID());
			if (kept != null && kept.claim() != null) {
				Claim at = gameClaimFor(now);
				if (!toHubOnRespawn.contains(now.getUUID()) && at != null && at.id.equals(kept.claim())) giveBackKept(now);
				else forgetKept(now);
			}
			if (toHubOnRespawn.remove(now.getUUID()) && !alive) GameRuntime.toHubAfterRespawn(now.level().getServer(), now);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> loggedOut(server, handler.player));
		// A restart kicks everyone without the usual logout: the same happens for them here, before they're saved.
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
				try {
					loggedOut(server, p);
				} catch (RuntimeException e) {
					NiceControlCenter.LOGGER.warn("Games: {} couldn't be moved out at the shutdown: {}", p.getGameProfile().name(), e.toString());
				}
			}
		});
		// Back in the hub's dimension (after a crash, or anything that skipped the logout): to the hub spawn.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> {
			ServerPlayer p = handler.player;
			eu.explorerseden.nicecontrolcenter.Games.Pos hub = GameRuntime.settings().hub;
			if (hub == null || !p.isAlive() || !p.level().dimension().identifier().toString().equals(hub.dim)) return;
			if (p.distanceToSqr(hub.x, hub.y, hub.z) <= 4 * 4) return;
			NiceControlCenter.LOGGER.info("Games: {} joined in {} away from the hub spawn: sent there", p.getGameProfile().name(), hub.dim);
			GameRuntime.toHub(server, p);
		}));
		// No ender pearls in games: they'd throw players out of the game or over its walls.
		net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) -> {
			if (!(player instanceof ServerPlayer p) || !player.getItemInHand(hand).is(net.minecraft.world.item.Items.ENDER_PEARL) || !inAnyGame(p)) return net.minecraft.world.InteractionResult.PASS;
			p.sendOverlayMessage(Component.literal("No ender pearls in games").withStyle(ChatFormatting.RED));
			return net.minecraft.world.InteractionResult.FAIL;
		});
		// No ender chests where things are put aside: they'd carry game items out (or own things in).
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (!(player instanceof ServerPlayer p) || !(world.getBlockState(hit.getBlockPos()).getBlock() instanceof net.minecraft.world.level.block.EnderChestBlock)) {
				return net.minecraft.world.InteractionResult.PASS;
			}
			if (!inInventoryClaim(p) && !inKitClaim(p)) return net.minecraft.world.InteractionResult.PASS;
			p.sendOverlayMessage(Component.literal("Ender chests don't open in this game area").withStyle(ChatFormatting.RED));
			return net.minecraft.world.InteractionResult.FAIL;
		});
	}

	/** A game item (kit or picked up in a game area) lying outside its area: it vanishes (no smuggling over the edge). */
	public static boolean strayGameItem(ItemEntity item) {
		CustomData d = item.getItem().get(DataComponents.CUSTOM_DATA);
		if (d == null) return false;
		CompoundTag t = d.copyTag();
		if (!t.contains(MARK)) return false;
		String claim = t.getStringOr(MARK, "");
		Claim here = ClaimStore.claimAt(item.level(), item.blockPosition());
		// Inside its claim, or a subclaim of it, or the claim around its subclaim: still in the game.
		if (here != null && (here.id.equals(claim) || claim.equals(here.parent))) return false;
		Claim own = ClaimStore.data().claims.get(claim);
		return own == null || here == null || own.parent == null || !own.parent.equals(here.id);
	}

	// ---------------------------------------------------------------- which claim

	/** The game claim at the player's spot, or null. */
	/** The game claim the player is in; none for an admin bypassing claims (/claim admin bypass): no inventory aside, kit, game mode or limits. */
	public static Claim gameClaimFor(ServerPlayer p) {
		return ClaimTool.bypassing(p) ? null : gameClaimAt(p.level(), p.blockPosition());
	}

	public static Claim gameClaimAt(Level level, BlockPos pos) {
		Claim c = ClaimStore.claimAt(level, pos);
		return c != null && c.admin != null && c.admin.active() ? c : null;
	}

	public static Claim gameClaimAt(String dim, int x, int z) {
		Claim c = ClaimStore.claimAt(dim, x, z);
		return c != null && c.admin != null && c.admin.active() ? c : null;
	}

	/** Inside a game claim that puts inventories aside (for blocking graves). */
	public static boolean inInventoryClaim(ServerPlayer p) {
		if (eu.explorerseden.nicecontrolcenter.games.DungeonRuntime.noGrave(p) || eu.explorerseden.nicecontrolcenter.games.WaveRuntime.noGrave(p) || eu.explorerseden.nicecontrolcenter.games.QuickRuntime.noGrave(p)) return true;
		Claim c = gameClaimFor(p);
		return c != null && c.admin.inventory;
	}

	private static boolean inKitClaim(ServerPlayer p) {
		Claim c = gameClaimFor(p);
		return c != null && kitCount(c.admin.kit, c.admin.moreKits) > 0;
	}

	/**
	 * Adventure mode in a game claim: the item's can_break (breaking {@code at}) or can_place_on (using it on
	 * {@code at}, landing at {@code lands}) decides instead of the claim's roles; the game checks the rest.
	 */
	public static boolean itemAllows(ServerPlayer p, Level level, BlockPos at, BlockPos lands, ItemStack held, boolean breaking) {
		if (p.gameMode.getGameModeForPlayer() != GameType.ADVENTURE || held.isEmpty()) return false;
		if (gameClaimAt(level, at) == null || gameClaimAt(level, lands) == null) return false;
		var block = new net.minecraft.world.level.block.state.pattern.BlockInWorld(level, at, false);
		return breaking ? held.canBreakBlockInAdventureMode(block) : held.canPlaceOnBlockInAdventureMode(block);
	}

	/** In any game area: the market, the inbox and pets don't work there (they'd carry things in or out). */
	public static boolean inGame(ServerPlayer p) {
		return eu.explorerseden.nicecontrolcenter.games.DungeonRuntime.inRun(p) || eu.explorerseden.nicecontrolcenter.games.WaveRuntime.inGame(p) || eu.explorerseden.nicecontrolcenter.games.QuickRuntime.inGame(p) || gameClaimFor(p) != null;
	}

	/** True (with a message) when the player is in a game area, so {@code what} is refused. */
	public static boolean refuse(ServerPlayer p, String what) {
		if (!inGame(p)) return false;
		p.sendOverlayMessage(Component.literal(what + " can't be used in this game area").withStyle(ChatFormatting.RED));
		return true;
	}

	/** No homes or spawn points here (a game claim that says so). */
	public static boolean blocksHomes(Level level, BlockPos pos) {
		Claim c = gameClaimAt(level, pos);
		return c != null && c.admin.blocksHomes();
	}

	// ---------------------------------------------------------------- entering and leaving

	private static void tick(MinecraftServer server) {
		boolean sweep = server.getTickCount() % 20 == 0;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!p.isAlive()) continue;
			gameMode(p, sweep);
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			// Dungeon runs, wave games and quick rounds put things aside and give them back themselves.
			if (!p.isAlive() || eu.explorerseden.nicecontrolcenter.games.DungeonRuntime.inRun(p) || eu.explorerseden.nicecontrolcenter.games.WaveRuntime.inGame(p) || eu.explorerseden.nicecontrolcenter.games.QuickRuntime.inGame(p)) continue;
			Claim now = gameClaimFor(p);
			String nowId = now == null ? null : now.id;
			String was = inside.get(p.getUUID());
			Stash kept = stashes.get(p.getStringUUID());
			// After a crash or restart while inside the same claim: carry on (their stash is still kept).
			if (was == null && nowId != null && kept != null && nowId.equals(kept.claim)) {
				inside.put(p.getUUID(), nowId);
				continue;
			}
			if (java.util.Objects.equals(was, nowId)) {
				// A stash left from before (a crash, a restart) while they're outside: given back.
				if (nowId == null && stashes.containsKey(p.getStringUUID())) leave(p, null);
				continue;
			}
			if (was != null || kept != null) leave(p, ClaimStore.data().claims.get(was != null ? was : kept.claim));
			if (now != null) enter(p, now);
			if (nowId == null) inside.remove(p.getUUID());
			else inside.put(p.getUUID(), nowId);
		}
	}

	private static void enter(ServerPlayer p, Claim c) {
		Claims.AdminOptions o = c.admin;
		deathCount.remove(p.getUUID());
		entryPoint.put(p.getUUID(), new double[] { p.getX(), p.getY(), p.getZ(), p.getYRot() });
		// Health and hunger as they come in: given back on leaving (a fight inside doesn't follow them out).
		vitals.put(p.getUUID(), new float[] { p.getHealth(), p.getFoodData().getFoodLevel(), p.getFoodData().getSaturationLevel() });
		if (o.inventory) {
			Stash s = new Stash();
			s.claim = c.id;
			var inv = p.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				ItemStack st = inv.getItem(i);
				if (!st.isEmpty()) s.items.put(i, encode(p, st));
			}
			s.xpLevel = p.experienceLevel;
			s.xpProgress = p.experienceProgress;
			s.xpTotal = p.totalExperience;
			s.health = p.getHealth();
			s.food = p.getFoodData().getFoodLevel();
			s.saturation = p.getFoodData().getSaturationLevel();
			for (MobEffectInstance e : p.getActiveEffects()) {
				MobEffectInstance.CODEC.encodeStart(p.registryAccess().createSerializationContext(JsonOps.INSTANCE), e).result().ifPresent(j -> s.effects.add(j.toString()));
			}
			stashes.put(p.getStringUUID(), s);
			save();
			inv.clearContent();
			p.setExperienceLevels(0);
			p.setExperiencePoints(0);
			p.removeAllEffects();
		}
		for (String json : pickKit(o.kit, o.moreKits)) handOut(p, json, c.id);
		p.inventoryMenu.broadcastChanges();
	}

	/** Leaving (c may be gone): game items are handled, then their own things come back. */
	private static void leave(ServerPlayer p, Claim c) {
		Claims.AdminOptions o = c == null ? null : c.admin;
		// A visit ends: deaths start over next time.
		deathCount.remove(p.getUUID());
		entryPoint.remove(p.getUUID());
		Stash s = stashes.get(p.getStringUUID());
		boolean swap = s != null;
		boolean remove = o != null && o.removePickups;
		var inv = p.getInventory();
		List<ItemStack> toInbox = new ArrayList<>();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack st = inv.getItem(i);
			if (st.isEmpty()) continue;
			String kind = kind(st);
			// With the swap, everything carried now is from the game; without it only what's marked.
			if (kind == null && !swap) {
				removeKitInside(st);
				continue;
			}
			inv.setItem(i, ItemStack.EMPTY);
			if ("kit".equals(kind) || remove) continue;
			toInbox.add(unmark(st));
		}
		if (swap) {
			for (Map.Entry<Integer, String> e : s.items.entrySet()) {
				ItemStack st = decode(p, e.getValue());
				if (st.isEmpty()) continue;
				if (inv.getItem(e.getKey()).isEmpty()) inv.setItem(e.getKey(), st);
				else if (!inv.add(st)) toInbox.add(st);
			}
			p.setExperienceLevels(s.xpLevel);
			p.experienceProgress = s.xpProgress;
			p.totalExperience = s.xpTotal;
			p.removeAllEffects();
			for (String json : s.effects) {
				MobEffectInstance.CODEC.parse(p.registryAccess().createSerializationContext(JsonOps.INSTANCE), JsonParser.parseString(json)).result().ifPresent(p::addEffect);
			}
			if (s.health > 0) vitals.put(p.getUUID(), new float[] { s.health, s.food, s.saturation });
			stashes.remove(p.getStringUUID());
			save();
		}
		// Health and hunger back to what they were on entering.
		float[] v = vitals.remove(p.getUUID());
		if (v != null && p.isAlive()) {
			p.setHealth(Math.min(p.getMaxHealth(), Math.max(1, v[0])));
			p.getFoodData().setFoodLevel(Math.round(v[1]));
			p.getFoodData().setSaturation(v[2]);
		}
		inbox(p, toInbox);
		p.inventoryMenu.broadcastChanges();
	}

	/**
	 * Dying inside: kit items vanish, picked-up ones drop where they died (option) or are held: given back if they
	 * respawn inside the claim, gone if they're sent out. Never a grave.
	 */
	private static void died(ServerPlayer p) {
		String id = inside.get(p.getUUID());
		Claim c = id == null ? null : ClaimStore.data().claims.get(id);
		if (c == null || c.admin == null) return;
		// Deaths left: they respawn inside (at the claim's point); the last one sends them out and starts over.
		boolean back = true;
		if (c.admin.respawnsInside()) {
			int n = deathCount.merge(p.getUUID(), 1, Integer::sum);
			back = c.admin.deaths == 0 || n < c.admin.deaths;
			if (back) {
				respawnIn.put(p.getUUID(), c.id);
				toHubOnRespawn.remove(p.getUUID());
				if (c.admin.deaths > 0) {
					int left = c.admin.deaths - n;
					p.sendSystemMessage(Component.literal("You died (" + n + " of " + c.admin.deaths + "): " + left + (left == 1 ? " more death sends" : " more deaths send") + " you out.").withStyle(ChatFormatting.GOLD));
				}
			} else {
				deathCount.remove(p.getUUID());
				p.sendSystemMessage(Component.literal("That was your last death in " + c.displayName() + ": you're out.").withStyle(ChatFormatting.RED));
			}
		}
		boolean swap = stashes.containsKey(p.getStringUUID());
		var inv = p.getInventory();
		List<ItemStack> keep = new ArrayList<>();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack st = inv.getItem(i);
			if (st.isEmpty()) continue;
			String kind = kind(st);
			if (kind == null && !swap) continue;
			inv.setItem(i, ItemStack.EMPTY);
			if ("kit".equals(kind)) {
				if (c.admin.keepKitOnDeath && back) keep.add(st);
				continue;
			}
			if (!back) continue;
			if (c.admin.dropPickupsOnDeath) {
				ItemEntity drop = new ItemEntity(p.level(), p.getX(), p.getY() + 0.5, p.getZ(), unmark(st));
				drop.setDefaultPickUpDelay();
				p.level().addFreshEntity(drop);
			} else {
				keep.add(st);
			}
		}
		if (!keep.isEmpty()) keptOnDeath.put(p.getUUID(), new Kept(c.id, keep));
		if (swap) {
			// No XP drops from the game either: their own XP comes back with the stash.
			p.setExperienceLevels(0);
			p.setExperiencePoints(0);
		}
	}

	/**
	 * After respawning: with deaths left, to the claim's respawn point (or where they entered); after the last one,
	 * out of the claim if the game's respawn put them inside.
	 */
	private static void respawnInside(ServerPlayer p) {
		String id = respawnIn.remove(p.getUUID());
		Claim c = id == null ? null : ClaimStore.data().claims.get(id);
		if (c == null || c.admin == null) {
			Claim here = gameClaimFor(p);
			if (here != null && here.admin.respawnsInside() && !deathCount.containsKey(p.getUUID())) sendHome(p.level().getServer(), p);
			return;
		}
		double[] at = c.admin.respawn != null && c.admin.respawn.length >= 4 ? c.admin.respawn : entryPoint.get(p.getUUID());
		ServerLevel level = p.level().getServer().getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(c.dim)));
		if (at == null || level == null) return;
		p.teleportTo(level, at[0], at[1], at[2], Set.of(), (float) at[3], 0, true);
		p.resetFallDistance();
	}

	/** What was held through a death goes back into their inventory (or at their feet). */
	public static void giveBackKept(ServerPlayer p) {
		Kept back = keptOnDeath.remove(p.getUUID());
		if (back == null) return;
		for (ItemStack st : back.items()) {
			if (!p.getInventory().add(st)) p.level().addFreshEntity(new ItemEntity(p.level(), p.getX(), p.getY(), p.getZ(), st));
		}
		p.inventoryMenu.broadcastChanges();
	}

	/** Sent out by the death: what was held is gone. */
	public static void forgetKept(ServerPlayer p) {
		keptOnDeath.remove(p.getUUID());
	}

	/**
	 * Logging out inside: things back, and out to the hub spawn (or their home, or respawn point) before the player
	 * is saved. Logging out anywhere in the hub's dimension also lands them at the hub spawn.
	 */
	private static void loggedOut(MinecraftServer server, ServerPlayer p) {
		toHubOnRespawn.remove(p.getUUID());
		// A wave game or quick round gives things back and moves them out itself (its own disconnect handler).
		if (eu.explorerseden.nicecontrolcenter.games.WaveRuntime.inGame(p) || eu.explorerseden.nicecontrolcenter.games.QuickRuntime.inGame(p)) return;
		// Logged out while dead: out of the game, so what was held is gone.
		forgetKept(p);
		respawnIn.remove(p.getUUID());
		boolean hub = GameRuntime.hubCovers(p);
		String id = inside.remove(p.getUUID());
		Claim c = id == null ? null : ClaimStore.data().claims.get(id);
		if (c == null && !stashes.containsKey(p.getStringUUID())) {
			if (hub && p.isAlive()) GameRuntime.toHub(server, p);
		} else {
			leave(p, c);
			sendHome(server, p);
		}
		// Saved in survival once they're out (dungeon runs end after this and are caught on the next login).
		if (p.isAlive()) gameMode(p, false);
	}

	private static void sendHome(MinecraftServer server, ServerPlayer p) {
		if (GameRuntime.toHub(server, p)) return;
		// The Nice Actions home, if it's allowed and not in a game claim.
		CompoundTag home = playerData(server, p.getUUID()).getCompoundOrEmpty("home");
		String dim = home.getStringOr("dimension", "");
		if (!dim.isEmpty() && homeAllowed(dim) && gameClaimAt(dim, home.getIntOr("x", 0), home.getIntOr("z", 0)) == null) {
			ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dim)));
			if (level != null) {
				p.teleportTo(level, home.getIntOr("x", 0) + 0.5, home.getIntOr("y", 64), home.getIntOr("z", 0) + 0.5, Set.of(), p.getYRot(), p.getXRot(), true);
				return;
			}
		}
		// Else where they'd respawn (bed, anchor or world spawn).
		var to = p.findRespawnPositionAndUseSpawnBlock(false, net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING);
		p.teleportTo(to.newLevel(), to.position().x, to.position().y, to.position().z, Set.of(), to.yRot(), to.xRot(), true);
	}

	/**
	 * Chorus fruit doesn't teleport in game areas, dungeon runs, wave games or anywhere in the dungeon dimension
	 * (it would skip parts or leave the box); foxes and other mobs eating it there stay put too.
	 */
	public static boolean blocksChorus(net.minecraft.world.entity.LivingEntity e) {
		if (eu.explorerseden.nicecontrolcenter.games.DungeonRuntime.dungeonWorld(e.level())) return true;
		if (e instanceof ServerPlayer p) return inGame(p);
		return gameClaimAt(e.level(), e.blockPosition()) != null;
	}

	// ---------------------------------------------------------------- adventure mode, kit leftovers

	/** In a game claim, or in a dungeon run inside the dungeon (not while waiting on the pad). */
	/** In a game of any kind: a game area, a dungeon run, a wave game, a quick round, or a parkour, race or maze run. */
	public static boolean inAnyGame(ServerPlayer p) {
		return playing(p) || gameClaimFor(p) != null || eu.explorerseden.nicecontrolcenter.games.GameRuntime.inRun(p);
	}

	private static boolean playing(ServerPlayer p) {
		return playMode(p) != null;
	}

	/**
	 * The mode survival players play in where they are: adventure in dungeon runs, wave games and quick rounds, the
	 * game claim's own choice in a game claim (null for survival: left alone), null outside games.
	 */
	private static GameType playMode(ServerPlayer p) {
		if (eu.explorerseden.nicecontrolcenter.games.DungeonRuntime.inRun(p) && eu.explorerseden.nicecontrolcenter.games.DungeonRuntime.inDungeonDimension(p)) return GameType.ADVENTURE;
		if (eu.explorerseden.nicecontrolcenter.games.WaveRuntime.inGame(p) || eu.explorerseden.nicecontrolcenter.games.QuickRuntime.inGame(p)) return GameType.ADVENTURE;
		Claim c = gameClaimFor(p);
		if (c == null) return null;
		return switch (c.admin.mode()) {
			case "creative" -> GameType.CREATIVE;
			case "spectator" -> GameType.SPECTATOR;
			case "survival" -> null;
			default -> GameType.ADVENTURE;
		};
	}

	/**
	 * Survival players in a game play in its mode (adventure, or a game claim's own choice); out of it they're in
	 * survival again (only if still in the mode the game put them in: a mode an admin gave them, or a dungeon's
	 * spectating, is left alone). With {@code sweep}, kit items they still have outside a game vanish.
	 */
	public static void gameMode(ServerPlayer p, boolean sweep) {
		String id = p.getStringUUID();
		GameType want = playMode(p);
		GameType now = p.gameMode.getGameModeForPlayer();
		String set = adventure.get(id);
		boolean ours = set != null && now.getName().equals(set);
		if (want != null) {
			// From survival, or from the mode another game area put them in.
			if (now != want && (now == GameType.SURVIVAL && set == null || ours)) {
				p.setGameMode(want);
				adventure.put(id, want.getName());
				saveAdventure();
			}
			return;
		}
		if (set != null) {
			adventure.remove(id);
			if (ours) {
				p.setGameMode(GameType.SURVIVAL);
				// Spectators or fliers coming back to survival in the air shouldn't fall to their death.
				p.resetFallDistance();
			}
			saveAdventure();
		}
		// (Not in a game area played in survival, nor in a parkour, race or maze run: they're still in the game there.)
		if (sweep && gameClaimFor(p) == null && !eu.explorerseden.nicecontrolcenter.games.GameRuntime.inRun(p)
				&& (removeKits(p.getInventory()) | removeKits(p.getEnderChestInventory()))) {
			p.inventoryMenu.broadcastChanges();
		}
	}

	/** Kit items in the container (also inside shulker boxes and bundles) vanish; true if any did. */
	private static boolean removeKits(Container c) {
		boolean any = false;
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack st = c.getItem(i);
			if (st.isEmpty()) continue;
			if ("kit".equals(kind(st))) {
				c.setItem(i, ItemStack.EMPTY);
				any = true;
			} else {
				any |= removeKitInside(st);
			}
		}
		return any;
	}

	private static boolean removeKitInside(ItemStack st) {
		boolean any = false;
		ItemContainerContents box = st.get(DataComponents.CONTAINER);
		if (box != null && !box.equals(ItemContainerContents.EMPTY)) {
			NonNullList<ItemStack> items = NonNullList.withSize(256, ItemStack.EMPTY);
			box.copyInto(items);
			boolean kit = false;
			for (int i = 0; i < items.size(); i++) {
				if (!items.get(i).isEmpty() && "kit".equals(kind(items.get(i)))) {
					items.set(i, ItemStack.EMPTY);
					kit = true;
				}
			}
			if (kit) st.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
			any = kit;
		}
		BundleContents bag = st.get(DataComponents.BUNDLE_CONTENTS);
		if (bag != null && !bag.items().isEmpty()) {
			List<ItemStackTemplate> keep = bag.items().stream().filter(t -> !"kit".equals(kind(t.create()))).toList();
			if (keep.size() != bag.items().size()) {
				st.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(keep));
				any = true;
			}
		}
		return any;
	}

	// ---------------------------------------------------------------- for dungeons

	/** Puts what the player carries aside (saved at once) and clears it; nothing if something's put aside already. */
	public static void stashFor(ServerPlayer p, String key) {
		if (stashes.containsKey(p.getStringUUID())) return;
		Claim pseudo = new Claim();
		pseudo.id = key;
		pseudo.name = "Dungeon";
		pseudo.admin = new Claims.AdminOptions();
		pseudo.admin.inventory = true;
		enter(p, pseudo);
	}

		/** One of the kits at random (the first and the more ones, the empty ones left out); empty when there's none. */
	public static List<String> pickKit(List<String> kit, List<List<String>> more) {
		List<List<String>> all = new ArrayList<>();
		if (kit != null && !kit.isEmpty()) all.add(kit);
		if (more != null) for (List<String> k : more) if (k != null && !k.isEmpty()) all.add(k);
		if (all.isEmpty()) return List.of();
		return all.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(all.size()));
	}

	/** How many kits there are ("2 kits (a random one each)"). */
	public static int kitCount(List<String> kit, List<List<String>> more) {
		int n = kit != null && !kit.isEmpty() ? 1 : 0;
		if (more != null) for (List<String> k : more) if (k != null && !k.isEmpty()) n++;
		return n;
	}

	/** Hands out kit items (they vanish when the player leaves or dies). */
	public static void giveKit(ServerPlayer p, List<String> kit, String key) {
		if (kit == null) return;
		for (String json : kit) handOut(p, json, key);
		p.inventoryMenu.broadcastChanges();
	}

	/** Hands out one kit item (a quick round's snowballs or rod): it vanishes when the player leaves or dies. */
	public static void giveKitItem(ServerPlayer p, ItemStack st, String key) {
		if (st.isEmpty()) return;
		mark(st, key, "kit");
		if (!p.getInventory().add(st)) p.level().addFreshEntity(new ItemEntity(p.level(), p.getX(), p.getY(), p.getZ(), st));
		p.inventoryMenu.broadcastChanges();
	}

	/** Gives back what was put aside; what they carry now vanishes (kit, removePickups) or goes to the inbox. */
	public static void restoreFor(ServerPlayer p, boolean removePickups) {
		if (!stashes.containsKey(p.getStringUUID())) return;
		Claim pseudo = new Claim();
		pseudo.admin = new Claims.AdminOptions();
		pseudo.admin.removePickups = removePickups;
		leave(p, pseudo);
	}

	public static boolean hasStash(ServerPlayer p) {
		return stashes.containsKey(p.getStringUUID());
	}

	/**
	 * Dying in a dungeon run with things put aside: kit items vanish; what they found is held when they'll respawn
	 * inside ({@code keep}, given back with {@link #giveBackKept}), else it's gone. No XP drops.
	 */
	public static void clearCarried(ServerPlayer p, boolean keep) {
		var inv = p.getInventory();
		List<ItemStack> held = new ArrayList<>();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack st = inv.getItem(i);
			if (st.isEmpty()) continue;
			inv.setItem(i, ItemStack.EMPTY);
			// Found inside (game-only items too, they were found there); the kit is handed out again anyway.
			if (keep && (!"kit".equals(kind(st)) || gameOnly(st))) held.add(st);
		}
		if (!held.isEmpty()) keptOnDeath.put(p.getUUID(), new Kept(null, held));
		p.setExperienceLevels(0);
		p.setExperiencePoints(0);
	}

	/** The admin's current inventory as kit JSON (for a dungeon's kit). */
	public static List<String> kitFrom(ServerPlayer admin) {
		List<String> kit = new ArrayList<>();
		var inv = admin.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack st = inv.getItem(i);
			if (!st.isEmpty()) kit.add(encodeSlot(admin, unmark(st.copy()), i));
		}
		return kit;
	}

	/**
	 * One kit item into the player's inventory: in the slot it had in the kit (hotbar, armour, off hand…) when that's
	 * free, else anywhere, else at their feet. Older kits without slots go anywhere.
	 */
	private static void handOut(ServerPlayer p, String json, String key) {
		ItemStack st = decode(p, json);
		if (st.isEmpty()) return;
		mark(st, key, "kit");
		int slot = slotOf(json);
		var inv = p.getInventory();
		if (slot >= 0 && slot < inv.getContainerSize() && inv.getItem(slot).isEmpty()) {
			inv.setItem(slot, st);
			return;
		}
		if (!inv.add(st)) p.level().addFreshEntity(new ItemEntity(p.level(), p.getX(), p.getY(), p.getZ(), st));
	}

	// ---------------------------------------------------------------- pickups

	/** From the pickup mixin: an item picked up inside a game claim gets the claim's mark. */
	public static void pickedUp(ServerPlayer p, ItemStack stack) {
		String id = inside.get(p.getUUID());
		if (id == null || stack.isEmpty() || kind(stack) != null) return;
		mark(stack, id, "pickup");
	}

	private static void mark(ItemStack st, String claim, String kind) {
		CustomData.update(DataComponents.CUSTOM_DATA, st, t -> {
			t.putString(MARK, claim);
			t.putString(KIND, kind);
		});
	}

	private static String kind(ItemStack st) {
		CustomData d = st.get(DataComponents.CUSTOM_DATA);
		if (d == null) return null;
		CompoundTag t = d.copyTag();
		if (t.contains(GAME_ONLY)) return "kit";
		return t.contains(MARK) ? t.getStringOr(KIND, "pickup") : null;
	}

	/** Tagged to stay in the game (see {@link #GAME_ONLY}). */
	private static boolean gameOnly(ItemStack st) {
		CustomData d = st.get(DataComponents.CUSTOM_DATA);
		return d != null && d.copyTag().contains(GAME_ONLY);
	}

	private static ItemStack unmark(ItemStack st) {
		CustomData d = st.get(DataComponents.CUSTOM_DATA);
		if (d == null) return st;
		CompoundTag t = d.copyTag();
		t.remove(MARK);
		t.remove(KIND);
		if (t.isEmpty()) st.remove(DataComponents.CUSTOM_DATA);
		else st.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
		return st;
	}

	/** Items into the player's market inbox (mailbox parcels), with a line in chat. */
	private static void inbox(ServerPlayer p, List<ItemStack> items) {
		if (items.isEmpty()) return;
		var data = MarketStore.data();
		var box = data.mailbox(p.getStringUUID(), p.getPlainTextName());
		int n = 0;
		for (ItemStack st : items) {
			eu.explorerseden.nicecontrolcenter.Market.Parcel parcel = new eu.explorerseden.nicecontrolcenter.Market.Parcel();
			parcel.item = MarketStore.store(p, st);
			parcel.amount = st.getCount();
			box.parcels.add(parcel);
			n += st.getCount();
		}
		data.mailChanged(box);
		MarketStore.saveNow();
		p.sendSystemMessage(Component.literal(n + " item" + (n == 1 ? "" : "s") + " you picked up went to your inbox: /inbox").withStyle(ChatFormatting.GOLD));
	}

	// ---------------------------------------------------------------- homes and the back teleport (Nice Actions dialog)

	private static final String TRIGGER = "nice_actions.dialog_trigger";

	/**
	 * Before the data packs' tick: Nice Actions teleports (3 random, 4 spawn, 6 home, 15 back) and set-home (5, 16) don't
	 * run during a game (a countdown already going is cancelled); set-home and back where they aren't allowed neither.
	 */
	private static void triggers(MinecraftServer server) {
		Objective obj = server.getScoreboard().getObjective(TRIGGER);
		if (obj == null) return;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			boolean playing = playingNow(p);
			// A teleport counting down when the game started (or started meanwhile) is called off.
			if (playing && p.entityTags().contains("nice_actions.warmup.active")) {
				eu.explorerseden.nicecontrolcenter.log.ConsoleRunner.quiet(server, "execute as " + p.getStringUUID() + " run function nice_actions:warmup/cancel");
			}
			var info = server.getScoreboard().getPlayerScoreInfo(p, obj);
			if (info == null) continue;
			int v = info.value();
			String why = null;
			// Teleports (random, spawn, home, back) and setting a home don't run while they play.
			if (playing && (v == 3 || v == 4 || v == 6 || v == 15 || v == 5 || v == 16)) {
				server.getScoreboard().getOrCreatePlayerScore(p, obj).set(0);
				p.sendOverlayMessage(Component.literal("Not during a game").withStyle(ChatFormatting.RED));
				continue;
			}
			if (v == 5 || v == 16) {
				String dim = p.level().dimension().identifier().toString();
				if (!homeAllowed(dim)) why = "Homes can't be set in this dimension.";
				else if (blocksHomes(p.level(), p.blockPosition())) why = "You can't set a home here.";
			} else if (v == 15) {
				CompoundTag back = playerData(server, p.getUUID()).getCompoundOrEmpty("back");
				String dim = back.getStringOr("dimension", "");
				if (!dim.isEmpty() && gameClaimAt(dim, (int) Math.floor(number(back, "x")), (int) Math.floor(number(back, "z"))) != null) why = "The back teleport can't take you back into a game area.";
			}
			if (why == null) continue;
			server.getScoreboard().getOrCreatePlayerScore(p, obj).set(0);
			p.sendSystemMessage(Component.literal("▊ ").withColor(0xFF4A4A).append(Component.literal(why).withStyle(ChatFormatting.WHITE)));
		}
	}

	/** In a game being played right now: a quick round, a wave fight, a dungeon run, or a parkour, race or maze run (not just standing in a game area). */
	public static boolean playingNow(ServerPlayer p) {
		return eu.explorerseden.nicecontrolcenter.games.QuickRuntime.inGame(p) || eu.explorerseden.nicecontrolcenter.games.WaveRuntime.inGame(p)
				|| eu.explorerseden.nicecontrolcenter.games.DungeonRuntime.inRun(p) || eu.explorerseden.nicecontrolcenter.games.GameRuntime.inRun(p);
	}

	/** Nice Actions homes are allowed in this dimension (Tweaks.homes.dimensions; empty = everywhere). */
	public static boolean homeAllowed(String dim) {
		var dims = TweaksControl.settings().homes.dimensions;
		return dims.isEmpty() || dims.contains(dim);
	}

	private static double number(CompoundTag t, String k) {
		var tag = t.get(k);
		return tag == null ? 0 : tag.asDouble().orElse(0.0);
	}

	/** The player's entry in eden:database (Nice Actions): player.<the four UUID ints>. */
	private static CompoundTag playerData(MinecraftServer server, UUID uuid) {
		int[] a = UUIDUtil.uuidToIntArray(uuid);
		CompoundTag db = server.getCommandStorage().get(Identifier.fromNamespaceAndPath("eden", "database"));
		return db.getCompoundOrEmpty("player").getCompoundOrEmpty("" + a[0] + a[1] + a[2] + a[3]);
	}

	// ---------------------------------------------------------------- kit (admins)

	/** The admin's current inventory becomes the claim's kit; returns how many stacks. */
	public static int setKit(ServerPlayer admin, Claim c) {
		List<String> kit = new ArrayList<>();
		var inv = admin.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack st = inv.getItem(i);
			if (!st.isEmpty()) kit.add(encodeSlot(admin, unmark(st.copy()), i));
		}
		if (c.admin == null) c.admin = new Claims.AdminOptions();
		c.admin.kit = kit;
		c.admin.moreKits = new ArrayList<>();
		ClaimStore.changed();
		return kit.size();
	}

	/** The admin's current inventory becomes one more kit of the claim (a random one is handed out); how many kits now. */
	public static int addKit(ServerPlayer admin, Claim c) {
		if (c.admin == null) c.admin = new Claims.AdminOptions();
		List<String> taken = kitFrom(admin);
		if (taken.isEmpty()) return kitCount(c.admin.kit, c.admin.moreKits);
		if (c.admin.kit == null || c.admin.kit.isEmpty()) {
			c.admin.kit = taken;
			ClaimStore.changed();
			return kitCount(c.admin.kit, c.admin.moreKits);
		}
		if (c.admin.moreKits == null) c.admin.moreKits = new ArrayList<>();
		c.admin.moreKits.add(taken);
		ClaimStore.changed();
		return kitCount(c.admin.kit, c.admin.moreKits);
	}

	// ---------------------------------------------------------------- storage

	private static String encode(ServerPlayer p, ItemStack st) {
		return ItemStack.CODEC.encodeStart(p.registryAccess().createSerializationContext(JsonOps.INSTANCE), st).getOrThrow().toString();
	}

	/** A kit item: the stack, plus the inventory slot it was in ("slot"). */
	private static String encodeSlot(ServerPlayer p, ItemStack st, int slot) {
		com.google.gson.JsonObject o = ItemStack.CODEC.encodeStart(p.registryAccess().createSerializationContext(JsonOps.INSTANCE), st).getOrThrow().getAsJsonObject();
		o.addProperty("slot", slot);
		return o.toString();
	}

	/** An item as JSON with the container slot it's in (a chef's supply chest). */
	public static String itemJson(net.minecraft.core.HolderLookup.Provider registries, ItemStack st, int slot) {
		com.google.gson.JsonObject o = ItemStack.CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), st).getOrThrow().getAsJsonObject();
		o.addProperty("slot", slot);
		return o.toString();
	}

	/** The item of {@link #itemJson} (empty when it can't be read). */
	public static ItemStack itemFrom(net.minecraft.core.HolderLookup.Provider registries, String json) {
		try {
			var tree = JsonParser.parseString(json);
			if (tree.isJsonObject() && tree.getAsJsonObject().has("slot")) tree.getAsJsonObject().remove("slot");
			return ItemStack.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), tree).getOrThrow();
		} catch (RuntimeException e) {
			return ItemStack.EMPTY;
		}
	}

	/** The slot of {@link #itemJson} (-1 when none). */
	public static int itemSlot(String json) {
		return slotOf(json);
	}

	/** The kit item's slot, or -1 (older kits, other items). */
	private static int slotOf(String json) {
		try {
			var o = JsonParser.parseString(json).getAsJsonObject();
			return o.has("slot") ? o.get("slot").getAsInt() : -1;
		} catch (RuntimeException e) {
			return -1;
		}
	}

	private static ItemStack decode(ServerPlayer p, String json) {
		try {
			var tree = JsonParser.parseString(json);
			// The kit's slot isn't part of the stack.
			if (tree.isJsonObject() && tree.getAsJsonObject().has("slot")) tree.getAsJsonObject().remove("slot");
			return ItemStack.CODEC.parse(p.registryAccess().createSerializationContext(JsonOps.INSTANCE), tree).getOrThrow();
		} catch (RuntimeException e) {
			NiceControlCenter.LOGGER.warn("Claims: a game-claim item can't be read: {}", e.getMessage());
			return ItemStack.EMPTY;
		}
	}

	private static void load(MinecraftServer server) {
		file = server.getWorldPath(LevelResource.ROOT).resolve("nicecontrolcenter-claim-stash.json");
		adventureFile = server.getWorldPath(LevelResource.ROOT).resolve("nicecontrolcenter-game-adventure.json");
		stashes.clear();
		inside.clear();
		keptOnDeath.clear();
		deathCount.clear();
		entryPoint.clear();
		respawnIn.clear();
		adventure.clear();
		try {
			if (Files.exists(adventureFile)) {
				var json = com.google.gson.JsonParser.parseString(Files.readString(adventureFile));
				if (json.isJsonObject()) {
					Map<String, String> saved = Json.GSON.fromJson(json, new com.google.gson.reflect.TypeToken<Map<String, String>>() { }.getType());
					if (saved != null) adventure.putAll(saved);
				} else if (json.isJsonArray()) {
					// Files from before the game mode choice: everyone in it was put in adventure.
					for (var e : json.getAsJsonArray()) adventure.put(e.getAsString(), "adventure");
				}
			}
		} catch (IOException | RuntimeException e) {
			NiceControlCenter.LOGGER.error("Claims: could not read who plays games in adventure mode ({})", adventureFile, e);
		}
		try {
			if (Files.exists(file)) {
				Map<String, Stash> saved = Json.GSON.fromJson(Files.readString(file), new com.google.gson.reflect.TypeToken<Map<String, Stash>>() { }.getType());
				if (saved != null) stashes.putAll(saved);
			}
		} catch (IOException | RuntimeException e) {
			NiceControlCenter.LOGGER.error("Claims: could not read the game-claim stashes ({}): kept as .broken", file, e);
			try {
				Files.copy(file, file.resolveSibling(file.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
				// Nothing more to do.
			}
		}
	}

	private static void saveAdventure() {
		if (adventureFile == null) return;
		try {
			Path tmp = adventureFile.resolveSibling(adventureFile.getFileName() + ".tmp");
			Files.writeString(tmp, Json.GSON.toJson(adventure), StandardCharsets.UTF_8);
			Files.move(tmp, adventureFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			NiceControlCenter.LOGGER.error("Claims: could not save who plays games in adventure mode", e);
		}
	}

	/** Written at once (never lose what someone put aside). */
	private static void save() {
		if (file == null) return;
		try {
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, Json.GSON.toJson(stashes), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			NiceControlCenter.LOGGER.error("Claims: could not save the game-claim stashes", e);
		}
	}
}
