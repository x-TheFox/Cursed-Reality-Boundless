package io.github.xthefox.crb.combat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.data.CRBAttachments;
import io.github.xthefox.crb.data.CombatState;
import io.github.xthefox.crb.move.MoveDurations;
import io.github.xthefox.crb.move.MoveEngine;
import io.github.xthefox.crb.net.MovePayloads;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Movement techniques: dash (JJA {@code dash}: 5 i-frames, impulse 2/3/4 by agility for 2-4
 * ticks, cooldown 60 − agility), Evade (JJA {@code evade}: breaks a combo when the meter filled by
 * taking hits is full) and double jump (JJA {@code _self_cmds}: once per airtime).
 */
public final class Movement {
    static final ResourceLocation DASH = CRB.id("dash"), EVADE = CRB.id("evade");
    static final int DASH_IFRAMES = 5, EVADE_IFRAMES = 20;

    private Movement() {}

    public static void handle(ServerPlayer p, MovePayloads.Movement m) {
        switch (m.kind()) {
            case MovePayloads.Movement.DASH -> dash(p, m.dir());
            case MovePayloads.Movement.EVADE -> evade(p);
            case MovePayloads.Movement.DOUBLE_JUMP -> doubleJump(p);
            default -> { }
        }
    }

    static int agility(LivingEntity e) {
        return e.hasData(CRBAttachments.SORCERER) ? e.getData(CRBAttachments.SORCERER).agility() : 0;
    }

    static void dash(ServerPlayer p, int dir) {
        CombatState s = p.getData(CRBAttachments.COMBAT);
        ComboState c = p.getData(CRBAttachments.COMBO);
        if (c.dashCooldown > 0 || s.stunned() || s.movementLocked() || MoveEngine.isBusy(p)) return;
        int agi = agility(p);
        float yaw = p.getYRot() * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw), lx = fz, lz = -fx;
        double x = 0, z = 0;
        if ((dir & 1) != 0) { x += fx; z += fz; }
        if ((dir & 2) != 0) { x -= fx; z -= fz; }
        if ((dir & 4) != 0) { x += lx; z += lz; }
        if ((dir & 8) != 0) { x -= lx; z -= lz; }
        double len = Math.sqrt(x * x + z * z);
        if (len < 1e-4) { x = fx; z = fz; len = 1; }
        c.dashX = x / len;
        c.dashZ = z / len;
        c.dashStrength = agi >= 50 ? 4 : agi >= 25 ? 3 : 2;
        c.dashTicks = agi >= 50 ? 4 : agi >= 25 ? 3 : 2;
        c.dashCooldown = Math.max(10, 60 - agi);
        s.iframes(DASH_IFRAMES);
        MoveEngine.start(p, DASH, MoveDurations.INSTANCE.get(DASH).orElse(12));
    }

    static void evade(ServerPlayer p) {
        CombatState s = p.getData(CRBAttachments.COMBAT);
        ComboState c = p.getData(CRBAttachments.COMBO);
        if (!s.stunned() || c.evade < ComboState.EVADE_MAX) return;
        c.evade = 0;
        c.resetString();
        s.clearStun();
        s.iframes(EVADE_IFRAMES);
        Vec3 back = Vec3.directionFromRotation(0, p.getYRot()).scale(-2 * CombatDamage.KNOCKBACK_SCALE);
        p.setDeltaMovement(back.x, 0.35, back.z);
        p.hurtMarked = true;
        MoveEngine.start(p, EVADE, MoveDurations.INSTANCE.get(EVADE).orElse(21));
    }

    static void doubleJump(ServerPlayer p) {
        ComboState c = p.getData(CRBAttachments.COMBO);
        CombatState s = p.getData(CRBAttachments.COMBAT);
        if (p.onGround() || c.doubleJumpUsed || c.doubleJumpCooldown > 0 || s.stunned() || s.movementLocked()
                || p.getAbilities().flying || p.isInWater()) return;
        c.doubleJumpUsed = true;
        Vec3 v = p.getDeltaMovement();
        p.setDeltaMovement(v.x * 1.2, 0.62, v.z * 1.2);
        p.fallDistance = 0;
        p.hurtMarked = true;
        Melee.cue(p, Melee.anim("animation.double_jump"));
        Melee.cue(p, Melee.sound("mob.blaze.shoot", 1, 2));
        for (int i = 1; i <= 4; i++) Melee.cue(p, particle("ds:double_jump" + i));
        JsonObject shake = new JsonObject();
        shake.addProperty("type", "shake");
        shake.addProperty("audience", "radius:5");
        shake.addProperty("intensity", 0.1);
        shake.addProperty("seconds", 0.1);
        shake.addProperty("mode", "rotational");
        Melee.cue(p, shake);
    }

    public static JsonObject particle(String id) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "particle");
        o.addProperty("particle", id);
        o.add("at", new JsonArray());
        return o;
    }

    /** Applies the dash impulse over its ticks and resets the double jump on landing. */
    static void tick(LivingEntity e, ComboState c) {
        if (c.dashTicks > 0) {
            c.dashTicks--;
            Vec3 v = e.getDeltaMovement();
            double sp = c.dashStrength * CombatDamage.KNOCKBACK_SCALE;
            e.setDeltaMovement(c.dashX * sp, Math.min(v.y, 0.1), c.dashZ * sp);
            e.hurtMarked = true;
        }
        if (e.onGround() && c.doubleJumpUsed) {
            c.doubleJumpUsed = false;
            c.doubleJumpCooldown = 4;
        }
    }
}
