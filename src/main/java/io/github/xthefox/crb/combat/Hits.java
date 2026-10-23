package io.github.xthefox.crb.combat;

import io.github.xthefox.crb.core.CRBConfig;
import io.github.xthefox.crb.data.CRBAttachments;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Server-side hit tests with latency rewind (research doc 05). */
public final class Hits {
    private Hits() {}

    /** Ticks to rewind targets for an attacker: their latency, capped by config. NPCs get 0. */
    public static int rewindTicks(LivingEntity attacker) {
        if (!(attacker instanceof ServerPlayer sp)) return 0;
        int ticks = Math.round(sp.connection.latency() / 50f);
        return Math.max(0, Math.min(ticks, CRBConfig.SERVER.lagCompensationTicks.get()));
    }

    /**
     * Living entities whose (rewound) bounding box comes within {@code radius} of {@code center}.
     * Excludes the attacker, spectators, creative players and anything with i-frames (JJA's
     * {@code tag=!Frames}).
     */
    public static List<LivingEntity> sphere(LivingEntity attacker, Vec3 center, double radius) {
        int rewind = rewindTicks(attacker);
        long now = attacker.level().getGameTime();
        double r2 = radius * radius;
        AABB search = new AABB(center, center).inflate(radius + 3);
        return attacker.level().getEntitiesOfClass(LivingEntity.class, search, e -> {
            if (e == attacker || !e.isAlive() || e.isSpectator()) return false;
            if (e instanceof Player p && p.isCreative()) return false;
            if (e.hasData(CRBAttachments.COMBAT) && e.getData(CRBAttachments.COMBAT).invulnerable()) return false;
            return distanceSq(HitHistory.boxAt(e, now, rewind), center) <= r2;
        });
    }

    static double distanceSq(AABB box, Vec3 p) {
        double dx = Math.max(Math.max(box.minX - p.x, 0), p.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - p.y, 0), p.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - p.z, 0), p.z - box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }
}
