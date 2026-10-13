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
					if (!enabled()) {
						player.sendSystemMessage(Component.literal("There are no pets on this server.").withStyle(ChatFormatting.RED));
						Plop.play(player);
						return 0;
					}
					PetDialogs.home(player, null);
					return 1;
				}));
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
			p.sendSystemMessage(Component.literal("Your pet " + e.name() + " has run out. Get it again in /pets.").withStyle(ChatFormatting.GOLD));
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
}
