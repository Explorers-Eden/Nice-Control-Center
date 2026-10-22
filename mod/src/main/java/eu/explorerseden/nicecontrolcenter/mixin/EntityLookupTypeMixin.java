package eu.explorerseden.nicecontrolcenter.mixin;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.EntityTypeTest;

import eu.explorerseden.nicecontrolcenter.core.PerfControl;

/**
 * Entities of each type in their own list, so @e[type=villager] without a distance looks at villagers only: vanilla
 * walks every loaded entity and checks its type, every time a data pack runs such a selector. The lists are kept in
 * the same add/remove calls as vanilla's own map, so the order within a type is the order vanilla would find them in.
 */
@Mixin(EntityLookup.class)
public abstract class EntityLookupTypeMixin<T extends EntityAccess> {
	@Unique
	private final Map<EntityType<?>, LinkedHashSet<T>> nicecontrolcenter$byType = new HashMap<>();

	@Inject(method = "add", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;put(ILjava/lang/Object;)Ljava/lang/Object;"))
	private void nicecontrolcenter$add(T entity, CallbackInfo ci) {
		if (entity instanceof Entity e) nicecontrolcenter$byType.computeIfAbsent(e.getType(), k -> new LinkedHashSet<>()).add(entity);
	}

	@Inject(method = "remove", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;remove(I)Ljava/lang/Object;"))
	private void nicecontrolcenter$remove(T entity, CallbackInfo ci) {
		if (!(entity instanceof Entity e)) return;
		LinkedHashSet<T> list = nicecontrolcenter$byType.get(e.getType());
		if (list != null && list.remove(entity) && list.isEmpty()) nicecontrolcenter$byType.remove(e.getType());
	}

	@SuppressWarnings("unchecked")
	@Inject(method = "getEntities", at = @At("HEAD"), cancellable = true)
	private <U extends T> void nicecontrolcenter$byType(EntityTypeTest<T, U> test, AbortableIterationConsumer<U> consumer, CallbackInfo ci) {
		if (!(test instanceof EntityType<?> type) || !PerfControl.selectorsByType()) return;
		ci.cancel();
		LinkedHashSet<T> list = nicecontrolcenter$byType.get(type);
		if (list == null) return;
		// A copy: whoever gets the entities may add or remove some while going through them.
		for (Object entity : list.toArray()) {
			U found = test.tryCast((T) entity);
			if (found != null && consumer.accept(found).shouldAbort()) return;
		}
	}
}
