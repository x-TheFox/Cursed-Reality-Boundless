package io.github.xthefox.crb.combat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.data.CRBAttachments;
import io.github.xthefox.crb.data.CombatState;
import io.github.xthefox.crb.data.SorcererData;
import io.github.xthefox.crb.move.MoveDurations;
import io.github.xthefox.crb.move.MoveEngine;
import io.github.xthefox.crb.net.CRBNetwork;
import io.github.xthefox.crb.net.MovePayloads;
import io.github.xthefox.crb.progression.Traits;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The M1 string (JJA {@code fighting/basic/basic_m1s}) and Black Flash (JJA {@code blackflash1}).
 *
 * <p>Each press adds the hit's windup; the hit lands when the countdown reaches 3, testing a sphere
 * in front of the attacker (lag-compensated). Four hits make a string, then 15 ticks of end-lag;
 * 25 idle ticks reset it. Every press rolls Black Flash: 1 in 269 (Tactical Intellect 234,
 * 530,000 IQ 230); in The Zone after a Black Flash the odds rise to 1 in 25 for 10 seconds.
 */
public final class Melee {
    public static final int BLACK_FLASH_ROLL = 269, BLACK_FLASH_ROLL_TACTICAL = 234, BLACK_FLASH_ROLL_IQ = 230, ZONE_ROLL = 25;
    public static final int BLACK_FLASH_HIT = 7, BLACK_FLASH_MULTIPLIER = 7, BLACK_FLASH_STUN = 30, ZONE_TICKS = 200;
    static final ResourceLocation BLACK_FLASH_MOVE = CRB.id("blackflash1");
    /** GameTests only: makes the next roll a Black Flash. */
    public static boolean forceNextBlackFlash;

    private Melee() {}

    /** Client pressed attack in Combat Mode. */
    public static void press(ServerPlayer p) {
        CombatState state = p.getData(CRBAttachments.COMBAT);
        ComboState c = p.getData(CRBAttachments.COMBO);
        if (state.stunned() || state.movementLocked() || MoveEngine.isBusy(p) || c.disabled > 0 || c.m1Timer > 0) return;
        CombatStyle style = CombatStyle.of(p);
        c.m1Count = c.m1Count >= 4 ? 1 : c.m1Count + 1;
        c.m1Timer = style.windup(c.m1Count);
        c.m1Reset = CombatStyle.STRING_RESET;
        c.blackFlashPending = rollBlackFlash(p, state);
        cue(p, anim(style.animation(c.m1Count)));
    }

    static boolean rollBlackFlash(LivingEntity p, CombatState state) {
        if (forceNextBlackFlash) {
            forceNextBlackFlash = false;
            return true;
        }
        SorcererData d = p.hasData(CRBAttachments.SORCERER) ? p.getData(CRBAttachments.SORCERER) : null;
        if (d != null && d.has(Traits.HEAVENLY_RESTRICTION)) return false; // JJA: no Black Flash without cursed energy
        int range = state.inTheZone() ? ZONE_ROLL : BLACK_FLASH_ROLL;
        if (d != null && d.has(Traits.IQ_530K)) range = Math.min(range, BLACK_FLASH_ROLL_IQ);
        else if (d != null && d.has(Traits.TACTICAL_INTELLECT)) range = Math.min(range, BLACK_FLASH_ROLL_TACTICAL);
        if (d != null && d.has(Traits.BLACK_FLASH_MASTERY)) range = Math.max(8, range / 3);
        return range <= 1 || 1 + p.getRandom().nextInt(range) == BLACK_FLASH_HIT;
    }

    /** Per-tick countdowns (called for every entity with combo state). */
    public static void tick(LivingEntity e) {
        ComboState c = e.getData(CRBAttachments.COMBO);
        if (c.disabled > 0) c.disabled--;
        if (c.dashCooldown > 0) c.dashCooldown--;
        if (c.doubleJumpCooldown > 0) c.doubleJumpCooldown--;
        if (c.m1Reset > 0 && --c.m1Reset == 0 && c.m1Timer == 0) c.m1Count = 0;
        if (c.m1Timer > 0) {
            if (e.getData(CRBAttachments.COMBAT).stunned()) {
                c.resetString();
                return;
            }
            c.m1Timer--;
            if (c.m1Timer == CombatStyle.HIT_AT) swing(e, c, CombatStyle.of(e));
        }
        Movement.tick(e, c);
    }

    private static void swing(LivingEntity e, ComboState c, CombatStyle style) {
        float yaw = e.getYRot() * Mth.DEG_TO_RAD;
        Vec3 center = e.position().add(-Mth.sin(yaw) * style.reach(), e.getBbHeight() * 0.55, Mth.cos(yaw) * style.reach());
        boolean finisher = c.m1Count == 4;
        cue(e, sound(style.swingSound(), 1, 0.75f));
        List<LivingEntity> targets = Hits.sphere(e, center, style.radius());
        float dmg = style.damage() + CombatDamage.strengthBonus(e);
        boolean landed = false;
        for (LivingEntity t : targets) {
            boolean black = c.blackFlashPending && !landed;
            CombatDamage.Hit hit = black
                    ? new CombatDamage.Hit(dmg * BLACK_FLASH_MULTIPLIER, style.kind(), BLACK_FLASH_STUN, 2.5, 9)
                    : new CombatDamage.Hit(dmg, style.kind(), finisher ? 16 : 6, finisher ? 3 : 1, finisher ? 9 : 6);
            if (CombatDamage.deal(e, t, hit)) {
                landed = true;
                if (black) blackFlash(e, t);
            }
        }
        if (landed) cue(e, sound(style.hitSound(), 1, 1.95f));
        if (finisher) {
            c.disabled = CombatStyle.END_LAG;
            c.m1Count = 0;
        }
        c.blackFlashPending = false;
    }

    /** Black Flash presentation (JJA's blackflash1 timeline) + The Zone. */
    static void blackFlash(LivingEntity attacker, LivingEntity target) {
        attacker.getData(CRBAttachments.COMBAT).zone(ZONE_TICKS);
        int duration = MoveDurations.INSTANCE.get(BLACK_FLASH_MOVE).orElse(10);
        MoveEngine.start(attacker, BLACK_FLASH_MOVE, duration, null, Set.of("blackflash1", "if:entity"), Map.of());
    }

    // ------------------------------------------------------------------ one-shot cues

    static JsonObject anim(String animation) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "anim");
        o.addProperty("anim", animation);
        o.addProperty("target", "self");
        return o;
    }

    static JsonObject sound(String sound, float volume, float pitch) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "sound");
        o.addProperty("sound", sound);
        o.addProperty("audience", "all");
        o.addProperty("volume", volume);
        o.addProperty("pitch", pitch);
        o.add("at", new JsonArray());
        return o;
    }

    static void cue(LivingEntity e, JsonObject cue) {
        MovePayloads.Cue p = new MovePayloads.Cue(e.getId(), cue.toString());
        PacketDistributor.sendToPlayersTrackingEntity(e, p);
        if (e instanceof ServerPlayer sp && CRBNetwork.canReceive(sp, MovePayloads.Cue.TYPE)) PacketDistributor.sendToPlayer(sp, p);
    }
}
