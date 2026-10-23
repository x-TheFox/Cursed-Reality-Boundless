package io.github.xthefox.crb.technique.limitless;

import io.github.xthefox.crb.combat.Infinity;
import io.github.xthefox.crb.combat.TargetTags;
import io.github.xthefox.crb.data.CRBAttachments;
import io.github.xthefox.crb.projectile.Carve;
import io.github.xthefox.crb.projectile.ProjectileLogics;
import io.github.xthefox.crb.projectile.TechniqueProjectile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Limitless projectile behaviour beyond the extracted lines (hits, stuns, explosions, carving and
 * contact rules come from {@code data/crb/projectiles.json}).
 */
final class LimitlessProjectiles {
    /** JJA {@code lapseblue2}: everything within 22 is drawn in 0.3/tick, within 13 another 0.26 + 0.12. */
    static final double PULL_RADIUS = 22, INNER_RADIUS = 13;
    static final double PULL_STEP = 0.3, INNER_STEP = 0.262 + 0.12;
    /** JJA: {@code kill @e[type=item,r=7]}. */
    static final double ITEM_CRUSH_RADIUS = 7;

    private LimitlessProjectiles() {}

    static void register() {
        ProjectileLogics.register("Lapse2", LimitlessProjectiles::lapse);
    }

    static void lapse(TechniqueProjectile p, int d, LivingEntity owner) {
        if (d < 1 || !(p.level() instanceof ServerLevel level)) return;
        Vec3 c = p.position();
        List<Entity> around = level.getEntities(p, new AABB(c, c).inflate(PULL_RADIUS));
        for (Entity e : around) {
            if (e == owner || e instanceof TechniqueProjectile) continue;
            double dist = e.position().distanceTo(c);
            if (dist > PULL_RADIUS) continue;
            if ((e instanceof ItemEntity || e instanceof ExperienceOrb) && dist <= ITEM_CRUSH_RADIUS) {
                e.discard();
                continue;
            }
            if (e instanceof LivingEntity l && !pullable(l)) continue;
            double step = PULL_STEP + (dist <= INNER_RADIUS ? INNER_STEP : 0);
            if (dist < 0.3) continue;
            Vec3 delta = c.subtract(e.position()).normalize().scale(Math.min(step, dist));
            if (e instanceof Player pl) {
                pl.setDeltaMovement(delta);
                pl.hurtMarked = true;
                pl.fallDistance = 0;
            } else if (e instanceof LivingEntity l) {
                l.move(MoverType.SELF, delta);
                l.fallDistance = 0;
            } else {
                e.setPos(e.getX() + delta.x, e.getY() + delta.y, e.getZ() + delta.z);
            }
        }
        // JJA drains water and lava in a growing cube (r 3 at the start, 6 at the end)
        if (d <= 35 && p.age() % 2 == 0) {
            int r = d >= 31 ? 3 : d >= 21 ? 4 : d >= 11 ? 5 : 6;
            Carve.drain(level, c, r);
        }
    }

    static boolean pullable(LivingEntity l) {
        if (!l.isAlive() || l.isSpectator()) return false;
        if (l instanceof Player pl && pl.isCreative()) return false;
        if (l.hasData(CRBAttachments.COMBAT) && l.getData(CRBAttachments.COMBAT).invulnerable()) return false;
        if (Infinity.active(l)) return false;
        return TargetTags.test(List.of("tag=!pull_adapted"), l);
    }
}
