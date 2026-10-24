package io.github.xthefox.crb.client.move;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.client.fx.BedrockFX;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Particles and sounds keyed in JJA's animations themselves ({@code particle_effects} /
 * {@code sound_effects} at keyframe times, on locators such as the hands or eyes), e.g. 200%
 * Hollow Purple's clap and suction. {@code tools/jja/convert.py} resolves the effect names and bakes
 * each locator's position at its keyframe time through the player skeleton
 * ({@code assets/crb/jja/animation_effects.json}); this plays them when the animation does.
 */
public final class AnimationEffects implements ResourceManagerReloadListener {
    public static final AnimationEffects INSTANCE = new AnimationEffects();
    private static final ResourceLocation FILE = CRB.id("jja/animation_effects.json");

    /** @param at locator position in model space (pixels, model facing -Z, right hand at +X), or null for the entity */
    record Effect(float time, String particle, String sound, Vec3 at) {}

    private record Pending(int entityId, String animation, long fireTick, Effect effect) {}

    private Map<String, List<Effect>> effects = Map.of();
    private final List<Pending> pending = new ArrayList<>();
    private final RandomSource random = RandomSource.create();
    private long ticks;

    private AnimationEffects() {}

    @Override
    public void onResourceManagerReload(ResourceManager rm) {
        Map<String, List<Effect>> loaded = new HashMap<>();
        rm.getResource(FILE).ifPresent(r -> {
            try (var reader = new InputStreamReader(r.open(), StandardCharsets.UTF_8)) {
                for (var e : JsonParser.parseReader(reader).getAsJsonObject().entrySet()) {
                    List<Effect> list = new ArrayList<>();
                    for (JsonElement el : e.getValue().getAsJsonArray()) {
                        JsonObject o = el.getAsJsonObject();
                        Vec3 at = null;
                        if (o.has("at")) {
                            JsonArray a = o.getAsJsonArray("at");
                            at = new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
                        }
                        list.add(new Effect(o.get("t").getAsFloat(), o.has("particle") ? o.get("particle").getAsString() : null,
                                o.has("sound") ? o.get("sound").getAsString() : null, at));
                    }
                    loaded.put(e.getKey(), List.copyOf(list));
                }
            } catch (Exception ex) {
                CRB.LOG.error("Failed to read {}", FILE, ex);
            }
        });
        effects = Map.copyOf(loaded);
        pending.clear();
    }

    /** An animation started on {@code entity}, {@code startTick} ticks in. */
    public void onAnimation(Entity entity, String animation, float startTick) {
        if (entity == null) return;
        stop(entity.getId());
        List<Effect> list = effects.get(animation);
        if (list == null) return;
        for (Effect e : list) {
            long at = ticks + Math.round(e.time() * 20f - startTick);
            if (at >= ticks) pending.add(new Pending(entity.getId(), animation, at, e));
        }
    }

    /** The entity's animation was cut off: its remaining effects do not play. */
    public void stop(int entityId) {
        pending.removeIf(p -> p.entityId() == entityId);
    }

    public void clear() {
        pending.clear();
    }

    public void tick() {
        ticks++;
        var level = Minecraft.getInstance().level;
        if (level == null) {
            pending.clear();
            return;
        }
        Iterator<Pending> it = pending.iterator();
        List<Pending> due = new ArrayList<>();
        while (it.hasNext()) {
            Pending p = it.next();
            if (p.fireTick() > ticks) continue;
            it.remove();
            due.add(p);
        }
        for (Pending p : due) {
            Entity e = level.getEntity(p.entityId());
            if (e == null) continue;
            Vec3 pos = worldPos(e, p.effect().at());
            float yaw = e instanceof LivingEntity l ? l.yBodyRot : e.getYRot();
            if (p.effect().particle() != null) BedrockFX.INSTANCE.spawn(p.effect().particle(), pos, yaw, null);
            if (p.effect().sound() != null) {
                SoundCues.resolve(p.effect().sound()).ifPresent(id -> Minecraft.getInstance().getSoundManager().play(
                        new SimpleSoundInstance(SoundEvent.createVariableRangeEvent(id), SoundSource.PLAYERS, 1f, 1f, random, pos.x, pos.y, pos.z)));
            }
        }
    }

    /** A model-space locator (pixels) on the entity, turned with its body. */
    static Vec3 worldPos(Entity e, Vec3 at) {
        if (at == null) return e.position();
        float yaw = (e instanceof LivingEntity l ? l.yBodyRot : e.getYRot()) * Mth.DEG_TO_RAD;
        Vec3 forward = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
        Vec3 right = new Vec3(-Mth.cos(yaw), 0, -Mth.sin(yaw));
        double s = e instanceof LivingEntity l ? l.getScale() : 1;
        return e.position()
                .add(right.scale(at.x / 16 * s))
                .add(0, at.y / 16 * s, 0)
                .add(forward.scale(-at.z / 16 * s));
    }
}
