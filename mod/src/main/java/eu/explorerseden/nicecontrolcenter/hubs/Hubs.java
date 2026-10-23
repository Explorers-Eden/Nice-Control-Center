package eu.explorerseden.nicecontrolcenter.hubs;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;

import eu.explorerseden.nicecontrolcenter.Borders;
import eu.explorerseden.nicecontrolcenter.NiceControlCenter;
import eu.explorerseden.nicecontrolcenter.claims.ClaimStore;
import eu.explorerseden.nicecontrolcenter.claims.ClaimTool;
import eu.explorerseden.nicecontrolcenter.core.HardBorder;
import eu.explorerseden.nicecontrolcenter.mixin.TextDisplayAccessor;

/**
 * Warping Wonders' waypoint hubs, without touching the data pack: where they may be placed, and
 * reading and changing a hub the way the pack's own name tag, book, lock and paper do.
 *
 * <p>The pack keeps hubs in command storage (players_storage, normally eden:database) under
 * waypoints.hubs.&lt;id&gt;; each hub has a marker with that id as its wawo.waypoint_hub.id score and a
 * text display showing the name.
 */
public final class Hubs {
	/** The armor stand a hub item places; the pack turns it into a hub on the next tick. */
	private static final String SPAWNER = "wawo.waypoint_hub.spawner";
	private static final String TYPE = "wawo.waypoint_hub.type.";
	private static final String MARKER = "wawo.waypoint_hub.marker";
	private static final String DISPLAY = "wawo.waypoint_hub.display";
	private static final String ID_SCORE = "wawo.waypoint_hub.id";
	/** Names and descriptions go into the pack's dialog JSON through macros, so these would break its menu. */
	public static final int MAX_TEXT = 50;
	/** The pack's colours (one per dye), as its dyes set them. */
	public static final java.util.Map<String, String> COLORS = colors();
	/** The block that was under the hub before 1.19.1's menu put wool there. */
	private static final String UNDER = "nicecontrolcenter_under";
	/** Set when the owner switched the arrival message off in the menu: the wool check then leaves the hub alone. */
	private static final String CHOSEN = "nicecontrolcenter_announcement";

	private static java.util.Map<String, String> colors() {
		java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
		m.put("white", "#FFFFFF");
		m.put("light_gray", "#d9d9d9");
		m.put("gray", "#979797");
		m.put("black", "#1c1c22");
		m.put("brown", "#895836");
		m.put("red", "#be3030");
		m.put("orange", "#f4b33f");
		m.put("yellow", "#e4e229");
		m.put("lime", "#83d41c");
		m.put("green", "#577a22");
		m.put("cyan", "#3889aa");
		m.put("light_blue", "#8fb9f4");
		m.put("blue", "#142eaf");
		m.put("purple", "#b064d8");
		m.put("magenta", "#db7ad5");
		m.put("pink", "#f2aed1");
		return java.util.Collections.unmodifiableMap(m);
	}

	/** The dye whose colour the hub has, or null for a colour that isn't in the list. */
	public static String dye(CompoundTag hub) {
		String c = hub.getStringOr("color", "#ffffff");
		for (var e : COLORS.entrySet()) if (e.getValue().equalsIgnoreCase(c)) return e.getKey();
		return null;
	}

	private Hubs() {
	}

	// ---------------------------------------------------------------- placing

	/**
	 * A hub item is placed in a blocked dimension or outside the permanent area: the hub item drops
	 * again (as the pack did) and the stand is never added. Called for every new entity, so the first
	 * check is cheap.
	 */
	public static boolean refusePlacement(ServerLevel level, Entity e) {
		if (!(e instanceof ArmorStand) || !e.entityTags().contains(SPAWNER)) return false;
		var settings = ClaimStore.settings();
		String dim = level.dimension().identifier().toString();
		String why = null;
		if (!settings.hubsAllowed(dim)) {
			why = "You can't place Waypoint Hubs in this dimension!";
		} else if (settings.hubsOnlyPermanentArea) {
			Borders.Border b = HardBorder.borders().get(dim);
			if (b != null && b.soft > 0 && !b.inside(e.getX(), e.getZ(), b.soft)) why = "You can't place Waypoint Hubs in the outer Wilds!";
		}
		if (why == null) return false;
		var near = level.getNearestPlayer(e.getX(), e.getY(), e.getZ(), 8, false);
		if (near instanceof ServerPlayer player && ClaimTool.bypassing(player)) return false;
		if (near instanceof ServerPlayer player) {
			ClaimTool.bar(player, why, ChatFormatting.RED);
			level.playSound(null, player.blockPosition(), SoundEvents.CHICKEN_EGG, SoundSource.NEUTRAL, 0.6f, 2f);
		}
		MinecraftServer server = level.getServer();
		for (String tag : e.entityTags()) {
			if (!tag.startsWith(TYPE)) continue;
			String type = tag.substring(TYPE.length());
			if (!type.matches("[a-z0-9_]+")) continue;
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(level).withSuppressedOutput(),
					"loot spawn " + e.getX() + " " + e.getY() + " " + e.getZ() + " loot wawo:item/waypoint_hub/" + type);
		}
		return true;
	}

	/** A Warping Wonders hub item (an armor stand item the pack marks as a hub). */
	public static boolean isHubItem(net.minecraft.world.item.ItemStack stack) {
		if (!stack.is(net.minecraft.world.item.Items.ARMOR_STAND)) return false;
		var data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
		return data != null && "waypoint_hub".equals(data.copyTag().getCompoundOrEmpty("warping_wonders").getStringOr("item", ""));
	}

	// ---------------------------------------------------------------- reading

	private static Identifier storageId() {
		String id = NiceControlCenter.config().players_storage;
		return Identifier.tryParse(id == null || id.isBlank() ? "eden:database" : id);
	}

	/** The whole storage compound (live: changes need {@link #save}). */
	private static CompoundTag database(MinecraftServer server) {
		Identifier id = storageId();
		return id == null ? new CompoundTag() : server.getCommandStorage().get(id);
	}

	private static void save(MinecraftServer server, CompoundTag database) {
		Identifier id = storageId();
		if (id != null) server.getCommandStorage().set(id, database);
	}

	/** The hub's storage entry, or null. */
	public static CompoundTag hub(MinecraftServer server, int id) {
		CompoundTag hubs = database(server).getCompoundOrEmpty("waypoints").getCompoundOrEmpty("hubs");
		return hubs.get(String.valueOf(id)) instanceof CompoundTag hub ? hub : null;
	}

	public static boolean ownedBy(CompoundTag hub, UUID player) {
		return hub != null && hub.getCompoundOrEmpty("profile").getIntArray("id").map(a -> a.length == 4 && UUIDUtil.uuidFromIntArray(a).equals(player)).orElse(false);
	}

	public static String name(MinecraftServer server, CompoundTag hub) {
		String name = eu.explorerseden.nicecontrolcenter.players.MapExport.text(server, hub.get("waypoint_name"));
		return name.isBlank() ? "Waypoint" : name;
	}

	/** The description without the two line breaks the pack adds for its tooltip. */
	public static String description(MinecraftServer server, CompoundTag hub) {
		return eu.explorerseden.nicecontrolcenter.players.MapExport.text(server, hub.get("waypoint_description")).strip();
	}

	public static boolean isPublic(CompoundTag hub) {
		return !"private".equals(hub.getStringOr("access", "public"));
	}

	public static int color(CompoundTag hub) {
		String c = hub.getStringOr("color", "#ffffff");
		try {
			return c.matches("#[0-9a-fA-F]{6}") ? Integer.parseInt(c.substring(1), 16) : 0xFFFFFF;
		} catch (NumberFormatException e) {
			return 0xFFFFFF;
		}
	}

	/** The trusted players' names (the pack keeps them with ", " entries between them, for its tooltip). */
	public static List<String> trusted(CompoundTag hub) {
		List<String> out = new ArrayList<>();
		ListTag list = hub.getListOrEmpty("trust");
		for (int i = 0; i < list.size(); i++) {
			String name = list.getStringOr(i, "");
			if (!name.isBlank() && !", ".equals(name)) out.add(name);
		}
		return out;
	}

	public static String dimension(CompoundTag hub) {
		return hub.getCompoundOrEmpty("pos").getStringOr("dimension", "minecraft:overworld");
	}

	/** The hub's marker, if its chunk is loaded and the player stands within 16 blocks of it (what the pack's own tools need too). */
	public static Marker marker(ServerPlayer player, int id) {
		Objective objective = player.level().getScoreboard().getObjective(ID_SCORE);
		if (objective == null) return null;
		for (Marker m : player.level().getEntitiesOfClass(Marker.class, player.getBoundingBox().inflate(16), m -> m.entityTags().contains(MARKER))) {
			ReadOnlyScoreInfo score = player.level().getScoreboard().getPlayerScoreInfo(m, objective);
			if (score != null && score.value() == id && m.distanceTo(player) <= 16) return m;
		}
		return null;
	}

	// ---------------------------------------------------------------- changing

	/** Characters that would break the pack's menu once its macros paste the text into JSON. */
	public static String clean(String text) {
		String s = text == null ? "" : text.replaceAll("[\"\\\\\\p{Cntrl}]", "").strip();
		return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT).strip() : s;
	}

	/** Like a renamed name tag: storage, the marker's name and the floating text (its colour stays). */
	public static void rename(ServerPlayer player, int id, Marker marker, String name) {
		MinecraftServer server = player.level().getServer();
		CompoundTag database = database(server);
		CompoundTag hub = hubIn(database, id);
		if (hub == null) return;
		hub.putString("waypoint_name", name);
		save(server, database);
		marker.setCustomName(Component.literal(name));
		Display.TextDisplay text = textDisplay(player, marker);
		if (text != null) {
			TextDisplayAccessor acc = (TextDisplayAccessor) text;
			Component old = acc.nicecontrolcenter$getText();
			acc.nicecontrolcenter$setText(Component.literal(name).withStyle(old == null ? net.minecraft.network.chat.Style.EMPTY : old.getStyle()));
		}
	}

	/** The hub's floating name, the text display closest to its marker. */
	private static Display.TextDisplay textDisplay(ServerPlayer player, Marker marker) {
		AABB around = marker.getBoundingBox().inflate(3);
		return player.level().getEntitiesOfClass(Display.TextDisplay.class, around, d -> d.entityTags().contains(DISPLAY)).stream()
				.min(java.util.Comparator.comparingDouble(d -> d.distanceToSqr(marker))).orElse(null);
	}

	/** Like a renamed book (empty = no description). */
	public static void describe(ServerPlayer player, int id, String description) {
		MinecraftServer server = player.level().getServer();
		CompoundTag database = database(server);
		CompoundTag hub = hubIn(database, id);
		if (hub == null) return;
		if (description.isEmpty()) hub.remove("waypoint_description");
		else hub.putString("waypoint_description", description + "\n\n");
		save(server, database);
	}

	/** Like a dye: storage and the floating name's colour. */
	public static void recolor(ServerPlayer player, int id, Marker marker, String dye) {
		String hex = COLORS.get(dye);
		MinecraftServer server = player.level().getServer();
		CompoundTag database = database(server);
		CompoundTag hub = hubIn(database, id);
		if (hub == null || hex == null) return;
		hub.putString("color", hex);
		save(server, database);
		Display.TextDisplay text = textDisplay(player, marker);
		if (text != null) {
			TextDisplayAccessor acc = (TextDisplayAccessor) text;
			Component old = acc.nicecontrolcenter$getText();
			Component now = old == null ? Component.literal(name(server, hub)) : old;
			acc.nicecontrolcenter$setText(now.copy().withColor(Integer.parseInt(hex.substring(1), 16)));
		}
	}

	/** Whether the owner hears and reads who teleports to the hub. */
	public static boolean announces(CompoundTag hub) {
		return !"disabled".equals(hub.getStringOr("announcement", "enabled"));
	}

	/**
	 * Switches the arrival message straight in storage. Off is remembered as the owner's choice, so
	 * the pack's check of the block under the hub (wool = no message, every 2 seconds) leaves the hub
	 * alone (see {@link #ownerChoseAnnouncement}); on hands the hub back to that check, so it can't
	 * be switched on over wool. Returns why not, or null.
	 */
	public static String setAnnounces(ServerPlayer player, int id, Marker marker, boolean on) {
		ServerLevel level = player.level();
		MinecraftServer server = level.getServer();
		CompoundTag database = database(server);
		CompoundTag hub = hubIn(database, id);
		if (hub == null) return null;
		if (on) {
			net.minecraft.core.BlockPos below = marker.blockPosition().below();
			boolean wool = level.getBlockState(below).is(net.minecraft.tags.BlockTags.WOOL);
			if (wool && hub.get(UNDER) instanceof CompoundTag before) {
				// 1.19.1's menu put this wool there: put back what was there.
				level.setBlock(below, net.minecraft.nbt.NbtUtils.readBlockState(level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK), before), 3);
			} else if (wool) {
				return "There's wool under the hub, and wool switches the arrival message off. Take it away to switch the message on.";
			}
			hub.remove(UNDER);
			hub.remove(CHOSEN);
		} else {
			hub.putString(CHOSEN, "disabled");
		}
		hub.putString("announcement", on ? "enabled" : "disabled");
		save(server, database);
		return null;
	}

	/** The marker belongs to a hub whose arrival message was switched off in the owner menu (so the pack's wool check must not turn it on again). */
	public static boolean ownerChoseAnnouncement(Entity entity) {
		if (!(entity instanceof Marker marker) || !marker.entityTags().contains(MARKER)) return false;
		var scoreboard = marker.level().getScoreboard();
		Objective objective = scoreboard.getObjective(ID_SCORE);
		ReadOnlyScoreInfo score = objective == null ? null : scoreboard.getPlayerScoreInfo(marker, objective);
		if (score == null || !(marker.level() instanceof ServerLevel level)) return false;
		CompoundTag hub = hub(level.getServer(), score.value());
		return hub != null && hub.contains(CHOSEN);
	}

	/** Like the waypoint lock. */
	public static void setPublic(ServerPlayer player, int id, boolean open) {
		MinecraftServer server = player.level().getServer();
		CompoundTag database = database(server);
		CompoundTag hub = hubIn(database, id);
		if (hub == null) return;
		hub.putString("access", open ? "public" : "private");
		save(server, database);
	}

	/** Like a "trust Name" / "untrust Name" paper; keeps the pack's ", " entries between names. */
	public static void setTrusted(ServerPlayer player, int id, List<String> names) {
		MinecraftServer server = player.level().getServer();
		CompoundTag database = database(server);
		CompoundTag hub = hubIn(database, id);
		if (hub == null) return;
		if (names.isEmpty()) {
			hub.remove("trust");
		} else {
			ListTag list = new ListTag();
			for (String n : names) {
				if (!list.isEmpty()) list.add(StringTag.valueOf(", "));
				list.add(StringTag.valueOf(n));
			}
			hub.put("trust", list);
		}
		save(server, database);
	}

	private static CompoundTag hubIn(CompoundTag database, int id) {
		Tag hub = database.getCompoundOrEmpty("waypoints").getCompoundOrEmpty("hubs").get(String.valueOf(id));
		return hub instanceof CompoundTag c ? c : null;
	}
}
