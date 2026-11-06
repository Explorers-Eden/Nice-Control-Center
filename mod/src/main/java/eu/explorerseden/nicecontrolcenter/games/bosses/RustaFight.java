package eu.explorerseden.nicecontrolcenter.games.bosses;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.Vec3;

/**
 * Rústa, Chieftain of the Ravaged Lands (Katters Structures, by Katter): a giant illager who keeps changing form
 * (with the same health): vindicator, pillager (arrows burst like wind charges), witch (potion clouds around it) and
 * evoker (vexes, and fangs under the players).
 */
final class RustaFight extends BossFight {
	private static final String NAME = "CustomNameVisible:1b,DeathLootTable:\"minecraft:empty\",PersistenceRequired:1b,CanPickUpLoot:0b,PatrolLeader:1b,"
			+ "CustomName:{bold:true,color:\"dark_gray\",text:\"Rústa, Chieftain of the Ravaged Lands\"},";

	private static String attributes(double speed, boolean armor) {
		return "attributes:[" + (armor ? "{id:\"minecraft:armor\",base:10}," : "") + "{id:\"minecraft:attack_knockback\",base:3},{id:\"minecraft:burning_time\",base:0},"
				+ "{id:\"minecraft:explosion_knockback_resistance\",base:1},{id:\"minecraft:fall_damage_multiplier\",base:0},{id:\"minecraft:follow_range\",base:35},"
				+ "{id:\"minecraft:knockback_resistance\",base:1.0},{id:\"minecraft:max_health\",base:200},{id:\"minecraft:movement_efficiency\",base:1},{id:\"minecraft:movement_speed\",base:" + speed + "},"
				+ "{id:\"minecraft:safe_fall_distance\",base:1024},{id:\"minecraft:scale\",base:4},{id:\"minecraft:step_height\",base:2}]";
	}

	private static final String AXE = "equipment:{mainhand:{id:\"minecraft:iron_axe\",count:1,components:{\"minecraft:enchantments\":{\"minecraft:vanishing_curse\":1},"
			+ "\"minecraft:attribute_modifiers\":[{id:\"block_interaction_range\",type:\"block_interaction_range\",amount:10,operation:\"add_value\",slot:\"mainhand\"}]}},"
			+ "offhand:{id:\"minecraft:shield\",count:1,components:{\"minecraft:enchantments\":{\"minecraft:vanishing_curse\":1}}}},drop_chances:{mainhand:0.0,offhand:0.0},";
	private static final String CROSSBOW = "equipment:{mainhand:{id:\"minecraft:crossbow\",count:1,components:{\"minecraft:enchantments\":{\"minecraft:quick_charge\":3,"
			+ "\"minecraft:multishot\":1,\"minecraft:vanishing_curse\":1}}}},drop_chances:{mainhand:0.0},";
	private static final String VEX = "{DeathLootTable:\"minecraft:empty\",Health:1f,life_ticks:200,attributes:[{id:\"minecraft:max_health\",base:1},{id:\"minecraft:scale\",base:4}]}";
	private static final String[] CLOUDS = { "minecraft:poison", "minecraft:slowness", "minecraft:weakness", "minecraft:harming" };
	private static final int[] CLOUD_COLORS = { 0x5E7C16, 0x9D9D97, 0x1D1D21, 0xB02E26 };

	private LivingEntity form;
	/** Seconds in this form. */
	private int seconds;
	/** Fangs to come (tick numbers). */
	private final List<Long> fangs = new ArrayList<>();
	private final Map<AbstractArrow, Vec3> arrows = new HashMap<>();

	RustaFight(ServerLevel level, List<String> tags, String bossTag, boolean bar, double x, double y, double z) {
		super(level, tags, bossTag, bar);
		form = (LivingEntity) spawn("minecraft:vindicator", x, y, z, "{" + NAME + "Health:200f," + AXE + attributes(0.3, false) + "}", true, false);
	}

	@Override
	String name() {
		return "Rústa, Chieftain of the Ravaged Lands";
	}

	@Override
	BossEvent.BossBarColor color() {
		return BossEvent.BossBarColor.WHITE;
	}

	@Override
	LivingEntity body() {
		return form;
	}

	@Override
	boolean alive() {
		return !dead(form);
	}

	@Override
	void tick() {
		arrows();
		for (Long t : List.copyOf(fangs)) {
			if (t > ticks) continue;
			fangs.remove(t);
			for (ServerPlayer p : near(form.position(), 25)) spawn("minecraft:evoker_fangs", p.getX(), p.getY(), p.getZ(), "{}", false, true);
		}
		if (ticks % 20 != 0) return;
		seconds++;
		if (seconds >= 20 || seconds >= 10 && random.nextInt(100) < 30) change();
	}

	/** A new form in its place, with its health. */
	private void change() {
		seconds = 0;
		LivingEntity old = form;
		Vec3 at = old.position();
		float health = old.getHealth();
		int phase = random.nextInt(4);
		String id;
		String data;
		switch (phase) {
			case 0 -> {
				sound(at, SoundEvents.RAVAGER_ROAR, 2, 1);
				id = "minecraft:vindicator";
				data = "{" + NAME + AXE + attributes(0.6, false) + "}";
			}
			case 1 -> {
				sound(at, SoundEvents.RAID_HORN.value(), 2, 1);
				id = "minecraft:pillager";
				data = "{" + NAME + CROSSBOW + attributes(0.2, false) + "}";
			}
			case 2 -> {
				sound(at, SoundEvents.WITCH_CELEBRATE, 2, 1);
				id = "minecraft:witch";
				data = "{" + NAME + attributes(0, true) + "}";
			}
			default -> {
				sound(at, SoundEvents.EVOKER_PREPARE_WOLOLO, 2, 1);
				id = "minecraft:evoker";
				data = "{" + NAME + attributes(0.2, false) + "}";
			}
		}
		old.discard();
		LivingEntity now = (LivingEntity) spawn(id, at.x, at.y, at.z, data, true, false);
		if (now == null) return;
		now.setHealth(health);
		form = now;
		if (phase == 2) {
			effect(now, MobEffects.REGENERATION, 200, 1);
			int[][] spots = { { 7, 7 }, { 7, 0 }, { 7, -7 }, { 0, 7 }, { 0, -7 }, { -7, 7 }, { -7, 0 }, { -7, -7 } };
			for (int[] s : spots) {
				int k = random.nextInt(4);
				spawn("minecraft:area_effect_cloud", at.x + s[0], at.y, at.z + s[1], "{custom_particle:{type:\"entity_effect\",color:" + CLOUD_COLORS[k] + "},ReapplicationDelay:2,Radius:3f,Duration:200,"
						+ "potion_contents:{potion:\"" + CLOUDS[k] + "\"}}", false, true);
			}
		}
		if (phase == 3) {
			for (int i = 0; i < 5; i++) spawn("minecraft:vex", at.x, at.y, at.z, VEX, false, false);
			for (int s : new int[] { 1, 3, 5, 7, 9 }) fangs.add(ticks + s * 20L);
		}
	}

	/** The pillager form's arrows burst like wind charges where they land. */
	private void arrows() {
		for (AbstractArrow a : level.getEntitiesOfClass(AbstractArrow.class, around(form, 64), x -> x.getOwner() == form && !arrows.containsKey(x))) arrows.put(a, a.position());
		for (Map.Entry<AbstractArrow, Vec3> e : List.copyOf(arrows.entrySet())) {
			AbstractArrow a = e.getKey();
			boolean landed = a.isRemoved() || a.getDeltaMovement().lengthSqr() < 0.0001;
			if (!landed) {
				arrows.put(a, a.position());
				continue;
			}
			windBurst(a.isRemoved() ? e.getValue() : a.position());
			if (!a.isRemoved()) a.discard();
			arrows.remove(a);
		}
	}

	@Override
	void hit(ServerPlayer victim, Entity attacker) {
	}
}
