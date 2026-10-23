package io.github.xthefox.crb.ability;

/**
 * Input state at the moment an ability key is pressed. JJA picks a move variant from four signals:
 * sneaking ({@code Sneaking=1..}), airborne ({@code detect_jump=1}), looking down ({@code tag=!uplook},
 * set while pitch is past 75° below level) and looking straight up ({@code tag=lookingup}, pitch
 * past 80° above level). Note JJA's {@code uplook} is true nearly all the time: it only means "not
 * looking at your feet".
 */
public record Modifiers(boolean sneak, boolean airborne, boolean lookDown, boolean lookUp) {
    public static final Modifiers NONE = new Modifiers(false, false, false, false);

    /** JJA {@code tag @a[rx=75] add uplook}: anything above this pitch is "looking down". */
    public static final float LOOK_DOWN_PITCH = 75f;
    /** JJA {@code tag @a[rx=-80] add lookingup}. */
    public static final float LOOK_UP_PITCH = -80f;

    private static final int SNEAK = 1, AIRBORNE = 2, LOOK_DOWN = 4, LOOK_UP = 8;

    public static Modifiers fromPitch(boolean sneak, boolean airborne, float pitch) {
        return new Modifiers(sneak, airborne, pitch > LOOK_DOWN_PITCH, pitch <= LOOK_UP_PITCH);
    }

    public int toBits() {
        return (sneak ? SNEAK : 0) | (airborne ? AIRBORNE : 0) | (lookDown ? LOOK_DOWN : 0) | (lookUp ? LOOK_UP : 0);
    }

    public static Modifiers fromBits(int bits) {
        return new Modifiers((bits & SNEAK) != 0, (bits & AIRBORNE) != 0, (bits & LOOK_DOWN) != 0, (bits & LOOK_UP) != 0);
    }
}
