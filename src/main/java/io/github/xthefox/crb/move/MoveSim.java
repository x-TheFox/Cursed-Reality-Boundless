package io.github.xthefox.crb.move;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.combat.CombatDamage;
import io.github.xthefox.crb.combat.DamageKind;
import io.github.xthefox.crb.combat.Hits;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JJA's simulation lines, extracted alongside the presentation ({@code data/crb/move_sim.json}):
 * radius hits ({@code damage @e[r=..] N}, {@code damage += N}, {@code += damageadd}), stuns, caster
 * impulses ({@code ds:knockback}, {@code ds:leapforwards...}), hover ({@code ds:antiair}) and
 * self-lunges ({@code tp @s ^ ^ ^0.5}). Hits within one tick are summed per target and applied
 * once, with lag-compensated hit tests. Moves with bespoke Java logic (projectiles, domains) add
 * that on top.
 */
public final class MoveSim implements ResourceManagerReloadListener {
    public static final MoveSim INSTANCE = new MoveSim();
    private static final ResourceLocation FILE = CRB.id("move_sim.json");
    /** Bedrock applyKnockback strength → blocks/tick (same scale as hit knockback). */
    static final double IMPULSE_SCALE = CombatDamage.KNOCKBACK_SCALE;

    record Cue(int t, int until, String type, JsonObject data, List<String> cond) {}

    private Map<ResourceLocation, List<Cue>> sims = Map.of();

    private MoveSim() {}

    @Override
    public void onResourceManagerReload(ResourceManager rm) {
        Map<ResourceLocation, List<Cue>> loaded = new HashMap<>();
        rm.getResource(FILE).ifPresent(r -> {
            try (var reader = new InputStreamReader(r.open(), StandardCharsets.UTF_8)) {
                for (var e : JsonParser.parseReader(reader).getAsJsonObject().entrySet()) {
                    List<Cue> list = new ArrayList<>();
                    for (JsonElement el : e.getValue().getAsJsonArray()) {
                        JsonObject o = el.getAsJsonObject();
                        List<String> cond = new ArrayList<>();
                        if (o.has("cond")) o.getAsJsonArray("cond").forEach(c -> {
                            String s = c.getAsString();
                            // world tests ("if entity in range") are what the hit test itself checks
                            if (!s.startsWith("if:") && !s.startsWith("unless:")) cond.add(s);
                        });
                        list.add(new Cue(o.get("t").getAsInt(), o.get("until").getAsInt(), o.get("type").getAsString(), o, cond));
                    }
                    loaded.put(ResourceLocation.parse(e.getKey()), List.copyOf(list));
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
        List<Cue> list = sims.get(m.moveId());
        if (list == null) return;
        LivingEntity caster = m.caster();
        Map<LivingEntity, float[]> hits = new LinkedHashMap<>(); // damage, stun, strength flag
        for (Cue c : list) {
            if (elapsed < c.t() || elapsed > c.until()) continue;
            if (!CueConditions.test(c.cond(), m.tags(), m.scores())) continue;
            switch (c.type()) {
                case "hit", "stun" -> {
                    Vec3 at = CuePositions.resolve(c.data().getAsJsonArray("at"), caster.position(), caster.getEyeHeight(),
                            caster.getYRot(), caster.getXRot());
                    double r = c.data().get("radius").getAsDouble();
                    for (LivingEntity target : Hits.sphere(caster, at, r)) {
                        float[] acc = hits.computeIfAbsent(target, k -> new float[3]);
                        if (c.type().equals("hit")) {
                            acc[0] += c.data().get("amount").getAsFloat();
                            if (c.data().has("strength")) acc[2] = 1;
                        } else {
                            acc[1] = Math.max(acc[1], c.data().get("ticks").getAsFloat());
                        }
                    }
                }
                case "impulse" -> impulse(caster, c.data());
                case "hover" -> {
                    Vec3 v = caster.getDeltaMovement();
                    caster.setDeltaMovement(v.x, Math.max(v.y, 0.0), v.z);
                    caster.fallDistance = 0;
                    caster.hurtMarked = true;
                }
                case "self_move" -> selfMove(caster, c.data().getAsJsonArray("local"));
                default -> { }
            }
        }
        if (hits.isEmpty()) return;
        DamageKind kind = kindFor(m.moveId());
        boolean landed = false;
        for (var e : hits.entrySet()) {
            float[] acc = e.getValue();
            float dmg = acc[0] + (acc[2] > 0 ? CombatDamage.strengthBonus(caster) : 0);
            CombatDamage.Hit hit = new CombatDamage.Hit(dmg, kind, (int) acc[1], dmg > 0 ? 1 : 0, dmg > 0 ? 6 : 0);
            landed |= CombatDamage.deal(caster, e.getKey(), hit);
        }
        // lets hit-confirm presentation (JJA `if entity ... run playsound`) play on clients
        if (landed) m.tag("if:entity", true);
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
