package eu.explorerseden.nicecontrolcenter.pets;

import com.mojang.brigadier.CommandDispatcher;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.Pets;
import eu.explorerseden.nicecontrolcenter.core.Plop;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pets while the server runs: who owns what (world file), the shop from the panel (config file, read again
 * when it changes), pets running out, and /pets. Server thread.
 */
public final class PetStore {
	public static final String PERMISSION_USE = "nicecontrolcenter.pets.use";
	private static Pets.Data data = new Pets.Data().fill();
	private static Pets.Settings settings = new Pets.Settings().fill();
	private static long settingsModified = -1;
	private static Path file;
	private static boolean dirty;

	private PetStore() {
	}

	public static Pets.Data data() {
		return data;
	}

	public static Pets.Settings settings() {
		return settings;
	}

	public static boolean enabled() {
		return settings.enabled;
	}

	public static void register() {
		net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((d, a, e) -> command(d));
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(PetStore::load);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			PetFollowers.removeAll();
			save();
			file = null;
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(PetStore::tick);
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> PetFollowers.remove(h.player.getUUID()));
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((e, level) -> {
			if (PetFollowers.leftOver(e)) e.discard();
		});
	}

	private static void command(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("pets")
				.requires(Permissions.require(PERMISSION_USE, PermissionLevel.ALL))
				.executes(ctx -> {
					ServerPlayer player = ctx.getSource().getPlayerOrException();
					if (!enabled()) return off(player);
					PetDialogs.home(player, null);
					return 1;
				})
				.then(Commands.literal("on").executes(ctx -> toggle(ctx.getSource().getPlayerOrException(), true)))
				.then(Commands.literal("off").executes(ctx -> toggle(ctx.getSource().getPlayerOrException(), false))));
	}

	private static int off(ServerPlayer player) {
		player.sendSystemMessage(Component.literal("There are no pets on this server.").withStyle(ChatFormatting.RED));
		Plop.play(player);
		return 0;
	}

	/** /pets on and /pets off: the quick way, with a line above the hotbar. */
	private static int toggle(ServerPlayer player, boolean on) {
		if (!enabled()) return off(player);
		Pets.Owner o = data.owners.get(player.getStringUUID());
		Component msg;
		if (o == null || o.pets.isEmpty()) {
			msg = Component.literal("You don't have a pet yet: get one with /pets").withStyle(ChatFormatting.RED);
		} else if (on) {
			Pets.Owned out = o.active != null ? o.owned(o.active) : o.takeOut();
			msg = Component.literal(name(out) + " is out").withStyle(ChatFormatting.GREEN);
		} else if (o.active == null) {
			msg = Component.literal("Your pet is already put away").withStyle(ChatFormatting.GRAY);
		} else {
			String n = name(o.owned(o.active));
			o.putAway();
			msg = Component.literal(n + " is put away").withStyle(ChatFormatting.YELLOW);
		}
		changed();
		player.sendOverlayMessage(msg);
		Plop.play(player);
		return 1;
	}

	private static Path settingsFile() {
		return FabricLoader.getInstance().getConfigDir().resolve(Pets.FILE);
	}

	private static void load(MinecraftServer s) {
		file = s.getWorldPath(LevelResource.ROOT).resolve(Pets.DATA_FILE);
		try {
			data = Pets.readData(file);
		} catch (IOException | RuntimeException e) {
			NiceControlCenter.LOGGER.error("Couldn't read {}; pets start empty and the file is kept as .broken", file, e);
			try {
				Files.copy(file, file.resolveSibling(file.getFileName() + ".broken"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
				// Nothing more to do.
			}
			data = new Pets.Data().fill();
		}
		settingsModified = -1;
		reloadSettings();
		dirty = false;
	}

	private static void tick(MinecraftServer s) {
		if (file == null) return;
		int tick = s.getTickCount();
		if (tick % 100 == 0) reloadSettings();
		if (tick % 200 == 100) expire(s);
		PetFollowers.tick(s);
		if (dirty && tick % 100 == 50) save();
	}

	private static void expire(MinecraftServer s) {
		List<Pets.Expired> gone = data.expire(System.currentTimeMillis());
		if (gone.isEmpty()) return;
		for (Pets.Expired e : gone) {
			ServerPlayer p = s.getPlayerList().getPlayer(UUID.fromString(e.owner()));
			if (p == null) continue;
			Pets.Pet pet = settings.pet(e.pet());
			p.sendSystemMessage(Component.literal("Your pet " + (pet != null ? pet.name : e.name()) + " has run out. Get it again in /pets.").withStyle(ChatFormatting.GOLD));
			Plop.play(p);
		}
		saveNow();
	}

	/** Reads the settings again if the panel changed them (also from the dashboard API, which works while paused). */
	public static void reloadSettings() {
		Path f = settingsFile();
		long now;
		try {
			now = Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() : 0;
		} catch (IOException e) {
			return;
		}
		if (now == settingsModified) return;
		settingsModified = now;
		settings = Pets.read(f);
	}

	/** Right away: what players paid for must never be lost to a crash. */
	public static void saveNow() {
		dirty = true;
		save();
	}

	public static void changed() {
		dirty = true;
	}

	private static void save() {
		Path f = file;
		if (f == null || !dirty) return;
		try {
			Pets.writeData(f, data);
			dirty = false;
		} catch (IOException e) {
			NiceControlCenter.LOGGER.warn("Couldn't save {}: {}", f, e.toString());
		}
	}

	/** The pet that's out for the player, or null. */
	static Pets.Owned active(ServerPlayer player) {
		Pets.Owner o = data.owners.get(player.getStringUUID());
		return o == null || o.active == null ? null : o.owned(o.active);
	}

	/** The head to show: the shop's current one if the pet is still there, else the copy from when it was bought. */
	static String texture(Pets.Owned owned) {
		Pets.Pet p = settings.pet(owned.pet);
		return p != null ? p.texture : owned.texture;
	}

	/** The pet's name: the shop's current one if it's still there, else the one from when it was bought. */
	static String name(Pets.Owned owned) {
		Pets.Pet p = settings.pet(owned.pet);
		return p != null ? p.name : owned.name;
	}

	/** For the panel: what was sold and who has which pet. */
	public static Map<String, Object> state() {
		reloadSettings();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("sold", data.sold);
		Map<String, Integer> owners = new LinkedHashMap<>();
		for (Pets.Owner o : data.owners.values()) for (Pets.Owned p : o.pets) owners.merge(p.pet, 1, Integer::sum);
		out.put("owners", owners);
		out.put("purchases", data.purchases);
		return out;
	}

	/** For the dashboard's player card: their pets, and the pets that can be given. */
	public static Map<String, Object> forPlayer(String uuid) {
		reloadSettings();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("enabled", settings.enabled);
		Pets.Owner o = data.owners.get(uuid);
		List<Map<String, Object>> owned = new java.util.ArrayList<>();
		if (o != null) for (Pets.Owned p : o.pets) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", p.pet);
			m.put("name", name(p));
			m.put("head", Pets.skinHash(texture(p)));
			m.put("since", p.since);
			m.put("expires", p.expires);
			m.put("active", p.pet.equals(o.active));
			m.put("inShop", settings.pet(p.pet) != null);
			owned.add(m);
		}
		out.put("owned", owned);
		out.put("behavior", Pets.Behavior.of(o == null ? null : o.behavior).name());
		out.put("trail", Pets.Trail.of(o == null ? null : o.trail).name());
		out.put("particles", settings.particles);
		List<Map<String, Object>> trails = new java.util.ArrayList<>();
		for (Pets.Trail t : Pets.Trail.values()) trails.add(Map.of("id", t.name(), "label", t.label));
		out.put("trails", trails);
		List<Map<String, Object>> all = new java.util.ArrayList<>();
		for (Pets.Pet p : settings.pets) all.add(Map.of("id", p.id, "name", p.name, "days", p.days, "retired", p.retired));
		out.put("shop", all);
		List<Map<String, Object>> moves = new java.util.ArrayList<>();
		for (Pets.Behavior b : Pets.Behavior.values()) moves.add(Map.of("id", b.name(), "label", b.label));
		out.put("behaviors", moves);
		return out;
	}

	/**
	 * An admin changes a player's pets: give (days, 0 = forever), add (days more), forever, take (away),
	 * out, away, move (how), trail (how). Returns an error, or null.
	 */
	public static String adminAction(MinecraftServer server, String uuid, String playerName, String action, String pet, int days, String how, String by) {
		if (!uuid.matches("[0-9a-fA-F-]{36}")) return "Unknown player.";
		reloadSettings();
		long now = System.currentTimeMillis();
		Pets.Owner o = data.owners.get(uuid);
		if (playerName != null && playerName.isBlank()) playerName = o != null ? o.name : null;
		Pets.Owned owned = o == null ? null : o.owned(pet);
		String what;
		switch (action) {
			case "give" -> {
				Pets.Pet p = settings.pet(pet);
				if (p == null) return "That pet isn't in the pet shop list.";
				if (days < 0 || days > 3650) return "Give it for 1 to 3650 days, or forever.";
				data.give(uuid, playerName, p, days, now);
				what = "gave " + p.name + " (" + Pets.duration(days) + ")";
			}
			case "add" -> {
				if (owned == null) return "They don't have that pet.";
				if (owned.expires == 0) return "It's already theirs forever.";
				if (days < 1 || days > 3650) return "Add 1 to 3650 days.";
				owned.expires = Math.max(now, owned.expires) + days * 86_400_000L;
				what = "added " + Pets.duration(days) + " to " + name(owned);
			}
			case "forever" -> {
				if (owned == null) return "They don't have that pet.";
				owned.expires = 0;
				what = "made " + name(owned) + " theirs forever";
			}
			case "take" -> {
				if (owned == null || !data.take(uuid, pet)) return "They don't have that pet.";
				what = "took " + name(owned) + " away";
			}
			case "out" -> {
				if (owned == null) return "They don't have that pet.";
				o.active = owned.pet;
				what = "took " + name(owned) + " out";
			}
			case "away" -> {
				if (o == null) return null;
				o.putAway();
				what = "put their pet away";
			}
			case "move" -> {
				if (o == null) return "They don't have a pet.";
				o.behavior = Pets.Behavior.of(how).name();
				what = "set their pet to " + Pets.Behavior.of(how).label;
			}
			case "trail" -> {
				if (o == null) return "They don't have a pet.";
				o.trail = Pets.Trail.of(how).name();
				what = "set their pet's particles to " + Pets.Trail.of(how).label;
			}
			default -> {
				return "Unknown action.";
			}
		}
		saveNow();
		NiceControlCenter.LOGGER.info("Pets: {} {} for {}", by == null ? "an admin" : by, what, playerName != null ? playerName : uuid);
		ServerPlayer online = server.getPlayerList().getPlayer(UUID.fromString(uuid));
		if (online != null && (action.equals("give") || action.equals("take") || action.equals("add") || action.equals("forever"))) {
			String msg = switch (action) {
				case "give" -> "You got the pet " + settings.pet(pet).name + (days > 0 ? " for " + Pets.duration(days) : "") + "! See /pets.";
				case "take" -> "Your pet " + name(owned) + " was taken away.";
				case "add" -> "Your pet " + name(owned) + " got " + Pets.duration(days) + " more.";
				default -> "Your pet " + name(owned) + " is yours forever now.";
			};
			online.sendSystemMessage(Component.literal(msg).withStyle(ChatFormatting.GOLD));
			Plop.play(online);
		}
		return null;
	}
}
