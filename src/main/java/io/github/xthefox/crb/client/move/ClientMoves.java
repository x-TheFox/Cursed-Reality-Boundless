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
        Playing m = ACTIVE.remove(p.entityId());
        if (m != null) END_LISTENERS.forEach(l -> l.onEnd(m, p.reason()));
    }

    public static void state(MovePayloads.State p) {
        Playing m = ACTIVE.get(p.entityId());
        if (m == null) return;
        m.tags.clear();
        m.tags.addAll(p.tags());
        m.scores.clear();
        m.scores.putAll(p.scores());
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
            // the server sends MoveEnd; this is a safety net if it is lost
            if (now > m.script.duration() + 40) {
                it.remove();
                END_LISTENERS.forEach(l -> l.onEnd(m, MoveLogic.EndReason.FINISHED));
                continue;
            }
            for (int e = m.next; e <= now; e++) fire(m, e);
            m.next = now + 1;
        }
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

    /** Helper for handlers: the caster as a living entity, or null if not loaded. */
    public static LivingEntity caster(Playing m) {
        return m.entity() instanceof LivingEntity l ? l : null;
    }
}
