package io.github.xthefox.crb.combat;

import net.minecraft.world.entity.LivingEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects one tick's hits so several JJA lines hitting the same target ({@code damage 3},
 * {@code damage += damageadd}, {@code Stun 20}) land as a single hit.
 */
public final class HitBatch {
    /** Optional per-target gate (projectile i-frames). Returns the damage that should land. */
    @FunctionalInterface
    public interface DamageGate {
        float admit(LivingEntity target, float amount);
    }

    private static final class Acc {
        float damage;
        int stun;
        boolean strength;
    }

    private final Map<LivingEntity, Acc> hits = new LinkedHashMap<>();

    public boolean isEmpty() {
        return hits.isEmpty();
    }

    /** Adds a radius hit or stun to every target in {@code targets} that passes the JJA target filter. */
    public void add(List<LivingEntity> targets, List<String> targetCond, float damage, int stun, boolean strength) {
        for (LivingEntity t : targets) {
            if (!TargetTags.test(targetCond, t)) continue;
            Acc a = hits.computeIfAbsent(t, k -> new Acc());
            a.damage += damage;
            a.stun = Math.max(a.stun, stun);
            a.strength |= strength;
        }
    }

    /** Deals everything collected. Returns true if any hit landed. */
    public boolean flush(LivingEntity attacker, DamageKind kind, DamageGate gate) {
        boolean landed = false;
        for (var e : hits.entrySet()) {
            Acc a = e.getValue();
            float dmg = a.damage + (a.strength ? CombatDamage.strengthBonus(attacker) : 0);
            if (gate != null) dmg = gate.admit(e.getKey(), dmg);
            if (dmg <= 0 && a.stun <= 0) continue;
            CombatDamage.Hit hit = new CombatDamage.Hit(Math.max(dmg, 0), kind, a.stun, dmg > 0 ? 1 : 0, dmg > 0 ? 6 : 0);
            landed |= CombatDamage.deal(attacker, e.getKey(), hit);
        }
        hits.clear();
        return landed;
    }
}
