package io.github.xthefox.crb.combat;

import io.github.xthefox.crb.data.CRBAttachments;
import io.github.xthefox.crb.data.CombatState;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Applies JJA-style damage: armour-bypassing damage attributed to the attacker, stun, i-frame
 * respect, and JJA's hit reaction (victim turned toward the attacker and pushed back).
 */
public final class CombatDamage {
    /** Bedrock applyKnockback strength → blocks/tick. */
    public static final double KNOCKBACK_SCALE = 0.4;

    /** Hook for systems that react to damage (Mahoraga adaptation, traits, domain clash). */
    @FunctionalInterface
    public interface Listener {
        void onHit(LivingEntity attacker, LivingEntity target, float amount, DamageKind kind);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private CombatDamage() {}

    public static void listen(Listener l) {
        LISTENERS.add(l);
    }

    /** What a hit does besides damage. */
    public record Hit(float amount, DamageKind kind, int stun, double knockback, int evadeGain) {
        public static Hit of(float amount, DamageKind kind) {
            return new Hit(amount, kind, 0, 1, 0);
        }
    }

    /** JJA's {@code damageadd}: +1 damage per 5 Strength. */
    public static float strengthBonus(LivingEntity attacker) {
        if (!attacker.hasData(CRBAttachments.SORCERER)) return 0;
        return attacker.getData(CRBAttachments.SORCERER).strength() / 5;
    }

    public static boolean deal(LivingEntity attacker, LivingEntity target, Hit hit) {
        if (!(target.level() instanceof ServerLevel level) || !target.isAlive()) return false;
        CombatState state = target.getData(CRBAttachments.COMBAT);
        if (state.invulnerable()) return false;
        if (Infinity.blocks(target, attacker, hit.kind())) {
            if (attacker.distanceToSqr(target) < 16) Infinity.push(target, attacker);
            return false;
        }
        DamageSource src = new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(hit.kind().key()),
                attacker, attacker);
        target.invulnerableTime = 0;
        boolean hurt = hit.amount() <= 0 || target.hurt(src, hit.amount());
        if (!hurt) return false;
        if (hit.stun() > 0) state.stun(hit.stun());
        if (hit.evadeGain() > 0 && target.hasData(CRBAttachments.COMBO)) target.getData(CRBAttachments.COMBO).addEvade(hit.evadeGain());
        react(attacker, target, hit.knockback());
        for (Listener l : LISTENERS) l.onHit(attacker, target, hit.amount(), hit.kind());
        return true;
    }

    /** JJA: the victim is turned to face the attacker ({@code tp facing}) and knocked back ({@code ds:knockback -1}). */
    static void react(LivingEntity attacker, LivingEntity target, double knockback) {
        Vec3 d = attacker.position().subtract(target.position());
        double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
        if (horizontal < 1e-4) return;
        if (!(target instanceof Player)) {
            float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
            target.setYRot(yaw);
            target.setYHeadRot(yaw);
            if (target instanceof Mob m) m.setYBodyRot(yaw);
        }
        if (knockback > 0) {
            Vec3 away = new Vec3(-d.x / horizontal, 0, -d.z / horizontal).scale(knockback * KNOCKBACK_SCALE);
            Vec3 v = target.getDeltaMovement();
            target.setDeltaMovement(away.x, Math.max(v.y, 0.05), away.z);
            target.hurtMarked = true;
        }
    }
}
