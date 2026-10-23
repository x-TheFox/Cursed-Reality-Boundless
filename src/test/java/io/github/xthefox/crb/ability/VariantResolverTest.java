package io.github.xthefox.crb.ability;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Lapse Blue's variants as extracted in catalog/movesets.md. */
class VariantResolverTest {
    static final Ability LAPSE_BLUE = new Ability("lapse_blue", "ability.crb.lapse_blue", 2, List.of(
            Variant.builder("form2", "limitless/lapse_blue").energy(50).cooldown("ability2", 250).duration(15).build(),
            Variant.builder("form2c", "limitless/lapse_blue_air").airborne(Tri.YES).energy(50).cooldown("ability2", 240).duration(21).build(),
            Variant.builder("form2e", "limitless/enchanted_blue").sneak(Tri.YES).airborne(Tri.ANY).lookUp(Tri.YES)
                    .requires("crb:six_eyes").energy(300).cooldown("ability7", 500).duration(66).build(),
            Variant.builder("form2b", "limitless/max_blue").sneak(Tri.YES).airborne(Tri.ANY).energy(250).cooldown("ability2", 400).duration(141).build()
    ));

    record User(int energy, int forms, Set<String> tags, Map<String, Integer> cds, boolean canAct) implements AbilityUser {
        public int cooldown(String k) { return cds.getOrDefault(k, 0); }
        public boolean has(String t) { return tags.contains(t); }
    }

    static User user(int energy, String... tags) {
        return new User(energy, 6, Set.of(tags), Map.of(), true);
    }

    @Test
    void plainPicksBaseVariant() {
        var r = VariantResolver.resolve(LAPSE_BLUE, Modifiers.NONE, user(1000), true);
        assertTrue(r.success());
        assertEquals("form2", r.variant().orElseThrow().form());
    }

    @Test
    void airbornePicksAirVariant() {
        var r = VariantResolver.resolve(LAPSE_BLUE, new Modifiers(false, true, false), user(1000), true);
        assertEquals("form2c", r.variant().orElseThrow().form());
    }

    @Test
    void sixEyesGateFallsThroughToNextVariant() {
        var sneakUp = new Modifiers(true, false, true);
        assertEquals("form2b", VariantResolver.resolve(LAPSE_BLUE, sneakUp, user(1000), true).variant().orElseThrow().form());
        assertEquals("form2e", VariantResolver.resolve(LAPSE_BLUE, sneakUp, user(1000, "crb:six_eyes"), true).variant().orElseThrow().form());
    }

    @Test
    void cooldownAndEnergyFailures() {
        var cd = new User(1000, 6, Set.of(), Map.of("ability2", 10), true);
        assertEquals(VariantResolver.Failure.ON_COOLDOWN, VariantResolver.resolve(LAPSE_BLUE, Modifiers.NONE, cd, true).failure());
        assertTrue(VariantResolver.resolve(LAPSE_BLUE, Modifiers.NONE, cd, false).success(), "cooldowns setting off");
        assertEquals(VariantResolver.Failure.NOT_ENOUGH_ENERGY, VariantResolver.resolve(LAPSE_BLUE, Modifiers.NONE, user(10), true).failure());
    }

    @Test
    void lockedAndBusy() {
        assertEquals(VariantResolver.Failure.LOCKED,
                VariantResolver.resolve(LAPSE_BLUE, Modifiers.NONE, new User(1000, 1, Set.of(), Map.of(), true), true).failure());
        assertEquals(VariantResolver.Failure.BUSY,
                VariantResolver.resolve(LAPSE_BLUE, Modifiers.NONE, new User(1000, 6, Set.of(), Map.of(), false), true).failure());
    }

    @Test
    void modifierBitsRoundTrip() {
        for (int bits = 0; bits < 8; bits++) assertEquals(bits, Modifiers.fromBits(bits).toBits());
        assertTrue(Modifiers.fromPitch(false, false, -60f).lookUp());
        assertFalse(Modifiers.fromPitch(false, false, 10f).lookUp());
    }

    @Test
    void techniquePaging() {
        var abilities = java.util.stream.IntStream.range(0, 11)
                .mapToObj(i -> new Ability("a" + i, "k", 0, List.of())).toList();
        Technique t = new Technique(Technique.Rarity.HONORED, true, 12).define(abilities, null);
        assertEquals(3, t.pages());
        assertEquals("a5", t.abilityAt(1, 0).orElseThrow().id());
        assertEquals("a10", t.abilityAt(2, 0).orElseThrow().id());
        assertTrue(t.abilityAt(2, 1).isEmpty());
        assertTrue(t.abilityAt(0, 5).isEmpty());
    }
}
