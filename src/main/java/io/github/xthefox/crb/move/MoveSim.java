package io.github.xthefox.crb.move;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.combat.CombatDamage;
import io.github.xthefox.crb.combat.DamageKind;
import io.github.xthefox.crb.combat.HitBatch;
import io.github.xthefox.crb.combat.Hits;
import io.github.xthefox.crb.data.CRBAttachments;
import io.github.xthefox.crb.data.CombatState;
import io.github.xthefox.crb.projectile.Projectiles;
import io.github.xthefox.crb.projectile.WorldFacts;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
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
    /** World tests ({@code w:far=5}) a move's counter jumps use. */
    private Map<ResourceLocation, Set<String>> facts = Map.of();
    /** Highest countdown value a move's lines use (jump targets included). */
    private Map<ResourceLocation, Integer> maxCounters = Map.of();
    /** Moves with lines reading a second press ("input") or a punch ("input:punch"). */
    private Map<ResourceLocation, Set<String>> inputs = Map.of();

    private MoveSim() {}

    @Override
    public void onResourceManagerReload(ResourceManager rm) {
        Map<ResourceLocation, List<SimCue>> loaded = new HashMap<>();
        Map<ResourceLocation, Set<String>> f = new HashMap<>();
        Map<ResourceLocation, Integer> max = new HashMap<>();
        Map<ResourceLocation, Set<String>> in = new HashMap<>();
        rm.getResource(FILE).ifPresent(r -> {
            try (var reader = new InputStreamReader(r.open(), StandardCharsets.UTF_8)) {
                for (var e : JsonParser.parseReader(reader).getAsJsonObject().entrySet()) {
                    ResourceLocation id = ResourceLocation.parse(e.getKey());
                    // world tests ("if entity in range") are what the hit test itself checks
                    List<SimCue> cues = SimCue.parseAll(e.getValue().getAsJsonArray(), c -> !c.startsWith("if:") && !c.startsWith("unless:"));
                    loaded.put(id, cues);
                    Set<String> facts = new LinkedHashSet<>();
                    Set<String> inputs = new HashSet<>();
                    int m = 0;
                    for (SimCue c : cues) {
                        for (String cond : c.cond()) {
                            if (cond.startsWith("w:")) facts.add(cond);
                            if (cond.startsWith("tag=input")) inputs.add(cond.substring(4));
                        }
                        if (c.hi() < 1_000_000) m = Math.max(m, c.hi());
                        if (c.type().equals("counter")) m = Math.max(m, (int) c.num("value", 0));
                    }
                    if (!facts.isEmpty()) f.put(id, Set.copyOf(facts));
                    if (!inputs.isEmpty()) in.put(id, Set.copyOf(inputs));
                    max.put(id, m);
                }
            } catch (Exception ex) {
                CRB.LOG.error("Failed to read {}", FILE, ex);
            }
        });
        sims = Map.copyOf(loaded);
        facts = Map.copyOf(f);
        maxCounters = Map.copyOf(max);
        inputs = Map.copyOf(in);
    }

    public boolean has(ResourceLocation moveId) {
        return sims.containsKey(moveId);
    }

    public int maxCounter(ResourceLocation moveId) {
        return maxCounters.getOrDefault(moveId, 0);
    }

    /** Whether the move reads this kind of press ("input", "input:punch"). */
    public boolean readsInput(ResourceLocation moveId, String kind) {
        return inputs.getOrDefault(moveId, Set.of()).contains(kind);
    }

    /** The move's lines at the current countdown value, in file order: a jump changes what later lines see. */
    void apply(MoveInstance m) {
        List<SimCue> list = sims.get(m.moveId());
        if (list == null) return;
        LivingEntity caster = m.caster();
        Set<String> tags = m.condTags();
        Set<String> fs = facts.get(m.moveId());
        if (fs != null) {
            tags = new HashSet<>(tags);
            for (String fact : fs) {
                if (WorldFacts.eval(caster.level(), caster.position(), fact, caster, caster)) tags.add(fact);
            }
        }
        Map<String, Integer> scores = m.condScores();
        HitBatch hits = new HitBatch();
        for (SimCue c : list) {
            if (!c.active(m.counter)) continue;
            if (!CueConditions.test(c.cond(), tags, scores)) continue;
            switch (c.type()) {
                case "hit", "stun" -> {
                    Vec3 at = CuePositions.resolve(c.array("at"), caster.position(), caster.getEyeHeight(),
                            caster.getYRot(), caster.getXRot());
                    List<LivingEntity> targets = Hits.sphere(caster, at, c.num("radius", 1));
                    if (c.type().equals("hit")) hits.add(targets, c.targetCond(), (float) c.num("amount", 0), 0, c.data().has("strength"));
                    else hits.add(targets, c.targetCond(), 0, (int) c.num("ticks", 0), false);
                }
                case "counter" -> {
                    int v = (int) c.num("value", 0);
                    int next = switch (c.str("op", "set")) {
                        case "add" -> m.counter + v;
                        case "remove" -> m.counter - v;
                        default -> v;
                    };
                    // unconditional jumps (holds, loops) are in the client script too; only the rest is sent
                    if (c.data().has("cond")) m.setCounter(next);
                    else m.counter = next;
                }
                case "lock" -> {
                    CombatState s = caster.getData(CRBAttachments.COMBAT);
                    int ticks = (int) c.num("ticks", 0);
                    // JJA: input stays disabled while the score is 3+, so a score of N locks for N-2 ticks
                    if (c.str("what", "move").equals("camera")) s.lockCamera(Math.max(0, ticks - 2));
                    else s.lockMovement(Math.max(0, ticks - 2));
                }
                case "hold" -> hold(m);
                case "impulse" -> impulse(caster, c.data());
                case "hover" -> {
                    Vec3 v = caster.getDeltaMovement();
                    caster.setDeltaMovement(v.x, Math.max(v.y, 0.0), v.z);
                    caster.fallDistance = 0;
                    caster.hurtMarked = true;
                }
                case "self_move" -> {
                    if (c.data().has("local")) selfMove(caster, c.array("local"));
                    else if (c.data().has("rel")) selfMoveRel(caster, c.array("rel"));
                }
                case "move_toward" -> Projectiles.moveToward(caster, caster, c.str("target", ""), c.num("step", 0));
                default -> Projectiles.apply(c, caster, caster, caster.position(), caster.getEyeHeight(), caster.getYRot(), caster.getXRot());
            }
        }
        if (hits.isEmpty()) return;
        // lets hit-confirm presentation (JJA `if entity ... run playsound`) play on clients
        if (hits.flush(caster, kindFor(m.moveId()), null)) m.tag("if:entity", true);
    }

    /**
     * JJA {@code tp @s ~~~} (or {@code tp @s @s}) every tick: the caster stays exactly where the run
     * started, with no falling or drifting. Players are pinned with teleports (their position is
     * client-driven); the view is left alone.
     */
    static void hold(MoveInstance m) {
        LivingEntity c = m.caster();
        long now = c.level().getGameTime();
        if (m.holdPos == null || m.holdTick < now - 1) m.holdPos = c.position();
        m.holdTick = now;
        Vec3 p = m.holdPos;
        c.setDeltaMovement(Vec3.ZERO);
        c.fallDistance = 0;
        if (c instanceof ServerPlayer sp) {
            if (sp.position().distanceToSqr(p) > 1e-4) {
                sp.connection.teleport(p.x, p.y, p.z, 0, 0, EnumSet.of(RelativeMovement.X_ROT, RelativeMovement.Y_ROT));
            }
            sp.hurtMarked = true;
        } else {
            c.setPos(p.x, p.y, p.z);
        }
    }

    /** {@code tp @s ~x ~y ~z}. */
    private static void selfMoveRel(LivingEntity caster, JsonArray rel) {
        Vec3 delta = new Vec3(rel.get(0).getAsDouble(), rel.get(1).getAsDouble(), rel.get(2).getAsDouble());
        if (caster instanceof Player) {
            caster.setDeltaMovement(delta);
            caster.hurtMarked = true;
        } else {
            caster.move(net.minecraft.world.entity.MoverType.SELF, delta);
        }
        caster.fallDistance = 0;
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
