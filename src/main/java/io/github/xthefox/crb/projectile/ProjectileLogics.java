package io.github.xthefox.crb.projectile;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Registry of {@link ProjectileLogic} by JJA projectile name ("Lapse2", "HollowP"). */
public final class ProjectileLogics {
    private static final Map<String, ProjectileLogic> LOGICS = new ConcurrentHashMap<>();

    private ProjectileLogics() {}

    public static void register(String name, ProjectileLogic logic) {
        LOGICS.put(name, logic);
    }

    public static ProjectileLogic get(String name) {
        return LOGICS.getOrDefault(name, ProjectileLogic.NONE);
    }
}
