package io.github.xthefox.crb.progression.kit;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import io.github.xthefox.crb.CRB;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Loads kits from {@code data/<ns>/crb_kits/*.json} on server start and {@code /reload}. */
public final class KitManager extends SimpleJsonResourceReloadListener {
    public static final KitManager INSTANCE = new KitManager();
    private Map<ResourceLocation, Kit> kits = Map.of();

    private KitManager() {
        super(new Gson(), "crb_kits");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
        Map<ResourceLocation, Kit> loaded = new TreeMap<>();
        files.forEach((id, json) -> Kit.CODEC.parse(JsonOps.INSTANCE, json)
                .resultOrPartial(err -> CRB.LOG.error("Invalid kit {}: {}", id, err))
                .ifPresent(kit -> loaded.put(id, kit)));
        kits = Map.copyOf(loaded);
        CRB.LOG.info("Loaded {} character kits", kits.size());
    }

    public Optional<Kit> get(ResourceLocation id) {
        return Optional.ofNullable(kits.get(id));
    }

    public Map<ResourceLocation, Kit> all() {
        return kits;
    }
}
