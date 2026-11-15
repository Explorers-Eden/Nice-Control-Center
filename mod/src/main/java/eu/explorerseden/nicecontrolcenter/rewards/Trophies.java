package eu.explorerseden.nicecontrolcenter.rewards;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import eu.explorerseden.nicecontrolcenter.Pets;
import eu.explorerseden.nicecontrolcenter.Rewards;
import eu.explorerseden.nicecontrolcenter.games.bosses.KatterBosses;

/**
 * Boss trophies (the Rewards action "trophy"): a placeable head named after the boss. Its look: the skin set for the
 * boss on the Rewards page, else the vanilla head of the mob it's based on (skulls, creeper, piglin…), else a plain
 * player head.
 */
public final class Trophies {
	/** Bosses whose body is a mob with a vanilla head (NCC bosses and plain mob lines). */
	private static final Map<String, net.minecraft.world.item.Item> HEADS = Map.ofEntries(
			Map.entry("ncc:frost_warden", Items.SKELETON_SKULL), Map.entry("ncc:bone_architect", Items.SKELETON_SKULL),
			Map.entry("ncc:creeper_king", Items.CREEPER_HEAD), Map.entry("ncc:piglin_warlord", Items.PIGLIN_HEAD),
			Map.entry("ncc:rotting_giant", Items.ZOMBIE_HEAD), Map.entry("ncc:drowned_admiral", Items.ZOMBIE_HEAD),
			Map.entry("minecraft:zombie", Items.ZOMBIE_HEAD), Map.entry("minecraft:husk", Items.ZOMBIE_HEAD), Map.entry("minecraft:drowned", Items.ZOMBIE_HEAD),
			Map.entry("minecraft:skeleton", Items.SKELETON_SKULL), Map.entry("minecraft:stray", Items.SKELETON_SKULL), Map.entry("minecraft:bogged", Items.SKELETON_SKULL),
			Map.entry("minecraft:wither_skeleton", Items.WITHER_SKELETON_SKULL), Map.entry("minecraft:wither", Items.WITHER_SKELETON_SKULL),
			Map.entry("minecraft:creeper", Items.CREEPER_HEAD), Map.entry("minecraft:piglin", Items.PIGLIN_HEAD), Map.entry("minecraft:piglin_brute", Items.PIGLIN_HEAD),
			Map.entry("minecraft:ender_dragon", Items.DRAGON_HEAD));

	private Trophies() {
	}

	/** "ncc:frost_warden" / "frost_warden" / "ravager {…}" → the boss as written in lists ("ncc:frost_warden", "minecraft:ravager"). */
	public static String key(String boss) {
		String b = boss == null ? "" : boss.strip().split("[\\s{]", 2)[0].toLowerCase(Locale.ROOT);
		String resolved = KatterBosses.resolve(b);
		if (resolved != null) return resolved;
		return b.contains(":") ? b : "minecraft:" + b;
	}

	/** The boss's name ("The Frost Warden", "Ravager"). */
	public static String title(String boss) {
		String k = key(boss);
		String t = KatterBosses.title(k);
		if (t != null && !t.equals(k)) return t;
		String path = k.substring(k.indexOf(':') + 1).replace('_', ' ');
		return Character.toUpperCase(path.charAt(0)) + path.substring(1);
	}

	/** The trophy head for the boss. */
	public static ItemStack make(MinecraftServer server, String boss, Rewards.Settings s) {
		String k = key(boss);
		ItemStack st = ItemStack.EMPTY;
		String skin = s == null ? null : Pets.texture(s.trophySkins.get(k));
		if (skin != null) st = eu.explorerseden.nicecontrolcenter.pets.PetDialogs.headOf(server, skin);
		if (st.isEmpty()) st = new ItemStack(HEADS.getOrDefault(k, Items.PLAYER_HEAD));
		st.set(DataComponents.CUSTOM_NAME, Component.literal(title(k) + " Trophy").withStyle(s2 -> s2.withColor(0xFFC83D).withItalic(false).withBold(true)));
		st.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("Beaten on " + LocalDate.now()).withStyle(s2 -> s2.withColor(ChatFormatting.GRAY).withItalic(false)),
				Component.literal(k).withStyle(s2 -> s2.withColor(ChatFormatting.DARK_GRAY).withItalic(false)))));
		st.set(DataComponents.RARITY, net.minecraft.world.item.Rarity.EPIC);
		return st;
	}
}
