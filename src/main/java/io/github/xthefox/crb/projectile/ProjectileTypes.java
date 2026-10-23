package io.github.xthefox.crb.projectile;

import io.github.xthefox.crb.CRB;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Entity types for technique projectiles. */
public final class ProjectileTypes {
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, CRB.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<TechniqueProjectile>> TECHNIQUE_PROJECTILE = ENTITIES.register(
            "technique_projectile", () -> EntityType.Builder.<TechniqueProjectile>of(TechniqueProjectile::new, MobCategory.MISC)
                    .sized(0.5f, 0.5f)
                    .clientTrackingRange(16)
                    .updateInterval(1)
                    .noSave()
                    .noSummon()
                    .fireImmune()
                    .build("technique_projectile"));

    private ProjectileTypes() {}

    public static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
    }
}
