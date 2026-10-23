package io.github.xthefox.crb.client.move;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.move.CuePositions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code sound} / {@code stopsound} cues. Bedrock sound names resolve to {@code crb:<name>} (JJA's
 * own sounds), then through {@code jja/bedrock_sound_map.json} to Java vanilla events, then to
 * {@code minecraft:<name>}. Bedrock's volume > 1 extends hearing range; Java's sound engine does the
 * same (attenuation × max(volume, 1)), so volumes pass through unchanged.
 */
public final class SoundCues {
    private static final Map<String, Optional<ResourceLocation>> RESOLVED = new ConcurrentHashMap<>();
    private static Map<String, String> bedrockMap;

    private SoundCues() {}

    public static void register() {
        ClientMoves.registerHandler("sound", SoundCues::play);
        ClientMoves.registerHandler("stopsound", SoundCues::stop);
    }

    /** Clears the name cache after a resource reload. */
    public static void reset() {
        RESOLVED.clear();
        bedrockMap = null;
    }

    static void play(ClientMoves.Playing m, MoveScript.Cue cue, int elapsed) {
        Entity caster = ClientMoves.anchor(m);
        if (caster == null) return;
        String audience = cue.str("audience", "all");
        if (!audience.equals("all") && !m.localInAudience(cue, java.util.Set.of())) return;
        Optional<ResourceLocation> id = resolve(cue.str("sound", ""));
        if (id.isEmpty()) return;
        Vec3 at = CuePositions.resolve(cue.array("at"), caster.position(), caster.getEyeHeight(), caster.getYRot(), caster.getXRot());
        float volume = cue.num("volume", 1f);
        float pitch = cue.num("pitch", 1f);
        SoundInstance s = new SimpleSoundInstance(SoundEvent.createVariableRangeEvent(id.get()), SoundSource.PLAYERS,
                volume, pitch, m.random, at.x, at.y, at.z);
        Minecraft.getInstance().getSoundManager().play(s);
    }

    static void stop(ClientMoves.Playing m, MoveScript.Cue cue, int elapsed) {
        String name = cue.str("sound", null);
        if (name == null) return;
        resolve(name).ifPresent(id -> Minecraft.getInstance().getSoundManager().stop(id, null));
    }

    public static Optional<ResourceLocation> resolve(String bedrockName) {
        return RESOLVED.computeIfAbsent(bedrockName.toLowerCase(Locale.ROOT), SoundCues::lookup);
    }

    private static Optional<ResourceLocation> lookup(String name) {
        var sounds = Minecraft.getInstance().getSoundManager();
        String safe = name.replaceAll("[^a-z0-9_./-]", "_");
        ResourceLocation own = ResourceLocation.fromNamespaceAndPath(CRB.MOD_ID, safe);
        if (sounds.getSoundEvent(own) != null) return Optional.of(own);
        String mapped = bedrockMap().get(name);
        if (mapped != null) {
            ResourceLocation m = ResourceLocation.parse(mapped.contains(":") ? mapped : "minecraft:" + mapped);
            if (sounds.getSoundEvent(m) != null) return Optional.of(m);
        }
        ResourceLocation vanilla = ResourceLocation.withDefaultNamespace(safe);
        if (sounds.getSoundEvent(vanilla) != null) return Optional.of(vanilla);
        CRB.LOG.debug("Unresolved move sound {}", name);
        return Optional.empty();
    }

    private static Map<String, String> bedrockMap() {
        if (bedrockMap == null) {
            Map<String, String> map = new ConcurrentHashMap<>();
            Minecraft.getInstance().getResourceManager().getResource(CRB.id("jja/bedrock_sound_map.json")).ifPresent(r -> {
                try (var reader = new InputStreamReader(r.open(), StandardCharsets.UTF_8)) {
                    JsonObject o = JsonParser.parseReader(reader).getAsJsonObject();
                    o.entrySet().forEach(e -> map.put(e.getKey(), e.getValue().getAsString()));
                } catch (Exception e) {
                    CRB.LOG.error("Failed to read bedrock_sound_map.json", e);
                }
            });
            bedrockMap = map;
        }
        return bedrockMap;
    }
}
