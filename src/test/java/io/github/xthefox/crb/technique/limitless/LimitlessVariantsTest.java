package io.github.xthefox.crb.technique.limitless;

import io.github.xthefox.crb.ability.Ability;
import io.github.xthefox.crb.ability.AbilityUser;
import io.github.xthefox.crb.ability.Modifiers;
import io.github.xthefox.crb.ability.Variant;
import io.github.xthefox.crb.ability.VariantResolver;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** JJA's limitless_click gates, with its easy-to-misread look tags. */
class LimitlessVariantsTest {
    record User(int energy, Set<String> tags, float scale) implements AbilityUser {
        public int cooldown(String k) { return 0; }
        public int forms() { return 6; }
        public boolean has(String t) { return tags.contains(t); }
        public boolean canAct() { return true; }
        public float costScale(Variant v) { return scale; }
    }

    static final User GOJO = new User(10_000, Set.of("crb:six_eyes", "crb:blue_teleport"), 1f);
    static final User STUDENT = new User(1000, Set.of(), 1f);

    static String form(Ability a, Modifiers m, User u) {
        var r = VariantResolver.resolve(a, m, u, true);
        return r.success() ? r.variant().orElseThrow().form() : r.failure().name();
    }

    static Modifiers look(boolean sneak, boolean air, float pitch) {
        return Modifiers.fromPitch(sneak, air, pitch);
    }

    @Test
    void reversalRedUsesLookingDownNotUp() {
        Ability red = Limitless.reversalRed();
        assertEquals("form3", form(red, look(false, false, 10), GOJO), "JJA's uplook is the normal stance");
        assertEquals("form3b", form(red, look(false, false, 80), GOJO), "!uplook: looking at the ground");
        assertEquals("form3c", form(red, look(false, true, 10), GOJO));
        assertEquals("form3e", form(red, look(true, false, 10), GOJO));
    }

    @Test
    void lapseBlueSneakNeedsSixEyesUnlessLookingDown() {
        Ability blue = Limitless.lapseBlue();
        assertEquals("form2", form(blue, Modifiers.NONE, STUDENT));
        assertEquals("form2c", form(blue, look(false, true, 0), STUDENT));
        assertEquals("form2b", form(blue, look(true, false, 80), STUDENT), "Max Blue: sneak + look down");
        assertEquals("NO_VARIANT", form(blue, look(true, false, 0), STUDENT), "JJA has no sneak variant without Six Eyes");
        assertEquals("form2e", form(blue, look(true, false, 0), GOJO));
    }

    @Test
    void hollowPurpleVariantsAndEnergyRequirement() {
        Ability purple = Limitless.hollowPurple();
        assertEquals("form5", form(purple, Modifiers.NONE, GOJO));
        assertEquals("form5c", form(purple, look(false, false, 80), GOJO), "200%: look down");
        assertEquals("form5b", form(purple, look(true, false, 0), GOJO));
        assertEquals("form5d", form(purple, look(true, false, -85), GOJO), "Limitless: sneak + look straight up");
        assertEquals("NOT_ENOUGH_ENERGY", form(purple, look(true, false, -85), new User(1000, Set.of(), 1f)), "needs 7500 CE");
        assertEquals("form5d", form(purple, look(true, false, -85), new User(1000, Set.of(), 0.1f)), "Six Eyes efficiency scales the requirement");
    }

    @Test
    void infinityAndSpatialAndTeleport() {
        assertEquals("form1", form(Limitless.infinity(), Modifiers.NONE, GOJO));
        assertEquals("form1b", form(Limitless.infinity(), look(true, true, 0), GOJO));
        assertEquals("form1c", form(Limitless.infinity(), look(false, true, 0), GOJO));
        assertEquals("form4", form(Limitless.spatial(), Modifiers.NONE, GOJO));
        assertEquals("form4c", form(Limitless.spatial(), look(false, true, 0), GOJO));
        assertEquals("NO_VARIANT", form(Limitless.spatial(), look(true, true, 0), GOJO), "JJA: no sneak + airborne Spatial");
        assertEquals("gj4b", form(Limitless.blueTeleport(), look(true, false, 0), GOJO));
        assertEquals("NO_VARIANT", form(Limitless.blueTeleport(), Modifiers.NONE, STUDENT), "needs the Blue Teleport unlock");
    }
}
