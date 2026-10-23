package io.github.xthefox.crb.projectile;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.move.SimCue;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code data/crb/projectiles.json}: for each JJA projectile name, its runner script (client
 * presentation, {@code assets/crb/move_scripts/projectile/...}) and its simulation lines.
 */
public final class ProjectileData implements ResourceManagerReloadListener {
    public static final ProjectileData INSTANCE = new ProjectileData();
    private static final ResourceLocation FILE = CRB.id("projectiles.json");

    /** @param facts world tests ({@code w:solid}, {@code w:near=4}) the sim lines use, evaluated once per tick */
    public record Entry(ResourceLocation script, List<SimCue> sim, Set<String> facts) {
        static final Entry EMPTY = new Entry(null, List.of(), Set.of());
    }

    private Map<String, Entry> entries = Map.of();

    private ProjectileData() {}

    @Override
    public void onResourceManagerReload(ResourceManager rm) {
        Map<String, Entry> loaded = new HashMap<>();
        rm.getResource(FILE).ifPresent(r -> {
            try (var reader = new InputStreamReader(r.open(), StandardCharsets.UTF_8)) {
                for (var e : JsonParser.parseReader(reader).getAsJsonObject().entrySet()) {
                    JsonObject o = e.getValue().getAsJsonObject();
                    List<SimCue> sim = SimCue.parseAll(o.getAsJsonArray("sim"), c -> true);
                    Set<String> facts = new LinkedHashSet<>();
                    sim.forEach(c -> c.cond().stream().filter(s -> s.startsWith("w:")).forEach(facts::add));
                    loaded.put(e.getKey(), new Entry(ResourceLocation.parse(o.get("script").getAsString()), sim, Set.copyOf(facts)));
                }
            } catch (Exception ex) {
                CRB.LOG.error("Failed to read {}", FILE, ex);
            }
        });
        entries = Map.copyOf(loaded);
    }

    public Entry get(String name) {
        return entries.getOrDefault(name, Entry.EMPTY);
    }

    public int size() {
        return entries.size();
    }
}
