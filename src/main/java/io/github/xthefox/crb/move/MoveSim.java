package io.github.xthefox.crb.move;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.combat.CombatDamage;
import io.github.xthefox.crb.combat.DamageKind;
import io.github.xthefox.crb.combat.HitBatch;
import io.github.xthefox.crb.combat.Hits;
import io.github.xthefox.crb.projectile.Projectiles;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JJA's simulation lines, extracted alongside the presentation ({@code data/crb/move_sim.json}):
 * radius hits ({@code damage @e[r=..] N}, {@code damage += N}, {@code += damageadd}), stuns, caster
 * impulses ({@code ds:knockback}, {@code ds:leapforwards...}), hover ({@code ds:antiair}) and
 * self-lunges ({@code tp @s ^ ^ ^0.5}), plus the move's projectiles and helper entities
 * ({@link Projectiles}). Hits within one tick are summed per target and applied once, with
 * lag-compensated hit tests. Moves with bespoke Java logic (domains) add that on top.
 */
public final class MoveSim implements ResourceManagerReloadListener {
    public static final MoveSim INSTANCE = new MoveSim();
    private static final ResourceLocation FILE = CRB.id("move_sim.json");
    /** Bedrock applyKnockback strength → blocks/tick (same scale as hit knockback). */
    static final double IMPULSE_SCALE = CombatDamage.KNOCKBACK_SCALE;

    private Map<ResourceLocation, List<SimCue>> sims = Map.of();

    private MoveSim() {}

    @Override
    public void onResourceManagerReload(ResourceManager rm) {
        Map<ResourceLocation, List<SimCue>> loaded = new HashMap<>();
        rm.getResource(FILE).ifPresent(r -> {
            try (var reader = new InputStreamReader(r.open(), StandardCharsets.UTF_8)) {
                for (var e : JsonParser.parseReader(reader).getAsJsonObject().entrySet()) {
                    // world tests ("if entity in range") are what the hit test itself checks
                    loaded.put(ResourceLocation.parse(e.getKey()),
                            SimCue.parseAll(e.getValue().getAsJsonArray(), c -> !c.startsWith("if:") && !c.startsWith("unless:")));
                }
            } catch (Exception ex) {
                CRB.LOG.error("Failed to read {}", FILE, ex);
            }
        });
        sims = Map.copyOf(loaded);
    }

    public boolean has(ResourceLocation moveId) {
        return sims.containsKey(moveId);
    }

    void apply(MoveInstance m, int elapsed) {
        List<SimCue> list = sims.get(m.moveId());
        if (list == null) return;
        LivingEntity caster = m.caster();
        HitBatch hits = new HitBatch();
        for (SimCue c : list) {
            if (elapsed < c.t() || elapsed > c.until()) continue;
            if (!CueConditions.test(c.cond(), m.tags(), m.scores())) continue;
            switch (c.type()) {
                case "hit", "stun" -> {
                    Vec3 at = CuePositions.resolve(c.array("at"), caster.position(), caster.getEyeHeight(),
                            caster.getYRot(), caster.getXRot());
                    List<LivingEntity> targets = Hits.sphere(caster, at, c.num("radius", 1));
                    if (c.type().equals("hit")) hits.add(targets, c.targetCond(), (float) c.num("amount", 0), 0, c.data().has("strength"));
                    else hits.add(targets, c.targetCond(), 0, (int) c.num("ticks", 0), false);
                }
                case "impulse" -> impulse(caster, c.data());
                case "hover" -> {
                    Vec3 v = caster.getDeltaMovement();
                    caster.setDeltaMovement(v.x, Math.max(v.y, 0.0), v.z);
                    caster.fallDistance = 0;
                    caster.hurtMarked = true;
                }
                case "self_move" -> {
                    if (c.data().has("local")) selfMove(caster, c.array("local"));
                }
                case "move_toward" -> Projectiles.moveToward(caster, caster, c.str("target", ""), c.num("step", 0));
                default -> Projectiles.apply(c, caster, caster, caster.position(), caster.getEyeHeight(), caster.getYRot(), caster.getXRot());
            }
        }
        if (hits.isEmpty()) return;
        // lets hit-confirm presentation (JJA `if entity ... run playsound`) play on clients
        if (hits.flush(caster, kindFor(m.moveId()), null)) m.tag("if:entity", true);
    }

    /** Bedrock {@code applyKnockback(view.x, view.z, horizontal, vertical)} on the caster. */
    private static void impulse(LivingEntity caster, JsonObject d) {
        double fwd = d.has("forward") ? d.get("forward").getAsDouble() : 0;
        double side = d.has("side") ? d.get("side").getAsDouble() : 0;
        double up = d.has("up") ? d.get("up").getAsDouble() : 0;
        float yaw = caster.getYRot() * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw);
        double sx = fz, sz = -fx; // left of the view direction
        Vec3 v = caster.getDeltaMovement();
        double vx = (fx * fwd + sx * side) * IMPULSE_SCALE, vz = (fz * fwd + sz * side) * IMPULSE_SCALE;
        double vy = up != 0 ? up * IMPULSE_SCALE : v.y;
        caster.setDeltaMovement(fwd == 0 && side == 0 ? v.x : vx, vy, fwd == 0 && side == 0 ? v.z : vz);
        caster.hurtMarked = true;
    }

    /** {@code tp @s ^x ^y ^z} each tick: players get it as velocity (smooth client prediction), mobs are moved. */
    private static void selfMove(LivingEntity caster, JsonArray local) {
        Vec3 target = CuePositions.local(caster.position(), caster.getYRot(), caster.getXRot(),
                local.get(0).getAsDouble(), local.get(1).getAsDouble(), local.get(2).getAsDouble());
        Vec3 delta = target.subtract(caster.position());
        if (caster instanceof Player) {
            caster.setDeltaMovement(delta);
            caster.hurtMarked = true;
        } else {
            caster.move(net.minecraft.world.entity.MoverType.SELF, delta);
        }
        caster.fallDistance = 0;
    }

    /** Damage kind by move family, so Mahoraga adapts to the right thing. */
    static DamageKind kindFor(ResourceLocation id) {
        String p = id.getPath().toLowerCase(Locale.ROOT);
        if (p.contains("dismantle")) return DamageKind.DISMANTLE;
        if (p.contains("cleave") || p.contains("worldslash")) return DamageKind.CLEAVE;
        if (p.contains("purple") || p.contains("hollow")) return DamageKind.DELETION;
        if (p.contains("blue") || p.contains("lapse")) return DamageKind.PULL;
        if (p.contains("red") || p.contains("reversal")) return DamageKind.EXPLOSION;
        if (p.startsWith("flames") || p.contains("fire") || p.contains("fuga")) return DamageKind.FIRE;
        if (p.startsWith("kashimo") || p.contains("lightning")) return DamageKind.ELECTRIC;
        if (p.startsWith("explosive")) return DamageKind.EXPLOSION;
        if (p.startsWith("idle_transfig") || p.contains("soul")) return DamageKind.SOUL;
        if (p.startsWith("speech")) return DamageKind.SPEECH;
        if (p.contains("katana") || p.contains("sword") || p.contains("blade") || p.contains("scythe")
                || p.contains("spear") || p.contains("claw") || p.contains("mandibles") || p.contains("cloud")) return DamageKind.SLASH;
        return DamageKind.BLUNT;
    }
}
