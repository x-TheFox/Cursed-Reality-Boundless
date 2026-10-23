package io.github.xthefox.crb.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnergyRulesTest {
    static EnergyRules.Context ctx(int cap, boolean busy, boolean hr, boolean sixEyes, boolean faster, int sneak, long time) {
        return new EnergyRules.Context(cap, busy, false, false, hr, sixEyes, faster, sneak, time);
    }

    @Test
    void passiveRegenFillsCapInNinetySeconds() {
        // 300 cap → ceil(300/90) = 4 per second
        assertEquals(4, EnergyRules.gainThisTick(ctx(300, false, false, false, false, 0, 20)));
        assertEquals(0, EnergyRules.gainThisTick(ctx(300, false, false, false, false, 0, 21)));
    }

    @Test
    void chargeRateScalesWithCap() {
        assertEquals(1, EnergyRules.gainThisTick(ctx(100, false, false, false, false, 5, 1)));
        assertEquals(3, EnergyRules.gainThisTick(ctx(300, false, false, false, false, 5, 1)));
        assertEquals(8, EnergyRules.gainThisTick(ctx(1000, false, false, false, false, 5, 1)));
        assertEquals(10, EnergyRules.gainThisTick(ctx(1000, false, false, false, true, 5, 1)));
        assertEquals(0, EnergyRules.gainThisTick(ctx(1000, false, false, false, false, 4, 1)), "needs 5 sneak ticks");
    }

    @Test
    void sixEyesBonus() {
        assertEquals(5, EnergyRules.gainThisTick(ctx(100, false, false, true, false, 0, 2)));
        assertEquals(1 + 7, EnergyRules.gainThisTick(ctx(100, false, false, true, false, 5, 2)));
    }

    @Test
    void heavenlyRestrictionAndBusyBlockRegen() {
        assertEquals(0, EnergyRules.gainThisTick(ctx(300, false, true, false, false, 10, 20)));
        assertEquals(0, EnergyRules.gainThisTick(ctx(300, true, false, true, false, 10, 20)));
    }
}
