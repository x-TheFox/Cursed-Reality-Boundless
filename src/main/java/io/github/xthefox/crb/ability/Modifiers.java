package io.github.xthefox.crb.ability;

/**
 * Input state at the moment an ability key is pressed. JJA picks a move variant from the same
 * three signals: sneaking ({@code Sneaking=1..}), airborne ({@code detect_jump=1}) and looking up
 * ({@code tag=uplook}, pitch above the horizon).
 */
public record Modifiers(boolean sneak, boolean airborne, boolean lookUp) {
    public static final Modifiers NONE = new Modifiers(false, false, false);

    /** JJA tags {@code uplook} when the player looks more than this many degrees above level. */
    public static final float LOOK_UP_PITCH = -35f;

    private static final int SNEAK = 1, AIRBORNE = 2, LOOK_UP = 4;

    public static Modifiers fromPitch(boolean sneak, boolean airborne, float pitch) {
        return new Modifiers(sneak, airborne, pitch <= LOOK_UP_PITCH);
    }

    public int toBits() {
        return (sneak ? SNEAK : 0) | (airborne ? AIRBORNE : 0) | (lookUp ? LOOK_UP : 0);
    }

    public static Modifiers fromBits(int bits) {
        return new Modifiers((bits & SNEAK) != 0, (bits & AIRBORNE) != 0, (bits & LOOK_UP) != 0);
    }
}
