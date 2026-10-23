package io.github.xthefox.crb.combat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Last {@value #TICKS} server ticks of every living entity's bounding box, for lag-compensated hit
 * tests: a player's swing is checked against where targets were when that player saw them.
 */
public final class HitHistory {
    public static final int TICKS = 20;
    private static final Map<LivingEntity, Ring> RINGS = new WeakHashMap<>();

    private static final class Ring {
        final AABB[] boxes = new AABB[TICKS];
        final long[] times = new long[TICKS];
    }

    private HitHistory() {}

    /** Called once per server tick per living entity. */
    public static synchronized void record(LivingEntity e, long gameTime) {
        Ring r = RINGS.computeIfAbsent(e, k -> new Ring());
        int i = (int) (gameTime % TICKS);
        r.boxes[i] = e.getBoundingBox();
        r.times[i] = gameTime;
    }

    /** The entity's box {@code ticksAgo} ticks ago, or its current box if not recorded. */
    public static synchronized AABB boxAt(LivingEntity e, long gameTime, int ticksAgo) {
        if (ticksAgo <= 0) return e.getBoundingBox();
        Ring r = RINGS.get(e);
        if (r == null) return e.getBoundingBox();
        long t = gameTime - ticksAgo;
        int i = (int) Math.floorMod(t, (long) TICKS);
        return r.times[i] == t && r.boxes[i] != null ? r.boxes[i] : e.getBoundingBox();
    }
}
