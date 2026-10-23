package io.github.xthefox.crb.projectile;

import io.github.xthefox.crb.data.CRBAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * World tests from projectile runners, as extracted by {@code tools/jja/timelines.py}:
 * {@code w:solid} ({@code unless block ~~~ air}), {@code w:air}, {@code w:near=R}
 * ({@code if entity @e[r=R,tag=!Frames,...]}) and {@code w:far=R}. Shared by the server and the
 * client so contact effects line up.
 */
public final class WorldFacts {
    private WorldFacts() {}

    public static boolean eval(Level level, Vec3 pos, String fact, Entity self, Entity owner) {
        if (fact.equals("w:solid") || fact.equals("w:air")) {
            BlockState s = level.getBlockState(BlockPos.containing(pos));
            boolean solid = !s.isAir() && !s.is(BlockTags.FIRE);
            return fact.equals("w:solid") == solid;
        }
        if (fact.startsWith("w:near=") || fact.startsWith("w:far=")) {
            double r = Double.parseDouble(fact.substring(fact.indexOf('=') + 1));
            boolean near = anyTarget(level, pos, r, self, owner);
            return fact.startsWith("w:near=") == near;
        }
        return false;
    }

    static boolean anyTarget(Level level, Vec3 pos, double r, Entity self, Entity owner) {
        double r2 = r * r;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(pos, pos).inflate(r))) {
            if (e == owner || e == self || !e.isAlive() || e.isSpectator()) continue;
            if (e instanceof Player p && p.isCreative()) continue;
            if (e.hasData(CRBAttachments.COMBAT) && e.getData(CRBAttachments.COMBAT).invulnerable()) continue;
            if (e.position().distanceToSqr(pos) <= r2) return true;
        }
        return false;
    }
}
