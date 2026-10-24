package io.github.xthefox.crb.client.fx;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.client.move.ClientMoves;
import io.github.xthefox.crb.move.CuePositions;
import io.github.xthefox.crb.molang.MolangContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bedrock particle runtime ("BedrockFX"): loads JJA's Snowstorm effects from
 * {@code assets/<ns>/bedrock_particles/**} and runs them as native client particles.
 */
public final class BedrockFX extends SimpleJsonResourceReloadListener {
    public static final BedrockFX INSTANCE = new BedrockFX();
    /** Emitters alive at once; beyond this new effects are dropped (JJA ultimates can spawn hundreds). */
    private static final int MAX_EMITTERS = 2048;
    /** Particles per frame budget across all emitters. */
    private static final int MAX_PARTICLES = 12000;
    private static final int MAX_EVENT_DEPTH = 4;
    static final boolean DEBUG = Boolean.getBoolean("crb.fxDebug");

    private Map<String, FxDef> defs = Map.of();
    private final List<FxEmitter> emitters = new ArrayList<>();
    private static int eventDepth;

    private BedrockFX() {
        super(new Gson(), "bedrock_particles");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
        Map<String, FxDef> loaded = new HashMap<>();
        int failed = 0;
        for (var e : files.entrySet()) {
            try {
                FxDef d = FxDef.parse(e.getValue().getAsJsonObject(), BedrockFX::texture);
                loaded.putIfAbsent(d.id, d);
            } catch (RuntimeException ex) {
                failed++;
                CRB.LOG.debug("Bad particle effect {}", e.getKey(), ex);
            }
        }
        defs = Map.copyOf(loaded);
        emitters.clear();
        CRB.LOG.info("Loaded {} Bedrock particle effects ({} failed)", defs.size(), failed);
    }

    private static ResourceLocation texture(String ref) {
        ResourceLocation id = ResourceLocation.tryParse(ref.contains(":") ? ref : CRB.MOD_ID + ":" + ref);
        if (id == null) return null;
        return id.getPath().endsWith(".png") ? id : id.withPath(id.getPath() + ".png");
    }

    public static void register() {
        ClientMoves.registerHandler("particle", (m, cue, elapsed) -> {
            Entity caster = ClientMoves.anchor(m, cue);
            if (caster == null) return;
            Vec3 at = CuePositions.resolve(cue.array("at"), caster.position(), caster.getEyeHeight(), caster.getYRot(), caster.getXRot());
            INSTANCE.spawn(cue.str("particle", ""), at, 0, null);
        });
    }

    public FxDef def(String id) {
        return defs.get(id.toLowerCase(Locale.ROOT));
    }

    /** Spawns an effect at a world position. Returns false if the effect is unknown or the budget is full. */
    public boolean spawn(String id, Vec3 pos, float yaw, Entity attachTo) {
        FxDef def = def(id);
        ClientLevel level = Minecraft.getInstance().level;
        if (def == null || level == null || emitters.size() >= MAX_EMITTERS) return false;
        emitters.add(new FxEmitter(def, level, pos, yaw, attachTo, ThreadLocalRandom.current().nextLong()));
        return true;
    }

    static boolean canSpawn() {
        return Minecraft.getInstance().particleEngine != null && INSTANCE.totalAlive() < MAX_PARTICLES;
    }

    private int aliveCache;

    int totalAlive() {
        return aliveCache;
    }

    public void tick() {
        if (Minecraft.getInstance().level == null) {
            emitters.clear();
            return;
        }
        int alive = 0;
        // iterate over a snapshot: events may add emitters while ticking
        List<FxEmitter> snapshot = new ArrayList<>(emitters);
        for (FxEmitter e : snapshot) alive += Math.max(0, e.alive);
        aliveCache = alive;
        Iterator<FxEmitter> it = snapshot.iterator();
        while (it.hasNext()) {
            FxEmitter e = it.next();
            if (e.level != Minecraft.getInstance().level || !e.tick()) emitters.remove(e);
        }
    }

    public void clear() {
        emitters.clear();
    }

    public int emitterCount() {
        return emitters.size();
    }

    /** Runs a named event of an effect (spawning its sub-effects) at {@code pos}. */
    static void fireEvent(FxDef def, String event, Vec3 pos, FxEmitter source, MolangContext ctx) {
        List<FxDef.EventAction> actions = def.events.get(event);
        if (actions == null || eventDepth >= MAX_EVENT_DEPTH) return;
        eventDepth++;
        try {
            for (FxDef.EventAction a : actions) {
                a.preEffect().eval(ctx);
                INSTANCE.spawn(a.effect(), a.boundToEmitter() ? source.pos : pos, source.yaw, a.boundToEmitter() ? source.attached : null);
            }
        } finally {
            eventDepth--;
        }
    }
}
