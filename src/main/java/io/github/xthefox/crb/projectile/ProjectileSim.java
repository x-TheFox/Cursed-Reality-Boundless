package io.github.xthefox.crb.projectile;

import io.github.xthefox.crb.combat.DamageKind;
import io.github.xthefox.crb.combat.HitBatch;
import io.github.xthefox.crb.combat.Hits;
import io.github.xthefox.crb.move.CueConditions;
import io.github.xthefox.crb.move.CuePositions;
import io.github.xthefox.crb.move.SimCue;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runs a projectile's extracted simulation lines for its current Deleter value: hits and stuns
 * around it (credited to its owner, with Bedrock's hurt cooldown per target), helper entities,
 * child projectiles, self-retargeting ({@code scoreboard players set @s Deleter 3} on contact)
 * and terrain carving.
 */
public final class ProjectileSim {
    private ProjectileSim() {}

    /** Whether a line gated on {@code t..until} runs at Deleter {@code d}. */
    public static boolean active(int t, int until, int d) {
        return d == TechniqueProjectile.UNSET ? t == TechniqueProjectile.UNSET : d >= t && d <= until;
    }

    /** The tags and scores a projectile's lines are tested against. */
    public static Set<String> tags(TechniqueProjectile p, ProjectileData.Entry entry, LivingEntity owner) {
        Set<String> tags = new HashSet<>();
        tags.add("proj=" + p.name());
        for (String f : entry.facts()) {
            if (WorldFacts.eval(p.level(), p.position(), f, p, owner)) tags.add(f);
        }
        return tags;
    }

    public static Map<String, Integer> scores(TechniqueProjectile p) {
        Map<String, Integer> s = new HashMap<>();
        // Lapse Blue's Mode alternates 1, 2, 1, 2 (`set Mode 0 if 2..`, then `add Mode 1`)
        s.put("Mode", p.age() % 2 == 1 ? 1 : 2);
        // `scoreboard players random @s chance 1 5`
        s.put("chance", 1 + p.getRandom().nextInt(5));
        return s;
    }

    static void run(TechniqueProjectile p, int d, LivingEntity owner) {
        ProjectileData.Entry entry = ProjectileData.INSTANCE.get(p.name());
        if (entry.sim().isEmpty()) return;
        Set<String> tags = tags(p, entry, owner);
        Map<String, Integer> scores = scores(p);
        HitBatch hits = new HitBatch();
        float yaw = p.getYRot(), pitch = p.getXRot();
        for (SimCue c : entry.sim()) {
            if (!active(c.lo(), c.hi(), d)) continue;
            if (!CueConditions.test(c.cond(), tags, scores)) continue;
            switch (c.type()) {
                case "hit", "stun" -> {
                    if (owner == null) continue;
                    Vec3 at = CuePositions.resolve(c.array("at"), p.position(), 0, yaw, pitch);
                    List<LivingEntity> targets = Hits.sphere(owner, at, c.num("radius", 1), 0);
                    if (c.type().equals("hit")) hits.add(targets, c.targetCond(), (float) c.num("amount", 0), 0, c.data().has("strength"));
                    else hits.add(targets, c.targetCond(), 0, (int) c.num("ticks", 0), false);
                }
                case "self_deleter" -> {
                    int v = (int) c.num("value", 0);
                    p.setRemaining(v);
                    if (v <= 3) p.stop(); // contact: JJA's "set Deleter 3" ends the flight
                }
                case "self_move" -> {
                    Vec3 to;
                    if (c.data().has("rel")) {
                        var r = c.array("rel");
                        to = p.position().add(r.get(0).getAsDouble(), r.get(1).getAsDouble(), r.get(2).getAsDouble());
                    } else {
                        var l = c.array("local");
                        to = CuePositions.local(p.position(), yaw, pitch, l.get(0).getAsDouble(), l.get(1).getAsDouble(), l.get(2).getAsDouble());
                    }
                    p.setPos(to.x, to.y, to.z);
                }
                case "move_toward" -> Projectiles.moveToward(p, owner, c.str("target", ""), c.num("step", 0));
                default -> Projectiles.apply(c, owner, p, p.position(), 0, yaw, pitch);
            }
            if (p.isRemoved()) return;
        }
        if (!hits.isEmpty() && owner != null) hits.flush(owner, kindFor(p.name()), (target, amount) -> admit(p, target, amount));
    }

    /** Bedrock's hurt cooldown: a target hit within the last 10 ticks only takes damage above that hit. */
    static float admit(TechniqueProjectile p, LivingEntity target, float amount) {
        long now = p.level().getGameTime();
        TechniqueProjectile.LastHit last = p.lastHits.get(target.getUUID());
        if (last != null && now - last.tick < TechniqueProjectile.HURT_COOLDOWN) {
            float extra = amount - last.amount;
            if (extra <= 0) return 0;
            last.amount = amount;
            return extra;
        }
        p.lastHits.put(target.getUUID(), new TechniqueProjectile.LastHit(now, amount));
        return amount;
    }

    static DamageKind kindFor(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("purple") || n.contains("hollow") || n.endsWith("p") && n.contains("imagin")) return DamageKind.DELETION;
        if (n.contains("lapse") || n.contains("blue")) return DamageKind.PULL;
        if (n.contains("red") || n.contains("reversal") || n.contains("twilight")) return DamageKind.EXPLOSION;
        if (n.contains("fang")) return DamageKind.SLASH;
        return DamageKind.BLUNT;
    }
}
