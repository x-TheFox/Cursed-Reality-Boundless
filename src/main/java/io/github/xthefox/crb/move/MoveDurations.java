package io.github.xthefox.crb.move;

import com.google.gson.JsonParser;
import io.github.xthefox.crb.CRB;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;

/**
 * Server-side durations of every extracted move ({@code data/crb/move_durations.json}, written by
 * {@code tools/jja/timelines.py}). Used by {@code /crb move play} and by NPC brains.
 */
public final class MoveDurations implements ResourceManagerReloadListener {
    public static final MoveDurations INSTANCE = new MoveDurations();
    private static final ResourceLocation FILE = CRB.id("move_durations.json");
    private Map<ResourceLocation, Integer> durations = Map.of();

    private MoveDurations() {}

    @Override
    public void onResourceManagerReload(ResourceManager rm) {
        Map<ResourceLocation, Integer> loaded = new TreeMap<>();
        rm.getResource(FILE).ifPresent(r -> {
            try (var reader = new InputStreamReader(r.open(), StandardCharsets.UTF_8)) {
                JsonParser.parseReader(reader).getAsJsonObject().entrySet()
                        .forEach(e -> loaded.put(ResourceLocation.parse(e.getKey()), e.getValue().getAsInt()));
            } catch (Exception e) {
                CRB.LOG.error("Failed to read {}", FILE, e);
            }
        });
        durations = Map.copyOf(loaded);
    }

    public OptionalInt get(ResourceLocation moveId) {
        Integer d = durations.get(moveId);
        return d == null ? OptionalInt.empty() : OptionalInt.of(d);
    }

    public java.util.Set<ResourceLocation> ids() {
        return durations.keySet();
    }
}
