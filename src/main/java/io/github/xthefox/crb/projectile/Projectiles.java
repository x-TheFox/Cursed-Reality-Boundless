package io.github.xthefox.crb.projectile;

import com.google.gson.JsonArray;
import io.github.xthefox.crb.move.CuePositions;
import io.github.xthefox.crb.move.SimCue;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Projectile and helper-entity cues shared by caster moves ({@link io.github.xthefox.crb.move.MoveSim})
 * and projectile runners ({@link ProjectileSim}): {@code summon}, {@code shoot},
 * {@code proj_deleter}, {@code proj_tp}, {@code proj_kill}, {@code carve}.
 *
 * <p>JJA finds projectiles by name across the whole world ({@code @e[name=HollowP,c=1]}); here a
 * lookup only sees projectiles of the same owner, so two Gojos never steer each other's Purple.
 */
public final class Projectiles {
    /** JJA's shootprojectile finds an existing projectile within this distance. */
    static final double FIND_RANGE = 64;

    private Projectiles() {}

    /**
     * @param owner  who the projectile or explosion belongs to
     * @param anchor the executing entity (the caster, or a projectile running its tick function)
     */
    public static void apply(SimCue c, LivingEntity owner, Entity anchor, Vec3 feet, float eye, float yaw, float pitch) {
        if (!(anchor.level() instanceof ServerLevel level)) return;
        switch (c.type()) {
            case "summon" -> summon(level, c, owner, anchor, feet, eye, yaw, pitch);
            case "shoot" -> shoot(level, c, owner, anchor);
            case "proj_deleter" -> find(level, owner, c.str("name", ""), anchor.position()).ifPresent(p -> p.setRemaining((int) c.num("value", 0)));
            case "proj_tp" -> find(level, owner, c.str("name", ""), anchor.position()).ifPresent(p -> {
                Vec3 to = CuePositions.resolve(c.array("at"), feet, eye, yaw, pitch);
                p.setPos(to.x, to.y, to.z);
            });
            case "proj_kill" -> all(level, owner, c.str("name", ""), anchor.position()).forEach(Entity::discard);
            case "carve" -> Carve.sphere(level, CuePositions.resolve(c.array("at"), feet, eye, yaw, pitch), c.num("radius", 1));
            default -> { }
        }
    }

    static void summon(ServerLevel level, SimCue c, LivingEntity owner, Entity anchor, Vec3 feet, float eye, float yaw, float pitch) {
        String entity = c.str("entity", "").toLowerCase(Locale.ROOT);
        Vec3 at = CuePositions.resolve(c.array("at"), feet, eye, yaw, pitch);
        if (entity.equals("ds:projectile")) {
            String name = c.str("name", "");
            if (name.isEmpty()) return;
            float pYaw = yaw, pPitch = pitch;
            JsonArray facing = c.array("facing_at");
            if (!facing.isEmpty()) {
                Vec3 look = CuePositions.resolve(facing, feet, eye, yaw, pitch).subtract(at);
                if (look.lengthSqr() > 1e-6) {
                    pYaw = yawOf(look);
                    pPitch = pitchOf(look);
                }
            }
            TechniqueProjectile.spawn(level, name, owner, at, pYaw, pPitch);
        } else {
            HelperEntities.INSTANCE.apply(level, entity, at, owner, anchor);
        }
    }

    /** JJA {@code scriptevent ds:shootprojectile}: from the feet + 0.1 along the view, {@code velocity} blocks per tick. */
    static void shoot(ServerLevel level, SimCue c, LivingEntity owner, Entity shooter) {
        Vec3 view = shooter.getViewVector(1f);
        Vec3 v = view.scale(c.num("velocity", 3));
        String name = c.str("name", "");
        if (c.data().has("existing")) {
            find(level, owner, name, shooter.position()).ifPresent(p -> p.launch(v));
            return;
        }
        Vec3 at = shooter.position().add(view.x * 0.1, 0.1 + view.y * 0.1, view.z * 0.1);
        TechniqueProjectile p = TechniqueProjectile.spawn(level, name, owner, at, yawOf(view), pitchOf(view));
        if (c.data().has("deleter") && !c.data().get("deleter").isJsonNull()) p.setRemaining(c.data().get("deleter").getAsInt());
        p.launch(v);
    }

    /** Nearest projectile with this name and owner. */
    public static Optional<TechniqueProjectile> find(ServerLevel level, LivingEntity owner, String name, Vec3 near) {
        return all(level, owner, name, near).stream().min(Comparator.comparingDouble(p -> p.distanceToSqr(near)));
    }

    public static List<TechniqueProjectile> all(ServerLevel level, LivingEntity owner, String name, Vec3 near) {
        return level.getEntitiesOfClass(TechniqueProjectile.class, new AABB(near, near).inflate(FIND_RANGE),
                p -> p.isAlive() && p.name().equals(name) && (owner == null || p.ownedBy(owner)));
    }

    /** {@code tp @s ^^^step facing @e[name=X]}: steps {@code self} toward a named projectile and faces it. */
    public static void moveToward(Entity self, LivingEntity owner, String target, double step) {
        if (!(self.level() instanceof ServerLevel level) || target.isEmpty()) return;
        find(level, owner, target, self.position()).filter(p -> p != self).ifPresent(p -> {
            Vec3 d = p.position().subtract(self.position());
            if (d.lengthSqr() < 1e-8) return;
            float yaw = yawOf(d), pitch = pitchOf(d);
            Vec3 delta = d.normalize().scale(Math.min(step, d.length()));
            if (self instanceof Player pl) {
                pl.setDeltaMovement(delta);
                pl.hurtMarked = true;
            } else if (self instanceof LivingEntity l) {
                l.setYRot(yaw);
                l.setXRot(pitch);
                l.move(MoverType.SELF, delta);
            } else {
                self.setYRot(yaw);
                self.setXRot(pitch);
                self.setPos(self.getX() + delta.x, self.getY() + delta.y, self.getZ() + delta.z);
            }
        });
    }

    /** Minecraft yaw for a direction (same as JJA's {@code atan2(-x, z)}). */
    public static float yawOf(Vec3 v) {
        return (float) (Mth.atan2(-v.x, v.z) * Mth.RAD_TO_DEG);
    }

    public static float pitchOf(Vec3 v) {
        double len = v.length();
        return len < 1e-8 ? 0 : (float) (Math.asin(Mth.clamp(-v.y / len, -1, 1)) * Mth.RAD_TO_DEG);
    }
}
