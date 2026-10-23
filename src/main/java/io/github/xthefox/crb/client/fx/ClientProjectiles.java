package io.github.xthefox.crb.client.fx;

import io.github.xthefox.crb.client.move.ClientMoves;
import io.github.xthefox.crb.client.move.MoveScript;
import io.github.xthefox.crb.client.move.MoveScriptManager;
import io.github.xthefox.crb.projectile.TechniqueProjectile;
import io.github.xthefox.crb.projectile.WorldFacts;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Plays technique projectiles' runner scripts (JJA {@code limitless/lapseblue}: the orb particles,
 * suction rubble, sounds) from each projectile's synced name and remaining Deleter. Nothing about
 * the effects is sent over the network.
 */
public final class ClientProjectiles {
    private record Runner(ClientMoves.Playing playing, Set<String> facts) {}

    private static final Map<Integer, Runner> RUNNERS = new HashMap<>();

    private ClientProjectiles() {}

    public static void register() {
        TechniqueProjectile.clientTick = ClientProjectiles::tick;
    }

    static void tick(TechniqueProjectile p) {
        Runner r = RUNNERS.get(p.getId());
        if (r == null) {
            ResourceLocation id = MoveScriptManager.INSTANCE.projectileScript(p.name());
            MoveScript script = id == null ? null : MoveScriptManager.INSTANCE.get(id);
            if (script == null) return;
            Set<String> facts = new LinkedHashSet<>();
            script.cues().forEach(c -> c.cond().stream().filter(s -> s.startsWith("w:")).forEach(facts::add));
            r = new Runner(ClientMoves.projectile(p.getId(), id, script), Set.copyOf(facts));
            RUNNERS.put(p.getId(), r);
        }
        Set<String> tags = r.playing().tags;
        tags.clear();
        tags.add("proj=" + p.name());
        for (String f : r.facts()) {
            if (WorldFacts.eval(p.level(), p.position(), f, p, p.getOwner())) tags.add(f);
        }
        ClientMoves.fireAt(r.playing(), p.remaining());
    }

    /** Drops runners whose projectile is gone (client tick). */
    public static void cleanup() {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            RUNNERS.clear();
            return;
        }
        RUNNERS.keySet().removeIf(id -> !(level.getEntity(id) instanceof TechniqueProjectile p) || p.isRemoved());
    }
}
