package io.github.xthefox.crb.combat;

import io.github.xthefox.crb.CRB;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageType;

/**
 * Damage categories. Each matches one of JJA's {@code damages/*} functions so Mahoraga can adapt
 * to it (and, unlike JJA, every source of damage has a kind). Backed by damage types in
 * {@code data/crb/damage_type}, all tagged to bypass armour, cooldowns and vanilla knockback,
 * like JJA's {@code damage ... suicide|override}.
 */
public enum DamageKind {
    BLUNT, SLASH, CLEAVE, DISMANTLE, FIRE, ELECTRIC, EXPLOSION, POISON, PULL, WATER, SOUL, SPEECH, SIGHT,
    DELETION, INFINITY, CURSE, SURE_HIT;

    private final ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE, CRB.id(name().toLowerCase(java.util.Locale.ROOT)));

    public ResourceKey<DamageType> key() {
        return key;
    }
}
