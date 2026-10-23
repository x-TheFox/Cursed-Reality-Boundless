package io.github.xthefox.crb.ability;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * One key of a technique (one JJA hotbar item, e.g. {@code ds:limitless2} "Lapse Blue").
 * Variants are checked in order; the first whose input condition and requirements match wins,
 * which mirrors the line order of JJA's {@code *_click} functions.
 *
 * @param icon HUD icon texture (JJA's item texture for the skill), may be null
 */
public record Ability(String id, String nameKey, int unlockForm, List<Variant> variants, ResourceLocation icon) {
    public Ability {
        variants = List.copyOf(variants);
    }

    public Ability(String id, String nameKey, int unlockForm, List<Variant> variants) {
        this(id, nameKey, unlockForm, variants, null);
    }
}
