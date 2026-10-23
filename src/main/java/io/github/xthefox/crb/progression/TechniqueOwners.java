package io.github.xthefox.crb.progression;

import io.github.xthefox.crb.ability.Technique;
import io.github.xthefox.crb.core.CRBConfig;
import io.github.xthefox.crb.core.CRBRegistries;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Optional;
import java.util.UUID;

/** World-wide claim book, stored with the overworld so it is shared by every dimension. */
public final class TechniqueOwners extends SavedData {
    private static final String NAME = "crb_technique_owners";
    private final Ownership book = new Ownership();

    public static TechniqueOwners get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(TechniqueOwners::new, TechniqueOwners::load, null), NAME);
    }

    private static TechniqueOwners load(CompoundTag tag, HolderLookup.Provider provider) {
        TechniqueOwners o = new TechniqueOwners();
        CompoundTag owners = tag.getCompound("owners");
        for (String key : owners.getAllKeys()) {
            ResourceLocation id = ResourceLocation.tryParse(key);
            if (id != null && owners.hasUUID(key)) o.book.putRaw(id, owners.getUUID(key));
        }
        return o;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        CompoundTag owners = new CompoundTag();
        book.all().forEach((id, uuid) -> owners.putUUID(id.toString(), uuid));
        tag.put("owners", owners);
        return tag;
    }

    public static boolean isUnique(ResourceLocation id) {
        return Ownership.isUnique(id,
                CRBConfig.SERVER.uniqueTechniques.get(),
                CRBConfig.SERVER.onlyOneSukunaVessel.get(),
                TechniqueOwners::canonUnique,
                CRBRegistries.SHRINE.getId());
    }

    private static boolean canonUnique(ResourceLocation id) {
        if (Traits.UNIQUE.contains(id)) return true;
        Technique t = CRBRegistries.TECHNIQUES.get(id);
        return t != null && CRBRegistries.TECHNIQUES.containsKey(id) && t.canonUnique();
    }

    public boolean available(ResourceLocation id, UUID player) {
        return !isUnique(id) || book.available(id, player);
    }

    public Ownership.ClaimResult claim(ResourceLocation id, UUID player) {
        Ownership.ClaimResult r = book.claim(id, player, isUnique(id));
        if (r == Ownership.ClaimResult.CLAIMED) setDirty();
        return r;
    }

    public Optional<UUID> transfer(ResourceLocation id, UUID player) {
        setDirty();
        return book.transfer(id, player);
    }

    public Optional<UUID> release(ResourceLocation id) {
        Optional<UUID> prev = book.release(id);
        if (prev.isPresent()) setDirty();
        return prev;
    }

    public Optional<UUID> owner(ResourceLocation id) {
        return book.owner(id);
    }

    public Ownership book() {
        return book;
    }
}
