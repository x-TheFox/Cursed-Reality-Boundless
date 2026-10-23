package io.github.xthefox.crb.combat;

import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.core.CRBConfig;
import io.github.xthefox.crb.data.CRBAttachments;
import io.github.xthefox.crb.data.CombatState;
import io.github.xthefox.crb.data.SorcererData;
import io.github.xthefox.crb.progression.Traits;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/**
 * Gojo's Infinity. JJA: casting sets {@code infinity = 300 + Energy}; while it is 3 or more the
 * user has i-frames ({@code Frames}), enemies close in are pushed back ({@code tp ^^^-0.12}) and
 * projectiles stop. It counts down one per tick and is broken by domains, the Inverted Spear,
 * World-Cutting Slash and an Infinity-adapted Mahoraga within 4.5 blocks.
 *
 * <p>Upgrade (GJ3, {@code gojoOverpowered}): with Six Eyes, RCT mastery and the
 * {@code crb:infinity_passive} unlock, Infinity is always up at a small cursed-energy drain, the
 * way it is in canon. Pressing Infinity while it is up switches it off (and on again).
 *
 * <p>What still gets through: a domain's sure-hit, Black Flash, and anything registered as a
 * piercer (Domain Amplification, Simple Domain, Inverted Spear, adapted Mahoraga register here
 * as they are implemented). Vanilla damage that bypasses invulnerability ({@code /kill}, void)
 * also gets through.
 */
public final class Infinity {
    public static final ResourceLocation PASSIVE = CRB.id("infinity_passive");
    /** How close something must be before Infinity pushes it back (JJA's push selectors use r=2.5..5). */
    static final double PUSH_RADIUS = 2.5;
    /** JJA: {@code tp @s ^^^-0.12} twice plus a {@code ^^^-0.06} step, per tick. */
    static final double PUSH_STEP = 0.3;
    /** Projectiles are stopped this far out (GJ3: "0.6 blocks short", measured from the hitbox). */
    static final double PROJECTILE_RADIUS = 1.6;
    /** Passive Infinity costs this much cursed energy per second. */
    static final int PASSIVE_DRAIN_PER_SECOND = 2;

    /** Returns true when the attacker gets through the target's Infinity. */
    private static final List<BiPredicate<LivingEntity, LivingEntity>> PIERCERS = new CopyOnWriteArrayList<>();
    private static final ThreadLocal<Boolean> PIERCING = ThreadLocal.withInitial(() -> false);

    private Infinity() {}

    public static void registerPiercer(BiPredicate<LivingEntity, LivingEntity> attackerTarget) {
        PIERCERS.add(attackerTarget);
    }

    /** Runs {@code action} with every hit it deals passing through Infinity (Black Flash). */
    public static <T> T piercing(Supplier<T> action) {
        boolean old = PIERCING.get();
        PIERCING.set(true);
        try {
            return action.get();
        } finally {
            PIERCING.set(old);
        }
    }

    public static boolean passive(LivingEntity e) {
        if (!CRBConfig.SERVER.gojoOverpowered.get() || !e.hasData(CRBAttachments.SORCERER)) return false;
        SorcererData d = e.getData(CRBAttachments.SORCERER);
        return d.has(PASSIVE) && d.has(Traits.SIX_EYES) && d.has(Traits.RCT_MASTER) && d.energy() > 0
                && !e.getData(CRBAttachments.COMBAT).infinitySuppressed();
    }

    public static boolean active(LivingEntity e) {
        if (!e.hasData(CRBAttachments.COMBAT)) return false;
        CombatState s = e.getData(CRBAttachments.COMBAT);
        return s.infinityActive() || passive(e);
    }

    /** Whether Infinity stops this hit. {@code attacker} may be null (environment damage). */
    public static boolean blocks(LivingEntity target, Entity attacker, DamageKind kind) {
        if (!active(target)) return false;
        if (kind == DamageKind.SURE_HIT || PIERCING.get()) return false;
        if (attacker instanceof LivingEntity a) {
            for (var p : PIERCERS) if (p.test(a, target)) return false;
        }
        return true;
    }

    /** JJA {@code limitless1}: {@code infinity = 300 + Energy}. */
    public static void cast(LivingEntity e) {
        int energy = e.hasData(CRBAttachments.SORCERER) ? e.getData(CRBAttachments.SORCERER).energy() : 0;
        CombatState s = e.getData(CRBAttachments.COMBAT);
        s.suppressInfinity(false);
        s.infinity(300 + energy);
    }

    /** Pressing Infinity while it is up: drop it. Returns true if it was up. */
    public static boolean toggleOff(LivingEntity e) {
        CombatState s = e.getData(CRBAttachments.COMBAT);
        boolean was = s.infinityActive() || passive(e);
        if (!was) return false;
        s.infinity(0);
        if (e.hasData(CRBAttachments.SORCERER) && e.getData(CRBAttachments.SORCERER).has(PASSIVE)) s.suppressInfinity(true);
        if (e instanceof ServerPlayer p) {
            p.displayClientMessage(Component.translatable("crb.infinity.off").withStyle(ChatFormatting.AQUA), true);
        }
        return true;
    }

    /** Server tick for any living entity with combat state. */
    public static void tick(LivingEntity e) {
        CombatState s = e.getData(CRBAttachments.COMBAT);
        if (s.infinity() == 3 && !passive(e) && e instanceof ServerPlayer p) {
            p.displayClientMessage(Component.translatable("crb.infinity.ran_out").withStyle(ChatFormatting.RED), false);
        }
        boolean passive = passive(e);
        if (!s.infinityActive() && !passive) return;
        if (passive && e.tickCount % 20 == 0 && !s.infinityActive()) {
            e.getData(CRBAttachments.SORCERER).addEnergy(-PASSIVE_DRAIN_PER_SECOND);
        }
        if (e.isOnFire()) e.clearFire();
        AABB box = e.getBoundingBox().inflate(PUSH_RADIUS);
        for (Entity o : e.level().getEntities(e, box)) {
            if (o instanceof Projectile pr) {
                if (pr.getOwner() == e) continue;
                if (pr.getBoundingBox().inflate(PROJECTILE_RADIUS).intersects(e.getBoundingBox())) {
                    pr.setDeltaMovement(Vec3.ZERO); // the arrow hangs in the air, then drops
                    pr.hurtMarked = true;
                }
            } else if (o instanceof Mob m && (m.getTarget() == e || m instanceof net.minecraft.world.entity.monster.Enemy)) {
                push(e, m);
            }
        }
    }

    /** Pushes an attacker back out of Infinity (also used when a blocked melee lands). */
    public static void push(LivingEntity gojo, LivingEntity other) {
        Vec3 d = other.position().subtract(gojo.position());
        Vec3 h = new Vec3(d.x, 0, d.z);
        if (h.lengthSqr() < 1e-6) h = gojo.getLookAngle().multiply(1, 0, 1);
        Vec3 step = h.normalize().scale(PUSH_STEP);
        if (other instanceof Player) {
            other.setDeltaMovement(step.x * 2, Math.max(other.getDeltaMovement().y, 0.05), step.z * 2);
            other.hurtMarked = true;
        } else {
            other.move(net.minecraft.world.entity.MoverType.SELF, step);
        }
    }
}
