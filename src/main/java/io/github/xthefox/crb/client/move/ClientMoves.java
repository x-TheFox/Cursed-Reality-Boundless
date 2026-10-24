package io.github.xthefox.crb.client.move;

import com.google.gson.JsonObject;
import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.move.CueConditions;
import io.github.xthefox.crb.move.MoveLogic;
import io.github.xthefox.crb.net.MovePayloads;
import io.github.xthefox.crb.projectile.TechniqueProjectile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plays move scripts on the client on JJA's own clock: a countdown that starts at the move's
 * length and drops by one per tick. Unconditional jumps in the script (holds, loops) are followed
 * locally; jumps that depend on input or the world arrive from the server in
 * {@link MovePayloads.State}, so a charge launched by a second press plays its launch on every
 * client.
 */
public final class ClientMoves {
    /** Handles one cue type ("sound", "camera_set" ...). {@code counter} is the countdown value. */
    @FunctionalInterface
    public interface CueHandler {
        void play(Playing move, MoveScript.Cue cue, int counter);
    }

    /** Notified when a move ends (to stop animations, cameras, looping sounds). */
    @FunctionalInterface
    public interface EndListener {
        void onEnd(Playing move, MoveLogic.EndReason reason);
    }

    /** Countdown value before the first tick: every cue counts as entered. */
    static final int NONE = Integer.MIN_VALUE;
    /** How long a client that reached the end waits for the server (a hold or a launch may still come). */
    static final int WAIT_FOR_SERVER = 40;

    public static final class Playing {
        public final int entityId;
        public final ResourceLocation moveId;
        public final MoveScript script;
        /** Client tick the move started at (late joiners: back-dated by what they missed). */
        public final long localStart;
        public final int startOffset;
        public final RandomSource random;
        public final Set<String> tags;
        public final Map<String, Integer> scores;
        /** The countdown value processed next tick. */
        int counter;
        /** The value processed last tick. */
        int prev = NONE;
        /** The countdown jumped since last tick: cues in range count as entered. */
        boolean jumped = true;
        /** Server said FINISHED while we were behind it: play out to 0, then end. */
        boolean serverFinished;
        /** Ticks spent at 0 waiting for the server. */
        int idle;

        Playing(MovePayloads.Start p, MoveScript script, long now) {
            this.entityId = p.entityId();
            this.moveId = p.moveId();
            this.script = script;
            this.startOffset = Math.max(0, p.start() - p.counter());
            this.localStart = now - startOffset;
            this.random = RandomSource.create(p.seed());
            this.tags = new HashSet<>(p.tags());
            this.scores = new HashMap<>(p.scores());
            this.counter = p.counter();
        }

        /** A projectile runner: cues keyed on the projectile's remaining Deleter. */
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

        public int counter() {
            return counter;
        }

        /** Whether the countdown just entered this cue's range (first tick of an animation). */
        public boolean entering(MoveScript.Cue cue) {
            return jumped || !cue.active(prev);
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
        if (p.counter() < p.start()) catchUpAnimation(playing);
    }

    public static void end(MovePayloads.End p) {
        Playing m = ACTIVE.get(p.entityId());
        if (m == null || !m.moveId.equals(p.moveId())) return;
        if (p.reason() == MoveLogic.EndReason.FINISHED && m.counter >= 1) {
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
        if (p.counter() != Integer.MIN_VALUE) {
            m.counter = p.counter(); // a jump the client could not predict (second press, world test)
            m.jumped = true;
            m.idle = 0;
            m.serverFinished = false;
        }
    }

    /** Plays a single cue now (M1 swings and hits, which are not moves in JJA). */
    public static void playOneShot(int entityId, String cueJson) {
        JsonObject c;
        try {
            c = com.google.gson.JsonParser.parseString(cueJson).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        c.addProperty("lo", 1);
        c.addProperty("hi", 1);
        JsonObject script = new JsonObject();
        script.addProperty("source", "one_shot");
        script.addProperty("start", 1);
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        arr.add(c);
        script.add("cues", arr);
        MoveScript ms = MoveScript.parse(script);
        Playing p = new Playing(new MovePayloads.Start(entityId, ResourceLocation.fromNamespaceAndPath("crb", "one_shot"), 1, 1, 0,
                List.of(), Map.of()), ms, ticks);
        fire(p, 1, false);
    }

    /** A runner for a projectile entity; see {@link #fireAt}. */
    public static Playing projectile(int entityId, ResourceLocation scriptId, MoveScript script) {
        return new Playing(entityId, scriptId, script, ticks);
    }

    /** Fires the cues due at {@code key} (a projectile's remaining Deleter). */
    public static void fireAt(Playing m, int key) {
        fire(m, key, false);
        m.jumped = false;
        m.prev = key;
    }

    public static void clear() {
        ACTIVE.clear();
    }

    /** Client tick: advance every playing move one countdown step. */
    public static void tick() {
        ticks++;
        Iterator<Playing> it = ACTIVE.values().iterator();
        while (it.hasNext()) {
            Playing m = it.next();
            if (m.counter < 1) {
                // the end, unless the server is about to jump (a hold waiting for a press) or end it
                if (m.serverFinished || ++m.idle > WAIT_FOR_SERVER) {
                    it.remove();
                    END_LISTENERS.forEach(l -> l.onEnd(m, MoveLogic.EndReason.FINISHED));
                }
                continue;
            }
            int v = m.counter;
            fire(m, v, true);
            m.counter--;
            m.prev = v;
            m.jumped = false;
        }
    }

    /** Runs the cues active at {@code v} in line order; unconditional counter cues move the countdown. */
    private static void fire(Playing m, int v, boolean followCounter) {
        int value = v;
        for (MoveScript.Cue cue : m.script.cues()) {
            if (!cue.active(value)) continue;
            if (!CueConditions.test(cue.cond(), m.tags, m.scores)) continue;
            if (cue.type().equals("counter")) {
                if (followCounter) {
                    int n = (int) cue.num("value", 0);
                    switch (cue.str("op", "set")) {
                        case "add" -> value += n;
                        case "remove" -> value -= n;
                        default -> value = n;
                    }
                    m.counter = value;
                }
                continue;
            }
            if (!cue.repeats() && !m.entering(cue)) continue;
            CueHandler h = HANDLERS.get(cue.type());
            if (h == null) continue;
            try {
                h.play(m, cue, value);
            } catch (RuntimeException ex) {
                CRB.LOG.error("Cue {} of {} failed", cue.type(), m.moveId, ex);
            }
        }
    }

    /** A late joiner starts the most recent animation part-way through instead of skipping it. */
    private static void catchUpAnimation(Playing m) {
        MoveScript.Cue last = null;
        for (MoveScript.Cue cue : m.script.cues()) {
            if (!cue.type().equals("anim") || !"self".equals(cue.str("target", "self"))) continue;
            if (cue.hi() <= m.counter || cue.hi() > m.script.start()) continue;
            if (!CueConditions.test(cue.cond(), m.tags, m.scores)) continue;
            if (last == null || cue.hi() < last.hi()) last = cue;
        }
        if (last != null && HANDLERS.containsKey("anim_resume")) HANDLERS.get("anim_resume").play(m, last, last.hi() - m.counter);
    }

    /** Helper for handlers: the caster as a living entity, or null if not loaded. */
    public static LivingEntity caster(Playing m) {
        return m.entity() instanceof LivingEntity l ? l : null;
    }

    /** The entity a cue runs at: the caster, or (for JJA's {@code execute as @e[...] at @s}) a projectile or victim. */
    public static Entity anchor(Playing m, MoveScript.Cue cue) {
        Entity self = m.entity();
        JsonObject a = cue.object("anchor");
        if (a == null || self == null) return self;
        var level = Minecraft.getInstance().level;
        if (level == null) return null;
        if (a.has("projectile")) {
            String name = a.get("projectile").getAsString();
            return level.getEntitiesOfClass(TechniqueProjectile.class, self.getBoundingBox().inflate(96),
                            p -> p.name().equals(name) && (p.getOwner() == null || p.getOwner() == self))
                    .stream().min(Comparator.comparingDouble(p -> p.distanceToSqr(self))).orElse(null);
        }
        double r = a.has("near") ? a.get("near").getAsDouble() : 16;
        if (a.has("entity_type")) {
            var type = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.tryParse(a.get("entity_type").getAsString()));
            if (type.isEmpty()) return null; // JJA cutscene helpers we do not spawn
            return nearest(level.getEntities(self, self.getBoundingBox().inflate(r), e -> e.getType() == type.get()), self);
        }
        if (a.has("name")) {
            String name = a.get("name").getAsString();
            return nearest(level.getEntities(self, self.getBoundingBox().inflate(64),
                    e -> e.hasCustomName() && e.getCustomName().getString().equals(name)), self);
        }
        boolean players = a.has("players");
        return nearest(level.getEntities(self, self.getBoundingBox().inflate(r), e -> e instanceof LivingEntity l && l.isAlive()
                && !e.isSpectator() && (!players || e instanceof Player) && e.distanceToSqr(self) <= r * r), self);
    }

    private static Entity nearest(List<Entity> list, Entity to) {
        return list.stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(to))).orElse(null);
    }
}
