package io.github.xthefox.crb.technique.limitless;

import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.combat.CombatDamage;
import io.github.xthefox.crb.combat.DamageKind;
import io.github.xthefox.crb.combat.Hits;
import io.github.xthefox.crb.combat.Infinity;
import io.github.xthefox.crb.combat.Melee;
import io.github.xthefox.crb.combat.Movement;
import io.github.xthefox.crb.data.CRBAttachments;
import io.github.xthefox.crb.data.CombatState;
import io.github.xthefox.crb.move.CuePositions;
import io.github.xthefox.crb.move.MoveEngine;
import io.github.xthefox.crb.move.MoveInstance;
import io.github.xthefox.crb.move.MoveLogic;
import io.github.xthefox.crb.move.MoveLogics;
import io.github.xthefox.crb.progression.Traits;
import io.github.xthefox.crb.projectile.HelperEntities;
import io.github.xthefox.crb.projectile.Projectiles;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/**
 * Java logic for the Limitless moves whose JJA behaviour lives in helper functions the extractor
 * cannot follow ({@code limitless/blitz}, {@code limitless/warp}) or in scoreboard state
 * ({@code infinity}). Timings are JJA's: a move's countdown value {@code move=N} is elapsed tick
 * {@code duration - N}.
 */
final class LimitlessMoves {
    private LimitlessMoves() {}

    static void register() {
        // form1: `scoreboard players set @s infinity 300` + Energy at move=5 (of 15)
        logic("limitless1", (m, t) -> {
            if (t == 10) Infinity.cast(m.caster());
        });
        // form1b Infinite Pressure: Infinity held up (move=29..75) while everything within 5 is pushed out (move=24..74)
        logic("limitless1b", (m, t) -> {
            CombatState s = m.caster().getData(CRBAttachments.COMBAT);
            if (t >= 6 && t <= 52 && s.infinity() < 5) s.infinity(5);
            if (t >= 7 && t <= 57) pushAll(m.caster(), 5);
        });
        logic("limitless4", LimitlessMoves::blitz);
        logic("limitless4c", LimitlessMoves::warp);
        logic("blue_teleport", (m, t) -> {
            if (t == 0) blinkForward(m.caster(), range(m.caster()));
        });
        logic("blue_teleport_chain", (m, t) -> {
            if (t == 0 || t == 8 || t == 16) blinkForward(m.caster(), 10);
        });
        logic("blue_teleport_behind", (m, t) -> {
            if (t != 0) return;
            LivingEntity target = lookTarget(m.caster(), range(m.caster()) * 1.5);
            if (target == null) blinkForward(m.caster(), range(m.caster()));
            else blinkBehind(m.caster(), target);
        });
    }

    static void logic(String path, MoveLogic l) {
        MoveLogics.register(CRB.id(Limitless.move(path)), l);
    }

    /** Six Eyes doubles Blue Teleport's reach. */
    static double range(LivingEntity e) {
        return e.hasData(CRBAttachments.SORCERER) && e.getData(CRBAttachments.SORCERER).has(Traits.SIX_EYES) ? 64 : 32;
    }

    // ------------------------------------------------------------------ Spatial

    /**
     * JJA {@code limitless/blitz} at move=5: a 0.33-block march from the eyes (anchored eyes,
     * {@code ^^-0.4^1}) through air and fire, up to 25 blocks; the first target within 2.5 takes
     * 8 + 8 damage (Bedrock's hurt cooldown keeps one 8) and a 15-tick stun.
     */
    static void blitz(MoveInstance m, int t) {
        if (t != 11) return;
        LivingEntity c = m.caster();
        Vec3 p = CuePositions.local(c.getEyePosition(), c.getYRot(), c.getXRot(), 0, -0.4, 1);
        Vec3 step = c.getLookAngle().scale(0.33);
        for (int i = 0; i < 76 && p.distanceTo(c.getEyePosition()) <= 25; i++, p = p.add(step)) {
            BlockState s = c.level().getBlockState(BlockPos.containing(p));
            if (!s.isAir() && !s.is(BlockTags.FIRE)) return;
            List<LivingEntity> hit = Hits.sphere(c, p, 2.5);
            if (!hit.isEmpty()) {
                CombatDamage.deal(c, hit.getFirst(), new CombatDamage.Hit(8 + CombatDamage.strengthBonus(c), DamageKind.FIRE, 15, 1, 3));
                return;
            }
        }
    }

    /**
     * JJA {@code limitless4c} Warp: at move=25 a 5-block-step search up to 50 blocks tags the
     * target; for move=15..25 Gojo is held at {@code ^0.3 ^0.4 ^-1} of the target (behind it,
     * facing it); at move=15 the kick lands (8 + Strength, stun 20, ds:knockback). No target:
     * the move ends (JJA sets move to 3).
     */
    static void warp(MoveInstance m, int t) {
        LivingEntity c = m.caster();
        if (t == 11) {
            LivingEntity target = null;
            Vec3 look = c.getLookAngle();
            Vec3 p = c.getEyePosition().add(look.scale(5.05));
            for (int i = 0; i < 10 && target == null; i++, p = p.add(look.scale(5))) {
                List<LivingEntity> near = Hits.sphere(c, p, 4.99);
                if (!near.isEmpty()) target = near.getFirst();
            }
            if (target == null) {
                MoveEngine.end(c, MoveLogic.EndReason.CANCELLED);
                return;
            }
            m.put("warp", target.getId());
            m.tag("warp", true);
        }
        Integer id = m.get("warp", null);
        if (id == null || t < 11 || t > 21) return;
        if (!(c.level().getEntity(id) instanceof LivingEntity target) || !target.isAlive()) {
            MoveEngine.end(c, MoveLogic.EndReason.CANCELLED);
            return;
        }
        Vec3 behind = CuePositions.local(target.position(), target.getYRot(), target.getXRot(), 0.3, 0.4, -1);
        teleport(c, behind, target.getEyePosition());
        if (t == 21) {
            CombatDamage.deal(c, target, new CombatDamage.Hit(8 + CombatDamage.strengthBonus(c), DamageKind.BLUNT, 20, 3, 8));
            if (c.level() instanceof ServerLevel sl) HelperEntities.INSTANCE.apply(sl, "ds:knockback", c.position(), c, c);
        }
    }

    // ------------------------------------------------------------------ Blue Teleport (GJ4)

    /** Blink to the crosshair: the first block hit (or {@code range} blocks), stepping back until Gojo fits. */
    static void blinkForward(LivingEntity c, double range) {
        Vec3 from = c.getEyePosition();
        Vec3 to = from.add(c.getLookAngle().scale(range));
        var hit = c.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, c));
        Vec3 end = hit.getType() == HitResult.Type.MISS ? to : hit.getLocation();
        Vec3 back = c.getLookAngle().scale(-0.5);
        Vec3 feet = end.subtract(0, c.getEyeHeight(), 0);
        for (int i = 0; i < 12; i++, feet = feet.add(back)) {
            if (fits(c, feet)) {
                teleport(c, feet, null);
                return;
            }
            Vec3 up = feet.add(0, 1, 0);
            if (fits(c, up)) {
                teleport(c, up, null);
                return;
            }
        }
    }

    /** Blink behind a target, facing it. */
    static void blinkBehind(LivingEntity c, LivingEntity target) {
        Vec3 dir = Vec3.directionFromRotation(0, target.getYRot());
        Vec3 spot = target.position().subtract(dir.scale(1.5));
        if (!fits(c, spot)) spot = target.position().add(dir.scale(1.5));
        if (!fits(c, spot)) spot = target.position().add(0, target.getBbHeight(), 0);
        teleport(c, spot, target.getEyePosition());
    }

    static LivingEntity lookTarget(LivingEntity c, double range) {
        Vec3 from = c.getEyePosition(), look = c.getLookAngle();
        var block = c.level().clip(new ClipContext(from, from.add(look.scale(range)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, c));
        double max = block.getType() == HitResult.Type.MISS ? range : block.getLocation().distanceTo(from);
        LivingEntity best = null;
        double bestAlong = Double.MAX_VALUE;
        AABB area = c.getBoundingBox().expandTowards(look.scale(max)).inflate(2);
        for (LivingEntity e : c.level().getEntitiesOfClass(LivingEntity.class, area, e -> e != c && e.isAlive() && !e.isSpectator())) {
            var clip = e.getBoundingBox().inflate(1.0).clip(from, from.add(look.scale(max)));
            if (clip.isEmpty()) continue;
            double along = clip.get().distanceTo(from);
            if (along < bestAlong) {
                bestAlong = along;
                best = e;
            }
        }
        return best;
    }

    static boolean fits(LivingEntity c, Vec3 feet) {
        AABB box = c.getDimensions(c.getPose()).makeBoundingBox(feet);
        return c.level().noCollision(c, box) && !c.level().containsAnyLiquid(box);
    }

    /** Moves Gojo with Lapse Blue implosions at both ends. {@code faceTo} may be null (keep the view). */
    static void teleport(LivingEntity c, Vec3 feet, Vec3 faceTo) {
        boolean far = c.position().distanceToSqr(feet) > 4;
        if (far) effects(c);
        float yaw = c.getYRot(), pitch = c.getXRot();
        if (faceTo != null) {
            Vec3 d = faceTo.subtract(feet.add(0, c.getEyeHeight(), 0));
            yaw = Projectiles.yawOf(d);
            pitch = Projectiles.pitchOf(d);
        }
        if (c instanceof ServerPlayer sp) {
            sp.teleportTo(sp.serverLevel(), feet.x, feet.y, feet.z, Set.of(), yaw, pitch);
        } else {
            c.moveTo(feet.x, feet.y, feet.z, yaw, pitch);
            c.setYHeadRot(yaw);
        }
        c.setDeltaMovement(Vec3.ZERO);
        c.fallDistance = 0;
        c.getData(CRBAttachments.COMBAT).iframes(4);
        if (far) effects(c);
    }

    static void effects(LivingEntity c) {
        Melee.cue(c, Movement.particle("ds:spatial_warp1"));
        Melee.cue(c, Movement.particle("ds:spatial_warp2"));
        Melee.cue(c, Movement.particle("ds:lapse_pull3"));
        Melee.cue(c, Melee.sound("beacon.deactivate", 1, 1.6f));
    }

    /** Pushes everything within {@code r} away from the caster (Infinite Pressure). */
    static void pushAll(LivingEntity c, double r) {
        for (Entity e : c.level().getEntities(c, c.getBoundingBox().inflate(r))) {
            if (!(e instanceof LivingEntity l) || !l.isAlive() || l.isSpectator()) continue;
            if (l instanceof Player p && p.isCreative()) continue;
            if (l.distanceTo(c) > r) continue;
            Infinity.push(c, l);
        }
    }
}
