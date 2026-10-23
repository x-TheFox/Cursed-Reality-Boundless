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
 * One running move on the server. Elapsed ticks match the client's script player, so Java logic
 * keyed on {@link #elapsed()} lines up with the extracted presentation cues.
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

    MoveInstance(LivingEntity caster, ResourceLocation moveId, Variant variant, long startTick, int duration, long seed) {
        this.caster = caster;
        this.moveId = moveId;
        this.variant = variant;
        this.startTick = startTick;
        this.duration = duration;
        this.seed = seed;
        this.random = RandomSource.create(seed);
    }

    public LivingEntity caster() { return caster; }
    public ResourceLocation moveId() { return moveId; }
    /** The ability variant that started this move, or null for moves started by logic (NPCs, cutscenes). */
    public Variant variant() { return variant; }
    public long startTick() { return startTick; }
    public int duration() { return duration; }
    public long seed() { return seed; }
    public RandomSource random() { return random; }

    public int elapsed() {
        return (int) (caster.level().getGameTime() - startTick);
    }

    public Set<String> tags() { return tags; }
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
