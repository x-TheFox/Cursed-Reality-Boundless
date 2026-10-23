package io.github.xthefox.crb.projectile;

import net.minecraft.world.entity.LivingEntity;

/** Java behaviour for a named projectile that its extracted lines cannot express (pulls, draining). */
@FunctionalInterface
public interface ProjectileLogic {
    ProjectileLogic NONE = (p, remaining, owner) -> { };

    /** Runs after the extracted simulation lines, before movement and the Deleter countdown. */
    void tick(TechniqueProjectile p, int remaining, LivingEntity owner);
}
