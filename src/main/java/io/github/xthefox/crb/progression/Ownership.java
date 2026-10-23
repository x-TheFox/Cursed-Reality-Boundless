package io.github.xthefox.crb.progression;

import io.github.xthefox.crb.core.CRBConfig.Uniqueness;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Claim book for unique techniques and traits (MASTER_PLAN §8b). Pure logic so it can be unit
 * tested; {@link TechniqueOwners} persists it in the world.
 */
public final class Ownership {
    public enum ClaimResult { NOT_UNIQUE, CLAIMED, ALREADY_YOURS, TAKEN }

    private final Map<ResourceLocation, UUID> owners = new HashMap<>();

    /**
     * @param mode              server's {@code uniqueTechniques} setting
     * @param oneSukuna         server's {@code onlyOneSukunaVessel} setting (JJA's own rule; applies even when mode is OFF)
     * @param canonUnique       whether an id is a named character's technique or an honored trait
     */
    public static boolean isUnique(ResourceLocation id, Uniqueness mode, boolean oneSukuna, Predicate<ResourceLocation> canonUnique,
                                   ResourceLocation shrineId) {
        if (oneSukuna && id.equals(shrineId)) return true;
        return switch (mode) {
            case OFF -> false;
            case CANON -> canonUnique.test(id);
            case ALL -> true;
        };
    }

    public Optional<UUID> owner(ResourceLocation id) {
        return Optional.ofNullable(owners.get(id));
    }

    /** Whether {@code player} may hold {@code id} right now (free, or already theirs). */
    public boolean available(ResourceLocation id, UUID player) {
        UUID o = owners.get(id);
        return o == null || o.equals(player);
    }

    /** Claims {@code id} for {@code player} if it is unique and free. Non-unique ids are never recorded. */
    public ClaimResult claim(ResourceLocation id, UUID player, boolean unique) {
        if (!unique) return ClaimResult.NOT_UNIQUE;
        UUID o = owners.get(id);
        if (o == null) { owners.put(id, player); return ClaimResult.CLAIMED; }
        return o.equals(player) ? ClaimResult.ALREADY_YOURS : ClaimResult.TAKEN;
    }

    /** Operator transfer: overrides any existing claim. Returns the previous owner. */
    public Optional<UUID> transfer(ResourceLocation id, UUID player) {
        return Optional.ofNullable(owners.put(id, player));
    }

    public Optional<UUID> release(ResourceLocation id) {
        return Optional.ofNullable(owners.remove(id));
    }

    public Map<ResourceLocation, UUID> all() {
        return Map.copyOf(owners);
    }

    void putRaw(ResourceLocation id, UUID player) {
        owners.put(id, player);
    }
}
