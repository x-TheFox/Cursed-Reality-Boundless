package io.github.xthefox.crb.client.move;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import io.github.xthefox.crb.CRB;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.HashMap;
import java.util.Map;

/** Loads {@code assets/<ns>/move_scripts/**.json} (client resources, so resource packs can retime moves). */
public final class MoveScriptManager extends SimpleJsonResourceReloadListener {
    public static final MoveScriptManager INSTANCE = new MoveScriptManager();
    private Map<ResourceLocation, MoveScript> scripts = Map.of();

    private MoveScriptManager() {
        super(new Gson(), "move_scripts");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
        Map<ResourceLocation, MoveScript> loaded = new HashMap<>();
        int cues = 0;
        for (var e : files.entrySet()) {
            try {
                MoveScript s = MoveScript.parse(e.getValue().getAsJsonObject());
                loaded.put(e.getKey(), s);
                cues += s.cues().size();
            } catch (RuntimeException ex) {
                CRB.LOG.error("Invalid move script {}", e.getKey(), ex);
            }
        }
        scripts = Map.copyOf(loaded);
        CRB.LOG.info("Loaded {} move scripts ({} cues)", scripts.size(), cues);
    }

    public MoveScript get(ResourceLocation moveId) {
        return scripts.get(moveId);
    }

    public int size() {
        return scripts.size();
    }
}
