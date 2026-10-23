package io.github.xthefox.crb.move;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Registry of Java move logic, keyed by move id (e.g. {@code crb:limitless/limitless5}). */
public final class MoveLogics {
    private static final Map<ResourceLocation, MoveLogic> LOGIC = new ConcurrentHashMap<>();

    private MoveLogics() {}

    public static void register(ResourceLocation moveId, MoveLogic logic) {
        if (LOGIC.putIfAbsent(moveId, logic) != null) {
            throw new IllegalStateException("Duplicate move logic for " + moveId);
        }
    }

    public static MoveLogic get(ResourceLocation moveId) {
        return LOGIC.getOrDefault(moveId, MoveLogic.NONE);
    }

    public static boolean has(ResourceLocation moveId) {
        return LOGIC.containsKey(moveId);
    }
}
