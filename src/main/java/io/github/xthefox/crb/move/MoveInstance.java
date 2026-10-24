package io.github.xthefox.crb.move;

import io.github.xthefox.crb.ability.Variant;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One running move on the server, on JJA's own clock: a countdown ({@code move} score) that starts
 * at the move's length, drops by one per tick and can be set by the move itself (holds, a second
 * press launching a charged attack, early exits). The move ends when the countdown reaches 0.
 * {@link #elapsed()} (ticks since start) is kept for Java logic that does not care about jumps.
 */
public final class MoveInstance {
    private final LivingEntity caster;
    private final ResourceLocation moveId;
    private final Variant variant;
    private final long startTick;
    private final int duration;
    private final long seed;
    private final RandomSource random;
    /** JJA-style tags and scores used by cue conditions ({@code tag=blackflashed}, {@code score:chance=2}). */
    private final Set<String> tags = new HashSet<>();
    private final Map<String, Integer> scores = new HashMap<>();
    /** Free-form state for the move's Java logic. */
    private final Map<String, Object> data = new HashMap<>();
    private boolean stateDirty;
    /** JJA's {@code move} value processed next tick. */
    int counter;
    long lastTick = Long.MIN_VALUE;
    /** The counter was set this tick (not just decremented): clients need it. */
    boolean jumped;
    /** Input since the last tick: "input" (the move's ability pressed again), "input:punch" (M1). */
    final Set<String> inputs = new HashSet<>();
    /** Live values JJA reads from scores (Sneaking, detect_jump, Stun, Energy), refreshed every tick. */
    final Map<String, Integer> live = new HashMap<>();
    /** Where a run of {@code tp @s ~~~} pins the caster. */
    net.minecraft.world.phys.Vec3 holdPos;
    long holdTick = Long.MIN_VALUE;

    MoveInstance(LivingEntity caster, ResourceLocation moveId, Variant variant, long startTick, int duration, long seed) {
        this.caster = caster;
        this.moveId = moveId;
        this.variant = variant;
        this.startTick = startTick;
        this.duration = duration;
        this.seed = seed;
        this.random = RandomSource.create(seed);
        this.counter = duration;
    }

    public LivingEntity caster() { return caster; }
    public ResourceLocation moveId() { return moveId; }
    /** The ability variant that started this move, or null for moves started by logic (NPCs, cutscenes). */
    public Variant variant() { return variant; }
    public long startTick() { return startTick; }
    /** The countdown's start value (JJA {@code scoreboard players set @s move N}). */
    public int duration() { return duration; }
    /** The countdown value being processed this tick (JJA's {@code move} score). */
    public int counter() { return counter; }

    /** Sets the countdown (JJA {@code scoreboard players set @s move N}); clients are told. */
    public void setCounter(int value) {
        if (value != counter) {
            counter = value;
            jumped = true;
        }
    }

    /** Records a press for the move's {@code tag=input} lines this tick. */
    public void input(String kind) {
        inputs.add(kind);
    }
    public long seed() { return seed; }
    public RandomSource random() { return random; }

    public int elapsed() {
        return (int) (caster.level().getGameTime() - startTick);
    }

    public Set<String> tags() { return tags; }

    /** Tags for condition tests this tick: the move's tags plus this tick's inputs. */
    Set<String> condTags() {
        if (inputs.isEmpty()) return tags;
        Set<String> all = new HashSet<>(tags);
        inputs.forEach(i -> all.add(i));
        return all;
    }

    /** Scores for condition tests this tick: the move's scores plus live JJA scores. */
    Map<String, Integer> condScores() {
        Map<String, Integer> all = new HashMap<>(live);
        all.putAll(scores);
        return all;
    }
    public Map<String, Integer> scores() { return scores; }

    /** Sets a cue-condition tag; synced to clients at the end of the tick. */
    public void tag(String tag, boolean on) {
        if (on ? tags.add(tag) : tags.remove(tag)) stateDirty = true;
    }

    public void score(String key, int value) {
        Integer prev = scores.put(key, value);
        if (prev == null || prev != value) stateDirty = true;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, T fallback) {
        return (T) data.getOrDefault(key, fallback);
    }

    public void put(String key, Object value) {
        data.put(key, value);
    }

    boolean consumeStateDirty() {
        boolean d = stateDirty;
        stateDirty = false;
        return d;
    }
}
