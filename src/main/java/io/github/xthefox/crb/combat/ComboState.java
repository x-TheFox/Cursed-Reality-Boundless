package io.github.xthefox.crb.combat;

/**
 * Transient melee/movement state per entity, replacing JJA's {@code BasicM1}, {@code BasicM1s},
 * {@code M1Reset}, {@code Disabled}, {@code Evade}, {@code DashCD}, {@code doublejumpCD} scores.
 */
public final class ComboState {
    public static final int EVADE_MAX = 100;

    int m1Timer;          // counts down from the swing's windup; the hit lands at HIT_AT
    int m1Count;          // 1..4 within the current string
    int m1Reset;          // string resets to 0 when this runs out
    int disabled;         // end-lag after the 4th hit
    boolean blackFlashPending;
    int evade;            // filled by taking hits; full meter lets a stunned player Evade
    int dashCooldown;
    int dashTicks;
    double dashX, dashZ, dashStrength;
    boolean doubleJumpUsed;
    int doubleJumpCooldown;

    public int evade() { return evade; }
    public int m1Count() { return m1Count; }
    public boolean swinging() { return m1Timer > 0; }

    public void addEvade(int n) {
        evade = Math.min(EVADE_MAX, evade + n);
    }

    public void resetString() {
        m1Timer = 0;
        m1Count = 0;
        m1Reset = 0;
        blackFlashPending = false;
    }
}
