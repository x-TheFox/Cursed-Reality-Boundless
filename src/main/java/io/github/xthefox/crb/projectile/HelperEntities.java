package io.github.xthefox.crb.projectile;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.combat.CombatDamage;
import io.github.xthefox.crb.combat.DamageKind;
import io.github.xthefox.crb.data.CRBAttachments;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * JJA's one-shot helper entities ({@code summon ds:red_reversal}): BP entities whose only job is a
 * {@code minecraft:explode} or {@code behavior.knockback_roar} on spawn. Converted to a table
 * ({@code data/crb/helper_entities.json}) and applied directly, without spawning anything.
 */
public final class HelperEntities implements ResourceManagerReloadListener {
    public static final HelperEntities INSTANCE = new HelperEntities();
    private static final ResourceLocation FILE = CRB.id("helper_entities.json");

    public record Explode(float power, boolean fire, boolean breaks) {}
    public record Roar(double strength, double range) {}
    public record Helper(Explode explode, Roar roar) {}

    private Map<String, Helper> helpers = Map.of();

    private HelperEntities() {}

    @Override
    public void onResourceManagerReload(ResourceManager rm) {
        Map<String, Helper> loaded = new HashMap<>();
        rm.getResource(FILE).ifPresent(r -> {
            try (var reader = new InputStreamReader(r.open(), StandardCharsets.UTF_8)) {
                for (var e : JsonParser.parseReader(reader).getAsJsonObject().entrySet()) {
                    JsonObject o = e.getValue().getAsJsonObject();
                    Explode ex = null;
                    Roar roar = null;
                    if (o.has("explode")) {
                        JsonObject x = o.getAsJsonObject("explode");
                        ex = new Explode(x.get("power").getAsFloat(), x.get("fire").getAsBoolean(), x.get("breaks").getAsBoolean());
                    }
                    if (o.has("roar")) {
                        JsonObject x = o.getAsJsonObject("roar");
                        roar = new Roar(x.get("strength").getAsDouble(), x.get("range").getAsDouble());
                    }
                    loaded.put(e.getKey(), new Helper(ex, roar));
                }
            } catch (Exception ex) {
                CRB.LOG.error("Failed to read {}", FILE, ex);
            }
        });
        helpers = Map.copyOf(loaded);
    }

    public boolean has(String id) {
        return helpers.containsKey(id);
    }

    /** Applies a helper's spawn effect at {@code at}. The owner is never hurt or pushed by its own helpers. */
    public boolean apply(ServerLevel level, String id, Vec3 at, LivingEntity owner, Entity direct) {
        Helper h = helpers.get(id);
        if (h == null) return false;
        if (h.explode() != null) explode(level, h.explode(), at, owner, direct);
        if (h.roar() != null) roar(level, h.roar(), at, owner);
        return true;
    }

    static void explode(ServerLevel level, Explode ex, Vec3 at, LivingEntity owner, Entity direct) {
        DamageSource src = new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(DamageKind.EXPLOSION.key()), direct, owner);
        ExplosionDamageCalculator calc = new ExplosionDamageCalculator() {
            @Override
            public boolean shouldDamageEntity(Explosion explosion, Entity e) {
                return e != owner && !(e instanceof TechniqueProjectile) && !iframed(e);
            }

            @Override
            public float getKnockbackMultiplier(Entity e) {
                return e == owner || iframed(e) ? 0 : 1;
            }
        };
        boolean breaks = ex.breaks() && Carve.griefingAllowed(level);
        level.explode(direct, src, calc, at.x, at.y, at.z, ex.power(), ex.fire() && breaks,
                breaks ? Level.ExplosionInteraction.MOB : Level.ExplosionInteraction.NONE);
    }

    /** Bedrock knockback_roar: everything in range is thrown away from the centre. */
    static void roar(ServerLevel level, Roar roar, Vec3 at, LivingEntity owner) {
        double r = roar.range();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(r))) {
            if (e == owner || !e.isAlive() || e.isSpectator() || iframed(e)) continue;
            if (e instanceof Player p && p.isCreative()) continue;
            Vec3 d = e.position().subtract(at);
            double dist = d.length();
            if (dist > r) continue;
            Vec3 dir = dist < 1e-3 ? new Vec3(0, 0, 0) : new Vec3(d.x, 0, d.z).normalize();
            double s = roar.strength() * CombatDamage.KNOCKBACK_SCALE;
            e.setDeltaMovement(dir.x * s, Math.max(e.getDeltaMovement().y, 0.5), dir.z * s);
            e.hurtMarked = true;
        }
    }

    static boolean iframed(Entity e) {
        return e instanceof LivingEntity l && l.hasData(CRBAttachments.COMBAT) && l.getData(CRBAttachments.COMBAT).invulnerable();
    }
}
