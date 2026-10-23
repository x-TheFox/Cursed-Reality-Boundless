package io.github.xthefox.crb.molang;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;

/**
 * Evaluation state: variable slots plus a random source. Slots are global (one table for all
 * expressions), so a context is a plain array and lookups are index reads.
 */
public final class MolangContext {
    private static final Map<String, Integer> SLOTS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT = new AtomicInteger();

    // well-known particle variables
    public static final int EMITTER_AGE = slot("variable.emitter_age");
    public static final int EMITTER_LIFETIME = slot("variable.emitter_lifetime");
    public static final int EMITTER_RANDOM_1 = slot("variable.emitter_random_1");
    public static final int EMITTER_RANDOM_2 = slot("variable.emitter_random_2");
    public static final int EMITTER_RANDOM_3 = slot("variable.emitter_random_3");
    public static final int EMITTER_RANDOM_4 = slot("variable.emitter_random_4");
    public static final int PARTICLE_AGE = slot("variable.particle_age");
    public static final int PARTICLE_LIFETIME = slot("variable.particle_lifetime");
    public static final int PARTICLE_RANDOM_1 = slot("variable.particle_random_1");
    public static final int PARTICLE_RANDOM_2 = slot("variable.particle_random_2");
    public static final int PARTICLE_RANDOM_3 = slot("variable.particle_random_3");
    public static final int PARTICLE_RANDOM_4 = slot("variable.particle_random_4");
    public static final int ENTITY_SCALE = slot("variable.entity_scale");

    private double[] values = new double[64];
    private boolean[] set = new boolean[64];
    private final RandomGenerator random;
    /** Render partial tick for {@code query.frame_alpha}. */
    public double frameAlpha;

    public MolangContext(RandomGenerator random) {
        this.random = random;
    }

    public static int slot(String name) {
        return SLOTS.computeIfAbsent(name, n -> NEXT.getAndIncrement());
    }

    static Molang.Expr query(String name) {
        return switch (name) {
            case "query.frame_alpha" -> ctx -> ctx.frameAlpha;
            case "query.anim_time", "query.life_time" -> ctx -> ctx.get(PARTICLE_AGE);
            default -> null;
        };
    }

    public RandomGenerator random() {
        return random;
    }

    private void ensure(int slot) {
        if (slot >= values.length) {
            int n = Math.max(slot + 1, values.length * 2);
            values = Arrays.copyOf(values, n);
            set = Arrays.copyOf(set, n);
        }
    }

    public double get(int slot) {
        return slot < values.length ? values[slot] : 0;
    }

    public boolean isSet(int slot) {
        return slot < set.length && set[slot];
    }

    public void set(int slot, double v) {
        ensure(slot);
        values[slot] = v;
        set[slot] = true;
    }

    /** Copies all variables from another context (particles inherit emitter variables). */
    public void copyFrom(MolangContext other) {
        ensure(other.values.length - 1);
        System.arraycopy(other.values, 0, values, 0, other.values.length);
        System.arraycopy(other.set, 0, set, 0, other.set.length);
    }
}
