package io.github.xthefox.crb.move;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.xthefox.crb.CRB;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gameplay status effects from JJA move timelines ({@code effect @s slowness 1 2 true} during a
 * charge, levitation during aerial moves...), applied by the server on the same ticks.
 * Data: {@code data/crb/move_effects.json}, written by {@code tools/jja/timelines.py}.
 */
public final class MoveEffects implements ResourceManagerReloadListener {
    public static final MoveEffects INSTANCE = new MoveEffects();
    private static final ResourceLocation FILE = CRB.id("move_effects.json");

    record Effect(int t, int until, Holder<MobEffect> effect, int ticks, int amplifier, String audience,
                  List<String> cond, List<String> audienceCond) {}

    private Map<ResourceLocation, List<Effect>> effects = Map.of();

    private MoveEffects() {}

    @Override
    public void onResourceManagerReload(ResourceManager rm) {
        Map<ResourceLocation, List<Effect>> loaded = new HashMap<>();
        rm.getResource(FILE).ifPresent(r -> {
            try (var reader = new InputStreamReader(r.open(), StandardCharsets.UTF_8)) {
                for (var e : JsonParser.parseReader(reader).getAsJsonObject().entrySet()) {
                    List<Effect> list = new ArrayList<>();
                    for (JsonElement el : e.getValue().getAsJsonArray()) {
                        JsonObject o = el.getAsJsonObject();
                        var holder = BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.withDefaultNamespace(o.get("effect").getAsString()));
                        if (holder.isEmpty()) continue;
                        list.add(new Effect(o.get("t").getAsInt(), o.get("until").getAsInt(), holder.get(),
                                Math.max(1, (o.has("seconds") ? o.get("seconds").getAsInt() : 1) * 20),
                                o.has("amplifier") ? o.get("amplifier").getAsInt() : 0,
                                o.has("audience") ? o.get("audience").getAsString() : "self",
                                strings(o.getAsJsonArray("cond")), strings(o.getAsJsonArray("audience_cond"))));
                    }
                    if (!list.isEmpty()) loaded.put(ResourceLocation.parse(e.getKey()), List.copyOf(list));
                }
            } catch (Exception ex) {
                CRB.LOG.error("Failed to read {}", FILE, ex);
            }
        });
        effects = Map.copyOf(loaded);
    }

    private static List<String> strings(JsonArray a) {
        if (a == null) return List.of();
        List<String> out = new ArrayList<>();
        a.forEach(x -> out.add(x.getAsString()));
        return out;
    }

    void apply(MoveInstance m, int elapsed) {
        List<Effect> list = effects.get(m.moveId());
        if (list == null) return;
        LivingEntity caster = m.caster();
        for (Effect e : list) {
            if (elapsed < e.t() || elapsed > e.until()) continue;
            if (!CueConditions.test(e.cond(), m.tags(), m.scores())) continue;
            for (LivingEntity target : audience(caster, e.audience())) {
                if (!e.audienceCond().isEmpty() && !CueConditions.test(e.audienceCond(), JjaTags.of(target), Map.of())) continue;
                target.addEffect(new MobEffectInstance(e.effect(), e.ticks(), e.amplifier(), false, false, true), caster);
            }
        }
    }

    private static List<? extends LivingEntity> audience(LivingEntity caster, String audience) {
        if (audience.equals("self")) return List.of(caster);
        if (audience.equals("all")) return caster.level().players();
        if (audience.startsWith("radius:")) {
            double r = Double.parseDouble(audience.substring(7));
            // Bedrock @a: players only
            return caster.level().getEntitiesOfClass(Player.class, caster.getBoundingBox().inflate(r),
                    p -> p.distanceToSqr(caster) <= r * r);
        }
        return List.of();
    }
}
