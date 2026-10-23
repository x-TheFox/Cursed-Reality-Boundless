package io.github.xthefox.crb.client.move;

import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.move.CueConditions;
import io.github.xthefox.crb.move.MoveLogic;
import io.github.xthefox.crb.net.MovePayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plays move scripts on the client. Each {@link MovePayloads.Start} creates an instance anchored to
 * the local tick counter, so effects land on the same tick the server simulates them.
 */
public final class ClientMoves {
    /** Handles one cue type ("sound", "camera_set" ...). */
    @FunctionalInterface
    public interface CueHandler {
        void play(Playing move, MoveScript.Cue cue, int elapsed);
    }

    /** Notified when a move ends (to stop animations, cameras, looping sounds). */
    @FunctionalInterface
    public interface EndListener {
        void onEnd(Playing move, MoveLogic.EndReason reason);
    }

    public static final class Playing {
        public final int entityId;
        public final ResourceLocation moveId;
        public final MoveScript script;
        public final long localStart;
        public final int startOffset;
        public final RandomSource random;
        public final Set<String> tags;
        public final Map<String, Integer> scores;
        int next;
        /** Server said FINISHED; we are a few ticks behind it, so the script plays out locally. */
        boolean serverFinished;

        Playing(MovePayloads.Start p, MoveScript script, long now) {
            this.entityId = p.entityId();
            this.moveId = p.moveId();
            this.script = script;
            this.startOffset = p.elapsed();
            this.localStart = now - p.elapsed();
            this.random = RandomSource.create(p.seed());
            this.tags = new HashSet<>(p.tags());
            this.scores = new HashMap<>(p.scores());
            this.next = p.elapsed();
        }

        /** A projectile runner: cues keyed on the projectile's remaining Deleter instead of elapsed time. */
        Playing(int entityId, ResourceLocation id, MoveScript script, long now) {
            this.entityId = entityId;
            this.moveId = id;
            this.script = script;
            this.startOffset = 0;
            this.localStart = now;
            this.random = RandomSource.create(entityId);
            this.tags = new HashSet<>();
            this.scores = new HashMap<>();
        }

        public Entity entity() {
            var level = Minecraft.getInstance().level;
            return level == null ? null : level.getEntity(entityId);
        }

        /** True when the local player is the caster. */
        public boolean isLocalCaster() {
            LocalPlayer me = Minecraft.getInstance().player;
            return me != null && me.getId() == entityId;
        }

        /** Whether the local player is in a cue's audience ("self", "radius:N", "all"). */
        public boolean localInAudience(MoveScript.Cue cue, Set<String> localTags) {
            String audience = cue.str("audience", cue.str("target", "self"));
            LocalPlayer me = Minecraft.getInstance().player;
            if (me == null) return false;
            if (!CueConditions.test(cue.audienceCond(), localTags, Map.of())) return false;
            if (audience.equals("all")) return true;
            if (audience.equals("self")) return isLocalCaster();
            if (audience.startsWith("radius:")) {
                Entity e = entity();
                if (e == null) return false;
                double r = Double.parseDouble(audience.substring(7));
                return me.distanceToSqr(e) <= r * r;
            }
            return false;
        }
    }

    private static final Map<String, CueHandler> HANDLERS = new ConcurrentHashMap<>();
    private static final List<EndListener> END_LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final Map<Integer, Playing> ACTIVE = new ConcurrentHashMap<>();
    private static long ticks;

    private ClientMoves() {}

    public static void registerHandler(String type, CueHandler handler) {
        HANDLERS.put(type, handler);
    }

    public static void onEnd(EndListener listener) {
        END_LISTENERS.add(listener);
    }

    /** The client tick counter move playback runs on. */
    public static long ticks() {
        return ticks;
    }

    public static Map<Integer, Playing> active() {
        return ACTIVE;
    }

    public static void start(MovePayloads.Start p) {
        MoveScript script = MoveScriptManager.INSTANCE.get(p.moveId());
        Playing old = ACTIVE.remove(p.entityId());
        if (old != null) END_LISTENERS.forEach(l -> l.onEnd(old, MoveLogic.EndReason.INTERRUPTED));
        if (script == null) {
            CRB.LOG.debug("No move script for {}", p.moveId());
            return;
        }
        Playing playing = new Playing(p, script, ticks);
        ACTIVE.put(p.entityId(), playing);
        if (p.elapsed() > 0) catchUpAnimation(playing);
    }

    public static void end(MovePayloads.End p) {
        Playing m = ACTIVE.get(p.entityId());
        if (m == null || !m.moveId.equals(p.moveId())) return;
        if (p.reason() == MoveLogic.EndReason.FINISHED && ticks - m.localStart < m.script.duration()) {
            m.serverFinished = true; // finish the last few ticks of presentation, then end
            return;
        }
        ACTIVE.remove(p.entityId());
        END_LISTENERS.forEach(l -> l.onEnd(m, p.reason()));
    }

    public static void state(MovePayloads.State p) {
        Playing m = ACTIVE.get(p.entityId());
        if (m == null) return;
        m.tags.clear();
        m.tags.addAll(p.tags());
        m.scores.clear();
        m.scores.putAll(p.scores());
    }

    /** Plays a single cue now (M1 swings and hits, which are not moves in JJA). */
    public static void playOneShot(int entityId, String cueJson) {
        com.google.gson.JsonObject c;
        try {
            c = com.google.gson.JsonParser.parseString(cueJson).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        c.addProperty("t", 0);
        c.addProperty("until", 0);
        com.google.gson.JsonObject script = new com.google.gson.JsonObject();
        script.addProperty("source", "one_shot");
        script.addProperty("duration", 1);
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        arr.add(c);
        script.add("cues", arr);
        MoveScript ms = MoveScript.parse(script);
        Playing p = new Playing(new MovePayloads.Start(entityId, ResourceLocation.fromNamespaceAndPath("crb", "one_shot"), 0, 1, 0,
                List.of(), Map.of()), ms, ticks);
        fire(p, 0);
    }

    public static void clear() {
        ACTIVE.clear();
    }

    /** Client tick: advance every playing move and fire the cues due this tick. */
    public static void tick() {
        ticks++;
        Iterator<Playing> it = ACTIVE.values().iterator();
        while (it.hasNext()) {
            Playing m = it.next();
            int now = (int) (ticks - m.localStart);
            // finished on the server and played out here; or the end packet was lost (safety net)
            if ((m.serverFinished && now >= m.script.duration()) || now > m.script.duration() + 40) {
                for (int e = m.next; e < m.script.duration(); e++) fire(m, e);
                it.remove();
                END_LISTENERS.forEach(l -> l.onEnd(m, MoveLogic.EndReason.FINISHED));
                continue;
            }
            for (int e = m.next; e <= now; e++) fire(m, e);
            m.next = now + 1;
        }
    }

    /** A runner for a projectile entity; see {@link #fireAt}. */
    public static Playing projectile(int entityId, ResourceLocation scriptId, MoveScript script) {
        return new Playing(entityId, scriptId, script, ticks);
    }

    /** Fires the cues due at {@code key} (a projectile's remaining Deleter). */
    public static void fireAt(Playing m, int key) {
        fire(m, key);
    }

    private static void fire(Playing m, int elapsed) {
        for (MoveScript.Cue cue : m.script.cues()) {
            if (cue.t() > elapsed) break; // cues are sorted by start tick
            if (elapsed > cue.until()) continue;
            if (!cue.repeats() && elapsed != cue.t()) continue;
            if (!CueConditions.test(cue.cond(), m.tags, m.scores)) continue;
            CueHandler h = HANDLERS.get(cue.type());
            if (h == null) continue;
            try {
                h.play(m, cue, elapsed);
            } catch (RuntimeException ex) {
                CRB.LOG.error("Cue {} of {} failed", cue.type(), m.moveId, ex);
            }
        }
    }

    /** A late joiner starts the most recent animation part-way through instead of skipping it. */
    private static void catchUpAnimation(Playing m) {
        MoveScript.Cue last = null;
        for (MoveScript.Cue cue : m.script.cues()) {
            if (cue.t() >= m.startOffset) break;
            if (cue.type().equals("anim") && "self".equals(cue.str("target", "self"))
                    && CueConditions.test(cue.cond(), m.tags, m.scores)) last = cue;
        }
        if (last != null && HANDLERS.containsKey("anim_resume")) HANDLERS.get("anim_resume").play(m, last, m.startOffset - last.t());
    }

    /** Helper for handlers: the entity a cue's position is relative to (caster or projectile), or null. */
    public static Entity anchor(Playing m) {
        return m.entity();
    }

    /** Helper for handlers: the caster as a living entity, or null if not loaded. */
    public static LivingEntity caster(Playing m) {
        return m.entity() instanceof LivingEntity l ? l : null;
    }
}
