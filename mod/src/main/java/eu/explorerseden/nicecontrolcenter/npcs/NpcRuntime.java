package eu.explorerseden.nicecontrolcenter.npcs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Random;
import java.util.UUID;

import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.serialization.JsonOps;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraft.sounds.SoundSource;

import eu.explorerseden.nicecontrolcenter.Games;
import eu.explorerseden.nicecontrolcenter.Json;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.Npcs;
import eu.explorerseden.nicecontrolcenter.Npcs.Npc;
import eu.explorerseden.nicecontrolcenter.Pets;
import eu.explorerseden.nicecontrolcenter.Points;
import eu.explorerseden.nicecontrolcenter.games.MobLines;
import eu.explorerseden.nicecontrolcenter.log.ConsoleRunner;
import eu.explorerseden.nicecontrolcenter.mixin.MannequinInvoker;
import eu.explorerseden.nicecontrolcenter.mixin.MobGoalsAccessor;
import eu.explorerseden.nicecontrolcenter.players.PointsStore;
import eu.explorerseden.nicecontrolcenter.players.RewardShops;

/**
 * NPCs (see {@link Npcs}). A standing NPC is a mannequin kept on its spot (like the reward shop NPCs were). A walking
 * NPC and a hostile one are puppets: a mob nobody sees (the driver: NpcDriverHiddenMixin) pathfinds and fights, and the
 * mannequin (the body) copies it every tick, so players see a skinned player walking and fighting. Hits on the body go
 * to the driver; the driver's attacks swing the body's arm. Server thread only.
 */
public final class NpcRuntime {
	public static final String BODY = "ncc_npc_body";
	public static final String DRIVER = "ncc_npc_driver";
	/** Placed NPCs (standing or walking), kept where their settings say. */
	static final String PLACED = "ncc_npc_placed";
	static final String HOSTILE = "ncc_npc_hostile";
	static final String WALKER = "ncc_npc_walker";
	/** The invisible seat a sitting NPC rides (riding is what makes the player model sit). */
	public static final String SEAT = "ncc_npc_seat";
	static final String ID = "ncc_npcid_";
	static final String PAIR = "ncc_npcpair_";
	static final String LOOK = "ncc_npclook_";
	public static final String PERMISSION = "nicecontrolcenter.npcs.admin";

	private static volatile Npcs.Settings settings = new Npcs.Settings().fill();
	private static long modified = -1;
	private static final Random random = new Random();

	/** A driver and its body, found by their shared pair tag. */
	private static final class Puppet {
		Mob driver;
		LivingEntity body;
		long since;
	}

	private static final Map<String, Puppet> puppets = new HashMap<>();

	/** A placed NPC's state: its entities, where it walks to, talking. */
	private static final class Placed {
		LivingEntity body;
		Mob driver;
		int next;
		int dir = 1;
		long waitUntil;
		boolean waiting;
		Vec3 lastPos;
		long lastMoveAt;
		long lineAt;
		long talkUntil;
		/** The checkpoint it last stood at (its spot at first): where it goes back to after a fall. */
		Games.Pos reached;
		int clickLine;
	}

	private static final Map<String, Placed> placed = new HashMap<>();
	/** npc id|player uuid → when they can set it off again (ms). */
	private static final Map<String, Long> cooldown = new HashMap<>();

	private NpcRuntime() {
	}

	public static Npcs.Settings settings() {
		return settings;
	}

	public static java.nio.file.Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(Npcs.FILE);
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((d, access, env) -> commands(d));
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			modified = -1;
			reload();
			migrateShops();
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 100 == 0) {
				reload();
				keep(server);
			}
			sync();
			if (server.getTickCount() % 5 == 0) walk();
			if (server.getTickCount() % 4 == 0) guard();
			if (server.getTickCount() % 20 == 0) talk(server);
			if (server.getTickCount() % 20 == 0) idle(server);
			if (server.getTickCount() % 5 == 0) steps();
		});
		ServerEntityEvents.ENTITY_LOAD.register((e, level) -> {
			String pair = tag(e, PAIR);
			if (pair == null) return;
			Puppet p = puppets.computeIfAbsent(pair, k -> new Puppet());
			if (p.since == 0) p.since = level.getServer().getTickCount();
			if (e instanceof Mob m && e.entityTags().contains(DRIVER)) {
				p.driver = m;
				if (e.entityTags().contains(WALKER)) calm(m);
				// Goals aren't saved: given again whenever it's loaded.
				if (e.entityTags().contains(HOSTILE) && m.getMainHandItem().is(Items.TRIDENT)) ((MobGoalsAccessor) m).nicecontrolcenter$goals().addGoal(1, new TridentThrow(m));
			} else if (e instanceof LivingEntity l && e.entityTags().contains(BODY)) {
				p.body = l;
			}
		});
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!(player instanceof ServerPlayer p) || !entity.entityTags().contains(BODY)) return InteractionResult.PASS;
			if (hand != InteractionHand.MAIN_HAND || entity.entityTags().contains(HOSTILE)) return InteractionResult.SUCCESS;
			Npc n = npcOf(entity);
			if (n == null || !settings.enabled) return InteractionResult.SUCCESS;
			// The cook of a chef round takes the order (nothing else then).
			if (eu.explorerseden.nicecontrolcenter.games.QuickRuntime.serve(p, n.name)) return InteractionResult.SUCCESS;
			if (!ready(n, p)) return InteractionResult.SUCCESS;
			run(p, n, n.onClick);
			// A talking line plays it in say().
			if (!n.clickLines || n.lines.isEmpty()) voice(n, entity, "ambient");
			if (n.clickLines && !n.lines.isEmpty()) {
				Placed st = placed.computeIfAbsent(n.id, k -> new Placed());
				String line = n.lines.get(st.clickLine++ % n.lines.size());
				say(n, st, line, List.of(p));
			}
			if (!n.shop.isBlank()) {
				Points.Shop shop = PointsStore.settings().shop(n.shop);
				if (shop != null && PointsStore.settings().enabled) RewardShops.open(p, shop, null, null);
				else p.sendOverlayMessage(Component.literal("This shop is closed").withStyle(ChatFormatting.GRAY));
			}
			return InteractionResult.SUCCESS;
		});
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!(player instanceof ServerPlayer p) || !entity.entityTags().contains(BODY)) return InteractionResult.PASS;
			if (entity.entityTags().contains(HOSTILE)) {
				Puppet pp = puppets.get(tag(entity, PAIR));
				// The hit lands on the fighter behind the body (weapon, enchantments, crits and knockback as usual).
				if (pp != null && pp.driver != null && pp.driver.isAlive()) p.attack(pp.driver);
				return InteractionResult.FAIL;
			}
			Npc n = npcOf(entity);
			if (n != null && settings.enabled && hurtReady(entity)) voice(n, entity, "hurt");
			if (n != null && settings.enabled && !n.onHit.isEmpty() && ready(n, p)) run(p, n, n.onHit);
			return InteractionResult.FAIL;
		});
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((victim, source, amount) -> {
			// A driver's own hit: the body swings its arm.
			Entity by = source.getDirectEntity();
			if (by != null && by.entityTags().contains(DRIVER)) {
				Puppet pp = puppets.get(tag(by, PAIR));
				if (pp != null && pp.body != null) swingArm(pp.body);
			}
			if (!victim.entityTags().contains(BODY)) return true;
			// Arrows and the like hitting the body hurt the driver; bodies themselves never take damage.
			if (victim.entityTags().contains(HOSTILE) && victim.level() instanceof ServerLevel l) {
				Puppet pp = puppets.get(tag(victim, PAIR));
				if (pp != null && pp.driver != null && pp.driver.isAlive()) pp.driver.hurtServer(l, source, amount);
			}
			return false;
		});
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
			if (!entity.entityTags().contains(DRIVER) || !entity.entityTags().contains(HOSTILE)) return;
			Puppet pp = puppets.get(tag(entity, PAIR));
			if (pp == null || pp.body == null || !(pp.body.level() instanceof ServerLevel l)) return;
			l.broadcastDamageEvent(pp.body, source);
			Npc n = npcOf(pp.body);
			if (n != null) voice(n, pp.body, "hurt");
		});
	}

	// ---------------------------------------------------------------- settings

	/** Reads the file again right now (not up to 5 s late): commands change what the panel saved last, not an older copy. */
	public static void reloadNow() {
		eu.explorerseden.nicecontrolcenter.core.FileWatch.now(file());
		reload();
	}

	public static void reload() {
		long now;
		try {
			now = eu.explorerseden.nicecontrolcenter.core.FileWatch.modified(file());
		} catch (IOException e) {
			return;
		}
		if (now == modified) return;
		modified = now;
		settings = Npcs.read(file());
	}

	public static void save() {
		try {
			Npcs.write(file(), settings);
			modified = eu.explorerseden.nicecontrolcenter.core.FileWatch.modified(file());
		} catch (IOException e) {
			NiceControlCenter.LOGGER.warn("NPCs: could not save the NPCs: {}", e.getMessage());
		}
	}

	/** Reward shop NPCs (before 1.73.0) become ordinary NPCs with their shop, once. */
	private static void migrateShops() {
		if (settings.shopsMigrated) return;
		// The shops' file may not be read yet (its own start-up hook can run after this one).
		PointsStore.reload();
		int moved = 0;
		for (Points.Shop shop : PointsStore.settings().shops) {
			for (Games.Pos at : shop.npcs) {
				Npc n = shopNpc(shop, at);
				settings.npcs.add(n);
				moved++;
			}
			shop.npcs.clear();
		}
		settings.shopsMigrated = true;
		settings.fill();
		save();
		if (moved > 0) {
			PointsStore.saveSettings();
			NiceControlCenter.LOGGER.info("NPCs: {} reward shop NPCs are NPCs now (Community → NPCs), each with its shop", moved);
		}
	}

	/** A new NPC for a reward shop at the spot (named after the shop, numbered when it has more). */
	public static Npc shopNpc(Points.Shop shop, Games.Pos at) {
		Npc n = new Npc();
		n.name = free(shop.name);
		n.title = shop.shown();
		n.skin = shop.texture;
		n.lookAtPlayers = shop.lookAtPlayers;
		n.description = "Spend your " + PointsStore.settings().name + " · right-click";
		n.shop = shop.id;
		n.spot = at;
		return n;
	}

	/** An NPC for a shop where the player stands (from /points shop create and npc add). */
	public static String addShopNpc(ServerPlayer p, Points.Shop shop) {
		reloadNow();
		Npc n = shopNpc(shop, spot(p));
		settings.npcs.add(n);
		settings.fill();
		save();
		keep(p.level().getServer());
		return n.name;
	}

	private static String free(String base) {
		String name = base.replaceAll("[^A-Za-z0-9_-]", "");
		if (name.isEmpty()) name = "npc";
		String out = name;
		for (int i = 2; settings.npc(out) != null; i++) out = name + "-" + i;
		return out;
	}

	// ---------------------------------------------------------------- tags and lookups

	private static String tag(Entity e, String prefix) {
		for (String t : e.entityTags()) if (t.startsWith(prefix)) return t.substring(prefix.length());
		return null;
	}

	private static Npc npcOf(Entity e) {
		String id = tag(e, ID);
		if (id == null) return null;
		for (Npc n : settings.npcs) if (n.id.equals(id)) return n;
		return null;
	}

	/** An NPC body: the claims let anyone talk to it. */
	public static boolean isNpc(Entity e) {
		return e.entityTags().contains(BODY);
	}

	/** What the placed NPC looks like and where: made again when it changes. */
	private static String looks(Npc n) {
		return Integer.toHexString((n.skin + "|" + n.skinValue + "|" + n.nameColor + "|" + n.shown() + "|" + n.description + "|" + n.pose + "|" + n.mainHand + "|" + n.equipment + "|" + n.walks() + "|" + n.sits() + "|" + n.scaleMin + "|" + n.scaleMax + "|"
				+ (n.spot == null ? "" : n.spot.dim + "|" + Math.round(n.spot.x * 2) + "|" + Math.round(n.spot.y * 2) + "|" + Math.round(n.spot.z * 2) + "|" + n.spot.yaw))
				.hashCode());
	}

	/** Where an NPC goes: the player's spot, facing the way they look (to the nearest 45°). */
	static Games.Pos spot(ServerPlayer p) {
		return new Games.Pos(p.level().dimension().identifier().toString(), Math.floor(p.getX()) + 0.5, Math.floor(p.getY()), Math.floor(p.getZ()) + 0.5,
				Math.round(p.getYRot() / 45f) * 45f);
	}

	// ---------------------------------------------------------------- placed NPCs

	/** Every five seconds: placed NPCs where they should be (once their chunk is loaded), outdated ones gone. */
	public static void keep(MinecraftServer server) {
		// Hostiles of an NPC that was removed go too (not when the file just couldn't be read).
		if (settings.readOk) {
			for (Puppet p : puppets.values()) {
				Entity any = p.body != null ? p.body : p.driver;
				if (any == null || any.isRemoved() || !any.entityTags().contains(HOSTILE) || npcOf(any) != null) continue;
				if (p.driver != null) p.driver.discard();
				if (p.body != null) p.body.discard();
			}
		}
		Map<String, LivingEntity> bodies = new HashMap<>();
		Map<String, Mob> drivers = new HashMap<>();
		Map<String, Entity> seats = new HashMap<>();
		for (ServerLevel level : server.getAllLevels()) {
			for (Entity e : level.getAllEntities()) {
				if (e == null || e.isRemoved() || !e.entityTags().contains(PLACED)) continue;
				Npc n = npcOf(e);
				boolean body = e.entityTags().contains(BODY);
				boolean seat = e.entityTags().contains(SEAT);
				boolean ok = settings.enabled && n != null && n.spot != null && e.entityTags().contains(LOOK + looks(n));
				if (ok && seat) ok = n.sits() && !seats.containsKey(n.id);
				else if (ok && body) ok = e instanceof LivingEntity && !bodies.containsKey(n.id);
				else if (ok) ok = e instanceof Mob && n.walks() && !drivers.containsKey(n.id);
				if (!ok) {
					e.discard();
					continue;
				}
				if (seat) seats.put(n.id, e);
				else if (body) bodies.put(n.id, (LivingEntity) e);
				else drivers.put(n.id, (Mob) e);
			}
		}
		placed.keySet().removeIf(id -> settings.npc(id) == null);
		uploadSkins(server);
		if (!settings.enabled) return;
		for (Npc n : settings.npcs) {
			if (n.spot == null) continue;
			LivingEntity b = bodies.get(n.id);
			Mob d = drivers.get(n.id);
			// A walker needs both halves.
			if (n.walks() && (b == null) != (d == null)) {
				if (b != null) b.discard();
				if (d != null) d.discard();
				b = null;
				d = null;
			}
			// A sitting one needs its seat, and to be on it.
			Entity seat = seats.get(n.id);
			if (n.sits() && b != null && (seat == null || b.getVehicle() != seat)) {
				b.discard();
				if (seat != null) seat.discard();
				b = null;
			}
			Placed st = placed.computeIfAbsent(n.id, k -> new Placed());
			st.body = b;
			st.driver = d;
			if (b != null) continue;
			ServerLevel level = eu.explorerseden.nicecontrolcenter.games.GameRuntime.levelOf(server, n.spot.dim);
			if (level == null || !level.isPositionEntityTicking(BlockPos.containing(n.spot.x, n.spot.y, n.spot.z))) continue;
			summonPlaced(level, n, st);
		}
	}

	/** NPC ids whose picture link is being uploaded. */
	private static final java.util.Set<String> uploading = new java.util.HashSet<>();

	/** Picture links not uploaded yet go through MineSkin (once per link; a failed one waits for a new link or key). */
	private static void uploadSkins(MinecraftServer server) {
		for (Npc n : settings.npcs) {
			if (!Npcs.pictureLink(n.skin) || n.skin.equals(n.skinSource) || uploading.contains(n.id)) continue;
			String id = n.id, link = n.skin, key = settings.mineskinKey;
			if (key.isBlank()) {
				if (!n.skinError.startsWith("Set a MineSkin")) {
					n.skinError = "Set a MineSkin API key (NPCs page) to use picture links.";
					save();
				}
				continue;
			}
			if (n.skinError.endsWith("[" + key.hashCode() + "|" + link.hashCode() + "]")) continue;
			uploading.add(id);
			NiceControlCenter.LOGGER.info("NPCs: uploading the skin of {} through MineSkin", n.name);
			MineSkin.upload(link, key).whenComplete((value, error) -> server.execute(() -> {
				uploading.remove(id);
				reloadNow();
				Npc now = settings.npc(id);
				if (now == null || !link.equals(now.skin)) return;
				if (error == null) {
					now.skinValue = value;
					now.skinSource = link;
					now.skinError = "";
					NiceControlCenter.LOGGER.info("NPCs: skin of {} uploaded", now.name);
				} else {
					String why = error.getCause() != null ? error.getCause().getMessage() : error.getMessage();
					// Remembers what failed, so it isn't tried again until the link or the key changes.
					now.skinError = "MineSkin: " + why + " [" + key.hashCode() + "|" + link.hashCode() + "]";
					NiceControlCenter.LOGGER.warn("NPCs: the skin of {} could not be uploaded: {}", now.name, why);
				}
				save();
				keep(server);
			}));
		}
	}

	private static void summonPlaced(ServerLevel level, Npc n, Placed st) {
		String pair = UUID.randomUUID().toString().substring(0, 8);
		List<String> tags = new ArrayList<>(List.of(BODY, PLACED, ID + n.id, LOOK + looks(n), MobLines.NMM_EXCLUDE));
		if (n.walks()) tags.add(PAIR + pair);
		LivingEntity body = body(level, n, n.spot.x, n.spot.y, n.spot.z, n.spot.yaw, tags, !n.walks(), false);
		if (body == null) return;
		Mob driver = null;
		if (n.walks()) {
			driver = driver(level, n, "minecraft:husk", n.spot.x, n.spot.y, n.spot.z, n.spot.yaw,
					List.of(DRIVER, PLACED, WALKER, ID + n.id, LOOK + looks(n), PAIR + pair, MobLines.NMM_EXCLUDE), true, "");
			if (driver == null) return;
			set(driver, Attributes.MOVEMENT_SPEED, n.walkSpeed);
			// Pathfinding gives up beyond the follow range (35 for a husk): checkpoints can be far apart.
			set(driver, Attributes.FOLLOW_RANGE, 128);
		}
		size(n, body, driver);
		level.addFreshEntity(body);
		if (driver != null) level.addFreshEntity(driver);
		if (n.sits()) {
			// Riding puts the hips at the seat: the seat goes where the NPC would stand (e.g. on top of a stair).
			Entity seat = load(level, "minecraft:item_display", "{Tags:[\"" + SEAT + "\",\"" + PLACED + "\",\"" + ID + n.id + "\",\"" + LOOK + looks(n) + "\",\""
					+ MobLines.NMM_EXCLUDE + "\"]}");
			if (seat != null) {
				seat.snapTo(n.spot.x, n.spot.y, n.spot.z, n.spot.yaw, 0);
				level.addFreshEntity(seat);
				body.startRiding(seat, true, false);
			}
		}
		st.body = body;
		st.driver = driver;
		st.next = 0;
		st.dir = 1;
		st.waiting = false;
		st.lastPos = null;
	}

	/** Standing NPCs stay put and unharmed, turning their head to the nearest player close by (like the shop NPCs did). */
	private static void guard() {
		for (Map.Entry<String, Placed> en : placed.entrySet()) {
			Placed st = en.getValue();
			Npc n = settings.npc(en.getKey());
			if (n == null || st.body == null || st.body.isRemoved() || !(st.body.level() instanceof ServerLevel level)) continue;
			LivingEntity e = st.body;
			e.clearFire();
			if (!e.getActiveEffects().isEmpty()) e.removeAllEffects();
			if (e.getHealth() < e.getMaxHealth()) e.setHealth(e.getMaxHealth());
			boolean seated = e.getVehicle() != null && e.getVehicle().entityTags().contains(SEAT);
			if (e.isPassenger() && !seated) e.stopRiding();
			if (e.isVehicle()) e.ejectPassengers();
			if (seated) {
				e.setYBodyRot(n.spot == null ? e.getYRot() : n.spot.yaw);
				if (n.lookAtPlayers) look(level, e);
				continue;
			}
			if (n.walks()) {
				// Waiting at a checkpoint: it may look at players near.
				if (st.waiting && n.lookAtPlayers && st.driver != null) look(level, st.driver);
				continue;
			}
			if (n.spot != null && e.distanceToSqr(n.spot.x, n.spot.y, n.spot.z) > 0.01) {
				e.teleportTo(n.spot.x, n.spot.y, n.spot.z);
				e.setDeltaMovement(0, 0, 0);
				e.setYRot(n.spot.yaw);
			}
			e.setYBodyRot(n.spot == null ? e.getYRot() : n.spot.yaw);
			if (n.lookAtPlayers) look(level, e);
		}
	}

	private static void look(ServerLevel level, LivingEntity e) {
		var p = level.getNearestPlayer(e, 8);
		if (p == null) return;
		double dx = p.getX() - e.getX(), dz = p.getZ() - e.getZ(), dy = p.getEyeY() - e.getEyeY();
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		e.setYHeadRot(yaw);
		e.setXRot(Math.max(-40, Math.min(40, pitch)));
	}

	/** Walkers: on to the next checkpoint, a wait there; back on the path when they fell or got stuck. */
	private static void walk() {
		long now = System.currentTimeMillis();
		for (Map.Entry<String, Placed> en : placed.entrySet()) {
			Placed st = en.getValue();
			Npc n = settings.npc(en.getKey());
			Mob d = st.driver;
			if (n == null || !n.walks() || d == null || d.isRemoved()) continue;
			if (st.next < 0 || st.next >= n.path.size()) st.next = 0;
			Npcs.Checkpoint c = n.path.get(st.next);
			Games.Pos back = st.reached != null && st.reached.dim.equals(c.pos.dim) ? st.reached : n.spot;
			// Fell (or in lava): back where it last was on the path.
			double lowest = n.spot.y;
			for (Npcs.Checkpoint k : n.path) lowest = Math.min(lowest, k.pos.y);
			if (d.getY() < lowest - 6 || d.isInLava()) {
				rescue(st, back);
				continue;
			}
			if (st.waiting) {
				if (now < st.waitUntil) continue;
				st.waiting = false;
				advance(n, st);
				continue;
			}
			double dx = d.getX() - c.pos.x, dz = d.getZ() - c.pos.z;
			// Close enough: on it, or as close as its way goes when the spot itself can't be stood on (a fence, a slab
			// edge, a narrow gap; vanilla navigation then ends a block or two short and calls it done).
			double h2 = dx * dx + dz * dz;
			boolean close = h2 < 2.25 && Math.abs(d.getY() - c.pos.y) < 2
					|| d.getNavigation().isDone() && h2 < 9 && Math.abs(d.getY() - c.pos.y) < 3;
			if (close) {
				d.getNavigation().stop();
				st.reached = c.pos;
				st.waiting = true;
				st.waitUntil = now + c.waitSeconds * 1000L;
				d.setYRot(c.pos.yaw);
				d.setYHeadRot(c.pos.yaw);
				d.setYBodyRot(c.pos.yaw);
				continue;
			}
			Vec3 pos = d.position();
			if (st.lastPos == null || pos.distanceToSqr(st.lastPos) > 0.25) {
				st.lastPos = pos;
				st.lastMoveAt = now;
			}
			long still = now - st.lastMoveAt;
			if (d.getNavigation().isDone() || still > 5_000 && still % 5_000 < 300) {
				d.getNavigation().setMaxVisitedNodesMultiplier(still > 5_000 ? 4f : 2f);
				// Far checkpoints in steps of up to 12 blocks (vanilla pathfinding stops short on long ways), each step on
				// ground it can stand on; standing still for a while: the checkpoint itself, any way that gets within 2.
				double far = Math.sqrt(h2);
				double k = still > 5_000 ? 1 : Math.min(1, 12 / Math.max(1, far));
				double tx = d.getX() - dx * k, tz = d.getZ() - dz * k;
				double ty = k >= 1 ? c.pos.y : ground(d.level(), tx, d.getY() + (c.pos.y - d.getY()) * k, tz);
				boolean going = d.getNavigation().moveTo(tx, ty, tz, k >= 1 && still > 5_000 ? 2 : 1, 1.0);
				if (!going && k < 1) d.getNavigation().moveTo(c.pos.x, c.pos.y, c.pos.z, 2, 1.0);
			}
			if (still > 15_000) {
				// Truly stuck: put on the checkpoint it walks to.
				rescue(st, c.pos);
			}
		}
	}

	/** A height near y at x, z where a mob can stand (solid below, room for its head); y itself when there's none. */
	private static double ground(net.minecraft.world.level.Level level, double x, double y, double z) {
		int bx = (int) Math.floor(x), bz = (int) Math.floor(z), by = (int) Math.floor(y);
		// Nearest first: y, y+1, y-1, y+2, y-2 … ±4.
		for (int off : new int[] { 0, 1, -1, 2, -2, 3, -3, 4, -4 }) {
			BlockPos feet = new BlockPos(bx, by + off, bz);
			if (level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
					&& !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()) return feet.getY();
		}
		return y;
	}

	private static void advance(Npc n, Placed st) {
		int size = n.path.size();
		if (size == 1) return;
		if ("random".equals(n.pathMode)) {
			// Any of the others: never the one it stands at.
			int pick = random.nextInt(size - 1);
			st.next = pick >= st.next ? pick + 1 : pick;
			return;
		}
		if ("back".equals(n.pathMode)) {
			if (st.next + st.dir >= size || st.next + st.dir < 0) st.dir = -st.dir;
			st.next += st.dir;
		} else {
			st.next = (st.next + 1) % size;
		}
	}

	private static void rescue(Placed st, Games.Pos to) {
		st.driver.getNavigation().stop();
		st.driver.teleportTo(to.x, to.y, to.z);
		st.driver.setDeltaMovement(0, 0, 0);
		st.driver.resetFallDistance();
		st.lastPos = null;
		st.lastMoveAt = System.currentTimeMillis();
	}

	/** Walkers' drivers do nothing on their own (no attacking, wandering, or burning): the path is all they do. */
	private static void calm(Mob m) {
		((MobGoalsAccessor) m).nicecontrolcenter$goals().removeAllGoals(g -> true);
		((MobGoalsAccessor) m).nicecontrolcenter$targets().removeAllGoals(g -> true);
		m.setTarget(null);
	}

	// ---------------------------------------------------------------- puppets

	/** Every tick: each body where its driver is, turned the same way, drawing a bow when it does. */
	private static void sync() {
		for (Iterator<Map.Entry<String, Puppet>> it = puppets.entrySet().iterator(); it.hasNext();) {
			Puppet p = it.next().getValue();
			Mob d = p.driver;
			LivingEntity b = p.body;
			if (d != null && (d.isRemoved() || d.isDeadOrDying())) {
				Entity.RemovalReason why = d.getRemovalReason();
				if (b != null && !b.isRemoved() && (d.isDeadOrDying() || why != null && why.shouldDestroy())) {
					Npc dying = npcOf(b);
					if (dying != null && d.isDeadOrDying()) voice(dying, b, "death");
					if (b.level() instanceof ServerLevel l) l.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF, b.getX(), b.getY() + 1, b.getZ(), 12, 0.3, 0.6, 0.3, 0.02);
					b.discard();
				}
				it.remove();
				continue;
			}
			if (b != null && b.isRemoved()) {
				Entity.RemovalReason why = b.getRemovalReason();
				if (d != null && why != null && why.shouldDestroy()) d.discard();
				it.remove();
				continue;
			}
			if (d == null || b == null) {
				// Half of it never turned up (a crash between the two): the other half goes after a while.
				MinecraftServer server = (d != null ? d : b) == null ? null : (d != null ? d : b).level().getServer();
				if (server != null && server.getTickCount() - p.since > 200) {
					if (d != null) d.discard();
					if (b != null) b.discard();
					it.remove();
				}
				continue;
			}
			if (b.level() != d.level()) {
				b.discard();
				d.discard();
				it.remove();
				continue;
			}
			if (d.isPassenger()) d.stopRiding();
			if (b.isPassenger()) b.stopRiding();
			// Placed by hand every tick: no physics, so it never pushes its driver back.
			b.noPhysics = true;
			b.setPos(d.getX(), d.getY(), d.getZ());
			b.setYRot(d.getYRot());
			b.setXRot(d.getXRot());
			b.setYHeadRot(d.getYHeadRot());
			b.setYBodyRot(d.yBodyRot);
			b.setDeltaMovement(Vec3.ZERO);
			b.clearFire();
			if (b.isCustomNameVisible() && b.entityTags().contains(HOSTILE)) {
				b.setCustomNameVisible(false);
				b.setCustomName(null);
			}
			// Drawing, aiming, raising, charging (a spear's charge hits nothing on the body: KineticNoDamageMixin).
			if (d.isUsingItem() && !b.isUsingItem()) b.startUsingItem(d.getUsedItemHand());
			else if (!d.isUsingItem() && b.isUsingItem()) b.stopUsingItem();
		}
	}


	/** A trident NPC throws it at players 4–20 blocks away it can see, every 2 seconds (up close it fights). */
	private static final class TridentThrow extends net.minecraft.world.entity.ai.goal.Goal {
		private final Mob mob;
		private int next;

		TridentThrow(Mob mob) {
			this.mob = mob;
		}

		@Override
		public boolean canUse() {
			LivingEntity t = mob.getTarget();
			if (t == null || !t.isAlive() || mob.tickCount < next) return false;
			double d = mob.distanceToSqr(t);
			return d > 16 && d < 400 && mob.getSensing().hasLineOfSight(t);
		}

		@Override
		public boolean canContinueToUse() {
			return false;
		}

		@Override
		public void start() {
			LivingEntity t = mob.getTarget();
			if (t == null || !(mob.level() instanceof ServerLevel level)) return;
			next = mob.tickCount + 40;
			var trident = new net.minecraft.world.entity.projectile.arrow.ThrownTrident(level, mob, new ItemStack(Items.TRIDENT));
			trident.pickup = net.minecraft.world.entity.projectile.arrow.AbstractArrow.Pickup.DISALLOWED;
			double dx = t.getX() - mob.getX(), dy = t.getY(0.3333) - trident.getY(), dz = t.getZ() - mob.getZ();
			double flat = Math.sqrt(dx * dx + dz * dz);
			trident.shoot(dx, dy + flat * 0.2, dz, 1.6f, 4f);
			level.addFreshEntity(trident);
			level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), net.minecraft.sounds.SoundEvents.DROWNED_SHOOT, mob.getSoundSource(), 1f, 1f);
			Puppet p = puppets.get(tag(mob, PAIR));
			if (p != null && p.body != null) swingArm(p.body);
		}
	}

	/**
	 * The body swings its arm, the way each game version does it (26.1: swing(hand); 26.3 changed it to a swing
	 * animation packet, and the old animate packet's 0 now means "wake up", which drops players for a mannequin).
	 */
	private static void swingArm(LivingEntity body) {
		try {
			for (java.lang.reflect.Method m : LivingEntity.class.getMethods()) {
				if (!m.getName().equals("swing") || m.getParameterCount() == 0 || m.getParameterTypes()[0] != InteractionHand.class) continue;
				if (m.getParameterCount() == 1) {
					m.invoke(body, InteractionHand.MAIN_HAND);
					return;
				}
				if (m.getParameterCount() == 3 && m.getParameterTypes()[2] == boolean.class) {
					Object anim = m.getParameterTypes()[1].getField("DEFAULT").get(null);
					m.invoke(body, InteractionHand.MAIN_HAND, anim, true);
					return;
				}
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			// No swing then.
		}
	}

	/**
	 * A hostile NPC for a mob pool line "npc:<name>" (waves, dungeon markers, mazes): the fighter carries the caller's
	 * tags (so the game counts, scales and clears it like any of its mobs), the body only its own.
	 */
	public static boolean spawnHostile(MinecraftServer server, String dim, double x, double y, double z, String name, List<String> extraTags) {
		Npc n = settings.npc(name);
		ServerLevel level = eu.explorerseden.nicecontrolcenter.games.GameRuntime.levelOf(server, dim);
		if (n == null || level == null) {
			NiceControlCenter.LOGGER.warn("NPCs: no NPC called {} for a mob pool (Community → NPCs)", name);
			return false;
		}
		ItemStack hand = item(server, n, 0);
		// A trident: a husk that throws it (a drowned only goes after targets in water or at night).
		String type = hand.is(Items.BOW) ? "minecraft:skeleton" : hand.is(Items.CROSSBOW) ? "minecraft:pillager" : "minecraft:husk";
		String pair = UUID.randomUUID().toString().substring(0, 8);
		float yaw = random.nextFloat() * 360;
		List<String> dTags = new ArrayList<>(List.of(DRIVER, HOSTILE, ID + n.id, PAIR + pair, MobLines.NMM_EXCLUDE));
		dTags.addAll(extraTags);
		Mob d = driver(level, n, type, x, y, z, yaw, dTags, false, n.lootTable);
		LivingEntity b = body(level, n, x, y, z, yaw, List.of(BODY, HOSTILE, ID + n.id, PAIR + pair, MobLines.NMM_EXCLUDE), false, true);
		if (d == null || b == null) return false;
		set(d, Attributes.MAX_HEALTH, n.hostile.health);
		set(d, Attributes.ATTACK_DAMAGE, n.hostile.damage);
		set(d, Attributes.MOVEMENT_SPEED, n.hostile.speed);
		set(d, Attributes.ARMOR, n.hostile.armor);
		set(d, Attributes.FOLLOW_RANGE, n.hostile.followRange);
		d.setHealth(d.getMaxHealth());
		// Sun-sensitive fighters keep a helmet nobody sees.
		if (d.getItemBySlot(EquipmentSlot.HEAD).isEmpty() && !type.equals("minecraft:pillager")) d.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
		d.setDropChance(EquipmentSlot.HEAD, 0);
		size(n, b, d);
		level.addFreshEntity(d);
		level.addFreshEntity(b);
		return true;
	}

	/** A random size between the NPC's smallest and largest, the same for its body and its driver (so hits fit). */
	private static void size(Npc n, LivingEntity body, LivingEntity driver) {
		double scale = n.scaleMin + random.nextDouble() * (n.scaleMax - n.scaleMin);
		if (Math.abs(scale - 1) < 0.001) return;
		set(body, Attributes.SCALE, scale);
		if (driver != null) set(driver, Attributes.SCALE, scale);
	}

	private static void set(LivingEntity e, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr, double value) {
		AttributeInstance a = e.getAttribute(attr);
		if (a != null) a.setBaseValue(value);
	}

	// ---------------------------------------------------------------- making the entities

	/** The mannequin: skin, name, pose, hand, equipment. */
	private static LivingEntity body(ServerLevel level, Npc n, double x, double y, double z, float yaw, List<String> tags, boolean invulnerable, boolean hostile) {
		StringBuilder nbt = new StringBuilder("{Tags:[");
		for (int i = 0; i < tags.size(); i++) nbt.append(i == 0 ? "" : ",").append(Json.GSON.toJson(tags.get(i)));
		nbt.append("],immovable:1b,NoGravity:1b,Silent:1b,PersistenceRequired:1b");
		if (invulnerable) nbt.append(",Invulnerable:1b");
		// Hostiles have no name tag: it shows through walls.
		if (!n.title.isBlank() && !hostile) {
			nbt.append(",CustomNameVisible:1b,CustomName:");
			if (n.nameColor.isEmpty()) nbt.append(Json.GSON.toJson(n.shown()));
			else nbt.append("{text:").append(Json.GSON.toJson(n.shown())).append(",color:\"").append(n.nameColor.toLowerCase(Locale.ROOT)).append("\"}");
		}
		if (!n.description.isBlank() && !hostile) nbt.append(",description:").append(Json.GSON.toJson(n.description));
		else nbt.append(",hide_description:1b");
		nbt.append(",pose:\"").append(n.pose.equals("sitting") ? "standing" : n.pose).append("\",main_hand:\"").append(n.mainHand).append("\"");
		String profile = profile(n);
		if (profile != null) nbt.append(",profile:").append(profile);
		nbt.append("}");
		LivingEntity e = (LivingEntity) load(level, "minecraft:mannequin", nbt.toString());
		if (e == null) return null;
		e.snapTo(x, y, z, yaw, 0);
		e.setYHeadRot(yaw);
		e.setYBodyRot(yaw);
		equip(level.getServer(), n, e);
		return e;
	}

	/** The hidden mob behind a walking or fighting NPC. */
	private static Mob driver(ServerLevel level, Npc n, String type, double x, double y, double z, float yaw, List<String> tags, boolean invulnerable, String loot) {
		StringBuilder nbt = new StringBuilder("{Tags:[");
		for (int i = 0; i < tags.size(); i++) nbt.append(i == 0 ? "" : ",").append(Json.GSON.toJson(tags.get(i)));
		nbt.append("],Silent:1b,PersistenceRequired:1b,CanPickUpLoot:0b,DeathLootTable:").append(Json.GSON.toJson(loot == null || loot.isBlank() ? "minecraft:empty"
				: loot.contains(":") ? loot : "minecraft:" + loot));
		if (invulnerable) nbt.append(",Invulnerable:1b");
		nbt.append("}");
		if (!(load(level, type, nbt.toString()) instanceof Mob m)) return null;
		m.snapTo(x, y, z, yaw, 0);
		m.setYHeadRot(yaw);
		equip(level.getServer(), n, m);
		for (EquipmentSlot s : EquipmentSlot.values()) m.setDropChance(s, 0);
		return m;
	}

	private static Entity load(ServerLevel level, String type, String snbt) {
		try {
			EntityType<?> t = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(type));
			CompoundTag tag = TagParser.parseCompoundFully(snbt);
			return EntityType.loadEntityRecursive(t, tag, level, EntitySpawnReason.COMMAND, EntityProcessor.NOP);
		} catch (Exception e) {
			NiceControlCenter.LOGGER.warn("NPCs: could not make a {}: {}", type, e.toString());
			return null;
		}
	}

	/** The mannequin's profile: a picture link's uploaded skin, a player name (their skin), or a texture value. */
	private static String profile(Npc n) {
		if (Npcs.pictureLink(n.skin)) {
			return !n.skinValue.isBlank() && n.skin.equals(n.skinSource) ? "{properties:[{name:\"textures\",value:\"" + n.skinValue + "\"}]}" : null;
		}
		return profile(n.skin);
	}

	private static String profile(String skin) {
		if (skin == null || skin.isBlank()) return null;
		String texture = Pets.texture(skin);
		if (texture != null) return "{properties:[{name:\"textures\",value:\"" + texture + "\"}]}";
		if (skin.matches("[A-Za-z0-9_]{3,16}")) return "{name:\"" + skin + "\"}";
		return null;
	}

	private static void equip(MinecraftServer server, Npc n, LivingEntity e) {
		for (EquipmentSlot s : List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
			ItemStack st = item(server, n, slotNumber(s));
			if (!st.isEmpty()) e.setItemSlot(s, st);
		}
	}

	/** Kit slot numbers: 0 main hand, 40 off hand, 36 feet … 39 head. */
	private static int slotNumber(EquipmentSlot s) {
		return switch (s) {
			case MAINHAND -> 0;
			case OFFHAND -> 40;
			case FEET -> 36;
			case LEGS -> 37;
			case CHEST -> 38;
			case HEAD -> 39;
			default -> -1;
		};
	}

	private static ItemStack item(MinecraftServer server, Npc n, int slot) {
		for (String json : n.equipment) {
			try {
				var o = JsonParser.parseString(json).getAsJsonObject();
				if (!o.has("slot") || o.get("slot").getAsInt() != slot) continue;
				o.remove("slot");
				return ItemStack.CODEC.parse(server.registryAccess().createSerializationContext(JsonOps.INSTANCE), o).getOrThrow();
			} catch (RuntimeException e) {
				// Unreadable (its mod gone): left out.
			}
		}
		return ItemStack.EMPTY;
	}

	private static String encode(ServerPlayer p, ItemStack st, int slot) {
		var o = ItemStack.CODEC.encodeStart(p.registryAccess().createSerializationContext(JsonOps.INSTANCE), st).getOrThrow().getAsJsonObject();
		o.addProperty("slot", slot);
		return o.toString();
	}

	private static List<String> equipmentOf(ServerPlayer p, LivingEntity from) {
		List<String> out = new ArrayList<>();
		for (EquipmentSlot s : List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
			ItemStack st = from.getItemBySlot(s);
			if (!st.isEmpty()) out.add(encode(p, st.copy(), slotNumber(s)));
		}
		return out;
	}

	// ---------------------------------------------------------------- actions and talking

	private static boolean ready(Npc n, ServerPlayer p) {
		String key = n.id + "|" + p.getStringUUID();
		long now = System.currentTimeMillis();
		if (cooldown.getOrDefault(key, 0L) > now) return false;
		cooldown.put(key, now + Math.max(250, n.cooldownSeconds * 1000L));
		if (cooldown.size() > 5000) cooldown.values().removeIf(t -> t < now);
		return true;
	}

	private static void run(ServerPlayer p, Npc n, List<Npcs.Action> actions) {
		MinecraftServer server = p.level().getServer();
		for (Npcs.Action a : actions) {
			String text = a.text.replace("{player}", p.getGameProfile().name()).replace("{uuid}", p.getStringUUID()).replace("{npc}", n.shown());
			if ("message".equals(a.type)) {
				p.sendSystemMessage(n.title.isBlank() ? Component.literal(text) : chatName(n).append(Component.literal(text).withStyle(ChatFormatting.WHITE)));
				continue;
			}
			String cmd = text.startsWith("/") ? text.substring(1) : text;
			if ("player".equals(a.as)) server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), cmd);
			else ConsoleRunner.quiet(server, cmd);
		}
	}

	// ---------------------------------------------------------------- voice (a mob's sounds)

	/** Mobs that use another one's sounds. */
	private static final Map<String, String> SOUNDS_OF = Map.of("mooshroom", "cow", "trader_llama", "llama", "giant", "zombie");
	private static final Map<Entity, Vec3> lastStep = new java.util.WeakHashMap<>();
	private static final Map<Entity, Long> lastHurt = new java.util.WeakHashMap<>();

	/** The NPC's voice mob's sound for the event (ambient, hurt, death, step) at the entity; nothing without one. */
	static void voice(Npc n, Entity at, String event) {
		if (n.voice.isEmpty() || !(at.level() instanceof ServerLevel level)) return;
		Identifier type = Identifier.tryParse(n.voice);
		if (type == null) return;
		String path = SOUNDS_OF.getOrDefault(type.getPath(), type.getPath());
		Identifier id = Identifier.tryParse(type.getNamespace() + ":entity." + path + "." + event);
		var sound = id == null ? java.util.Optional.<net.minecraft.sounds.SoundEvent>empty() : BuiltInRegistries.SOUND_EVENT.getOptional(id);
		if (sound.isEmpty()) return;
		float pitch = (float) (n.voicePitch * (1 + (random.nextFloat() - random.nextFloat()) * 0.2));
		float volume = (float) (n.voiceVolume * (event.equals("step") ? 0.15 : 1));
		boolean hostile = at.entityTags().contains(HOSTILE);
		level.playSound(null, at.getX(), at.getY(), at.getZ(), sound.get(), hostile ? SoundSource.HOSTILE : SoundSource.NEUTRAL, volume, pitch);
	}

	/** Hits on a placed NPC sound at most every 0.4 s (like a mob that just got hurt). */
	private static boolean hurtReady(Entity e) {
		long now = System.currentTimeMillis();
		if (lastHurt.getOrDefault(e, 0L) > now - 400) return false;
		lastHurt.put(e, now);
		return true;
	}

	/** Now and then (about every 8 s) the idle sound, with players close by. */
	private static void idle(MinecraftServer server) {
		if (!settings.enabled) return;
		Set<LivingEntity> bodies = new java.util.HashSet<>();
		for (Placed st : placed.values()) if (st.body != null && !st.body.isRemoved()) bodies.add(st.body);
		for (Puppet p : puppets.values()) if (p.body != null && !p.body.isRemoved()) bodies.add(p.body);
		for (LivingEntity b : bodies) {
			if (random.nextInt(8) != 0) continue;
			Npc n = npcOf(b);
			if (n == null || n.voice.isEmpty()) continue;
			if (b.level().getNearestPlayer(b, 16) == null) continue;
			voice(n, b, "ambient");
		}
	}

	/** Walking and fighting NPCs: a step sound every couple of blocks. */
	private static void steps() {
		for (Puppet p : puppets.values()) {
			LivingEntity b = p.body;
			if (b == null || b.isRemoved() || p.driver == null || !p.driver.onGround()) continue;
			Npc n = npcOf(b);
			if (n == null || n.voice.isEmpty()) continue;
			Vec3 was = lastStep.get(b), now = b.position();
			if (was == null || was.distanceToSqr(now) > 100) {
				lastStep.put(b, now);
				continue;
			}
			double dx = now.x - was.x, dz = now.z - was.z;
			if (dx * dx + dz * dz < 1.6 * 1.6) continue;
			lastStep.put(b, now);
			voice(n, b, "step");
		}
	}

	/** Now and then a line to the players close by; the line under the name goes back afterwards. */
	private static void talk(MinecraftServer server) {
		long now = System.currentTimeMillis();
		for (Map.Entry<String, Placed> en : placed.entrySet()) {
			Placed st = en.getValue();
			Npc n = settings.npc(en.getKey());
			if (n == null || st.body == null || st.body.isRemoved()) continue;
			if (st.talkUntil > 0 && now > st.talkUntil) {
				st.talkUntil = 0;
				describe(st.body, n.description);
			}
			if (n.lines.isEmpty() || n.clickLines || now < st.lineAt) continue;
			List<ServerPlayer> near = new ArrayList<>();
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (p.level() == st.body.level() && p.distanceToSqr(st.body) <= n.lineRadius * n.lineRadius) near.add(p);
			}
			if (near.isEmpty()) continue;
			st.lineAt = now + n.lineEverySeconds * 1000L + random.nextInt(Math.max(1, n.lineEverySeconds * 300));
			say(n, st, n.lines.get(random.nextInt(n.lines.size())), near);
		}
	}

	/** "Name: " in the NPC's colour (gold without one). */
	private static net.minecraft.network.chat.MutableComponent chatName(Npc n) {
		var name = Component.literal(n.shown() + ": ");
		return n.nameColor.isEmpty() ? name.withStyle(ChatFormatting.GOLD) : name.withStyle(st -> st.withColor(Integer.parseInt(n.nameColor.substring(1), 16)));
	}

	private static void say(Npc n, Placed st, String line, List<ServerPlayer> to) {
		for (ServerPlayer p : to) p.sendSystemMessage(chatName(n).append(Component.literal(line).withStyle(ChatFormatting.WHITE)));
		if (st.body != null && !st.body.isRemoved()) {
			voice(n, st.body, "ambient");
			describe(st.body, line);
			st.talkUntil = System.currentTimeMillis() + 5_000;
		}
	}

	private static void describe(LivingEntity body, String text) {
		if (!(body instanceof Mannequin m)) return;
		MannequinInvoker inv = (MannequinInvoker) m;
		if (text == null || text.isBlank()) {
			inv.nicecontrolcenter$setHideDescription(true);
		} else {
			inv.nicecontrolcenter$setDescription(Component.literal(text));
			inv.nicecontrolcenter$setHideDescription(false);
		}
	}

	// ---------------------------------------------------------------- commands

	private static void commands(CommandDispatcher<CommandSourceStack> d) {
		var admin = Permissions.require(PERMISSION, PermissionLevel.GAMEMASTERS);
		var npcArg = (java.util.function.Supplier<com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>>) () -> Commands.argument("npc", StringArgumentType.word())
				.suggests((ctx, b) -> SharedSuggestionProvider.suggest(settings.npcs.stream().map(x -> x.name), b));
		d.register(Commands.literal("npc").requires(src -> {
			reloadNow();
			return admin.test(src);
		})
				.then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					String name = StringArgumentType.getString(ctx, "name");
					if (!name.matches("[A-Za-z0-9_-]{1,32}")) return fail(ctx, "One word: letters, digits, - and _.");
					if (settings.npc(name) != null) return fail(ctx, "There's an NPC called " + name + " already.");
					Npc n = new Npc();
					n.name = name;
					n.title = name;
					n.spot = spot(p);
					settings.npcs.add(n);
					return saved(ctx, "Made the NPC " + name + " here. Skin, pose, actions and lines: Community → NPCs in the panel, or /npc skin|pose|equip|path|shop.");
				})))
				.then(Commands.literal("adopt").then(Commands.argument("name", StringArgumentType.word()).executes(NpcRuntime::adopt)))
				.then(Commands.literal("move").then(npcArg.get().executes(ctx -> {
					Npc n = npc(ctx);
					if (n == null) return fail(ctx, "There's no such NPC.");
					n.spot = spot(ctx.getSource().getPlayerOrException());
					return saved(ctx, n.name + " stands here now.");
				})))
				.then(Commands.literal("remove").then(npcArg.get().executes(ctx -> {
					Npc n = npc(ctx);
					if (n == null) return fail(ctx, "There's no such NPC.");
					settings.npcs.remove(n);
					return saved(ctx, "Removed the NPC " + n.name + ".");
				})))
				.then(Commands.literal("skin").then(npcArg.get().then(Commands.argument("skin", StringArgumentType.greedyString()).executes(ctx -> {
					Npc n = npc(ctx);
					if (n == null) return fail(ctx, "There's no such NPC.");
					String s = StringArgumentType.getString(ctx, "skin").strip();
					if (profile(s) == null && !Npcs.pictureLink(s)) return fail(ctx, "Use a player name, a link to the skin picture, a textures.minecraft.net hash, or the base64 value.");
					n.skin = s;
					if (Npcs.pictureLink(s)) {
						if (settings.mineskinKey.isBlank()) return fail(ctx, "Picture links need a MineSkin API key (free at mineskin.org): set it on the NPCs page first.");
						saved(ctx, n.name + ": uploading the picture through MineSkin; it shows in a few seconds.");
						uploadSkins(ctx.getSource().getServer());
						return 1;
					}
					return saved(ctx, n.name + ": skin set.");
				}))))
				.then(Commands.literal("pose").then(npcArg.get().then(Commands.argument("pose", StringArgumentType.word())
						.suggests((ctx, b) -> SharedSuggestionProvider.suggest(Npcs.POSES, b)).executes(ctx -> {
							Npc n = npc(ctx);
							if (n == null) return fail(ctx, "There's no such NPC.");
							String pose = StringArgumentType.getString(ctx, "pose").toLowerCase(Locale.ROOT);
							if (!Npcs.POSES.contains(pose)) return fail(ctx, "Poses: " + String.join(", ", Npcs.POSES) + ".");
							n.pose = pose;
							return saved(ctx, n.name + ": " + pose.replace('_', ' ') + ".");
						}))))
				.then(Commands.literal("equip").then(npcArg.get().executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					Npc n = npc(ctx);
					if (n == null) return fail(ctx, "There's no such NPC.");
					n.equipment = equipmentOf(p, p);
					return saved(ctx, n.name + " wears what you wear and holds what you hold (" + n.equipment.size() + " items).");
				})))
				.then(Commands.literal("path").then(npcArg.get()
						.then(Commands.literal("add").executes(ctx -> pathAdd(ctx, 0))
								.then(Commands.argument("wait", IntegerArgumentType.integer(0, 600)).executes(ctx -> pathAdd(ctx, IntegerArgumentType.getInteger(ctx, "wait")))))
						.then(Commands.literal("remove").executes(ctx -> {
							Npc n = npc(ctx);
							if (n == null || n.path.isEmpty()) return fail(ctx, "No checkpoints to take away.");
							n.path.remove(n.path.size() - 1);
							return saved(ctx, n.name + ": last checkpoint taken away (" + n.path.size() + " left).");
						}))
						.then(Commands.literal("clear").executes(ctx -> {
							Npc n = npc(ctx);
							if (n == null) return fail(ctx, "There's no such NPC.");
							n.path.clear();
							return saved(ctx, n.name + " stands on its spot again.");
						}))))
				.then(Commands.literal("shop").then(npcArg.get().then(Commands.argument("shop", StringArgumentType.word())
						.suggests((ctx, b) -> SharedSuggestionProvider.suggest(java.util.stream.Stream.concat(java.util.stream.Stream.of("none"), PointsStore.settings().shops.stream().map(x -> x.name)), b))
						.executes(ctx -> {
							Npc n = npc(ctx);
							if (n == null) return fail(ctx, "There's no such NPC.");
							String s = StringArgumentType.getString(ctx, "shop");
							if (s.equalsIgnoreCase("none")) {
								n.shop = "";
								return saved(ctx, n.name + " has no shop now.");
							}
							Points.Shop shop = PointsStore.settings().shop(s);
							if (shop == null) return fail(ctx, "There's no such reward shop.");
							n.shop = shop.id;
							return saved(ctx, n.name + " opens the shop " + shop.name + " on a right-click.");
						}))))
				// A hostile one now (as in a mob pool), e.g. for a fight made with command blocks.
				.then(Commands.literal("summon").then(npcArg.get().then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.Vec3Argument.vec3()).executes(ctx -> {
					Npc n = npc(ctx);
					if (n == null) return fail(ctx, "There's no such NPC.");
					Vec3 at = net.minecraft.commands.arguments.coordinates.Vec3Argument.getVec3(ctx, "pos");
					String dim = ctx.getSource().getLevel().dimension().identifier().toString();
					if (!spawnHostile(ctx.getSource().getServer(), dim, at.x, at.y, at.z, n.name, List.of())) return fail(ctx, "Could not summon it.");
					ctx.getSource().sendSuccess(() -> Component.literal("Summoned " + n.name + " to fight."), true);
					return 1;
				}))))
				.then(Commands.literal("list").executes(ctx -> {
					StringBuilder b = new StringBuilder(settings.npcs.size() + " NPCs:");
					for (Npc n : settings.npcs) {
						b.append("\n").append(n.name).append(n.spot == null ? " (not placed)" : " · " + n.spot.dim + " " + Math.round(n.spot.x) + ", " + Math.round(n.spot.y) + ", " + Math.round(n.spot.z))
								.append(n.walks() ? " · walks " + n.path.size() + " checkpoints" : "").append(n.shop.isBlank() ? "" : " · shop");
					}
					ctx.getSource().sendSuccess(() -> Component.literal(b.toString()), false);
					return 1;
				})));
	}

	private static int pathAdd(CommandContext<CommandSourceStack> ctx, int wait) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		Npc n = npc(ctx);
		if (n == null) return fail(ctx, "There's no such NPC.");
		if (n.spot == null) return fail(ctx, "Place it first: /npc move " + n.name + ".");
		Games.Pos at = new Games.Pos(p.level().dimension().identifier().toString(), p.getX(), p.getY(), p.getZ(), p.getYRot());
		if (!at.dim.equals(n.spot.dim)) return fail(ctx, "Checkpoints are in the NPC's dimension.");
		n.path.add(new Npcs.Checkpoint(at, wait));
		return saved(ctx, n.name + ": checkpoint " + n.path.size() + (wait > 0 ? " (waits " + wait + " s, looking where you look)" : "") + ".");
	}

	/** Takes over the mannequin the player looks at (or the nearest within 5 blocks): skin, pose, name, equipment. */
	private static int adopt(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String name = StringArgumentType.getString(ctx, "name");
		if (!name.matches("[A-Za-z0-9_-]{1,32}")) return fail(ctx, "One word: letters, digits, - and _.");
		if (settings.npc(name) != null) return fail(ctx, "There's an NPC called " + name + " already.");
		Mannequin found = null;
		for (Mannequin m : p.level().getEntitiesOfClass(Mannequin.class, p.getBoundingBox().inflate(5), m -> !m.entityTags().contains(BODY))) {
			if (found == null || m.distanceToSqr(p) < found.distanceToSqr(p)) found = m;
		}
		if (found == null) return fail(ctx, "Stand next to the mannequin (5 blocks at most).");
		var out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, p.registryAccess());
		found.saveWithoutId(out);
		CompoundTag t = out.buildResult();
		Npc n = new Npc();
		n.name = name;
		n.title = found.hasCustomName() ? found.getCustomName().getString() : "";
		n.description = t.contains("description") && !t.getBooleanOr("hide_description", false)
				? eu.explorerseden.nicecontrolcenter.players.MapExport.text(p.level().getServer(), t.get("description")) : "";
		n.pose = t.getStringOr("pose", "standing");
		n.mainHand = t.getStringOr("main_hand", "right");
		Tag prof = t.get("profile");
		if (prof instanceof CompoundTag c) {
			ListTag props = c.getListOrEmpty("properties");
			for (int i = 0; i < props.size(); i++) {
				CompoundTag pr = props.getCompoundOrEmpty(i);
				if ("textures".equals(pr.getStringOr("name", ""))) n.skin = pr.getStringOr("value", "");
			}
			if (n.skin.isBlank()) n.skin = c.getStringOr("name", "");
		} else if (prof != null) {
			n.skin = prof.asString().orElse("");
		}
		n.equipment = equipmentOf(p, found);
		n.spot = new Games.Pos(p.level().dimension().identifier().toString(), found.getX(), found.getY(), found.getZ(), found.getYRot());
		found.discard();
		settings.npcs.add(n);
		return saved(ctx, "Adopted the mannequin as the NPC " + name + " (skin, pose, name and equipment kept). Actions and lines: Community → NPCs.");
	}

	private static Npc npc(CommandContext<CommandSourceStack> ctx) {
		return settings.npc(StringArgumentType.getString(ctx, "npc"));
	}

	private static int saved(CommandContext<CommandSourceStack> ctx, String msg) {
		settings.fill();
		save();
		keep(ctx.getSource().getServer());
		ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
		return 1;
	}

	private static int fail(CommandContext<CommandSourceStack> ctx, String why) {
		ctx.getSource().sendFailure(Component.literal(why));
		return 0;
	}
}
