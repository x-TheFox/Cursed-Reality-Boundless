package io.github.xthefox.crb.data;

import io.github.xthefox.crb.core.CRBRegistries;
import io.github.xthefox.crb.progression.Grade;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.util.INBTSerializable;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Persistent sorcerer state (survives death). Replaces JJA's per-player scoreboards: Energy,
 * EnergyCap, strength/endurance/agility, invest, grade, exp, forms, finger, ability cooldowns,
 * and the trait/unlock tags.
 */
public final class SorcererData implements INBTSerializable<CompoundTag> {
    public static final int DEFAULT_ENERGY_CAP = 300;

    private ResourceLocation technique = CRBRegistries.NO_TECHNIQUE;
    private int forms;
    private int energy;
    private int energyCap = DEFAULT_ENERGY_CAP;
    private int strength, endurance, agility;
    private int investPoints;
    private int level = 1;
    private int exp;
    private Grade grade = Grade.GRADE_4;
    private int fingers;
    private boolean vessel;
    private int page;
    private final Set<ResourceLocation> traits = new LinkedHashSet<>();
    private final Map<String, Integer> cooldowns = new HashMap<>();
    private boolean dirty = true;

    // --- technique ---
    public ResourceLocation technique() { return technique; }
    public boolean hasTechnique() { return !CRBRegistries.NO_TECHNIQUE.equals(technique); }
    public void setTechnique(ResourceLocation id, int forms) { technique = id; this.forms = forms; page = 0; markDirty(); }
    public void clearTechnique() { setTechnique(CRBRegistries.NO_TECHNIQUE, 0); }
    public int forms() { return forms; }
    public void setForms(int f) { forms = Math.max(0, f); markDirty(); }
    public int page() { return page; }
    public void setPage(int p) { page = Math.max(0, p); markDirty(); }

    // --- energy ---
    public int energy() { return energy; }
    public int energyCap() { return energyCap; }
    public void setEnergy(int e) { int v = Math.min(e, energyCap); if (v != energy) { energy = v; markDirty(); } }
    public void addEnergy(int e) { setEnergy(energy + e); }
    public void setEnergyCap(int cap) { energyCap = Math.max(0, cap); energy = Math.min(energy, energyCap); markDirty(); }

    // --- stats ---
    public int strength() { return strength; }
    public int endurance() { return endurance; }
    public int agility() { return agility; }
    public void setStrength(int v) { strength = Math.max(0, v); markDirty(); }
    public void setEndurance(int v) { endurance = Math.max(0, v); markDirty(); }
    public void setAgility(int v) { agility = Math.max(0, v); markDirty(); }
    public int investPoints() { return investPoints; }
    public void setInvestPoints(int v) { investPoints = Math.max(0, v); markDirty(); }
    public int level() { return level; }
    public void setLevel(int v) { level = Math.max(1, v); markDirty(); }
    public int exp() { return exp; }
    public void setExp(int v) { exp = Math.max(0, v); markDirty(); }
    public Grade grade() { return grade; }
    public void setGrade(Grade g) { grade = g; markDirty(); }

    // --- Sukuna vessel ---
    public int fingers() { return fingers; }
    public void setFingers(int f) { fingers = Math.max(0, Math.min(20, f)); markDirty(); }
    public boolean isVessel() { return vessel; }
    public void setVessel(boolean v) { vessel = v; markDirty(); }

    // --- traits & unlocks ---
    public boolean has(ResourceLocation trait) { return traits.contains(trait); }
    public Set<ResourceLocation> traits() { return Set.copyOf(traits); }
    public boolean addTrait(ResourceLocation t) { boolean b = traits.add(t); if (b) markDirty(); return b; }
    public boolean removeTrait(ResourceLocation t) { boolean b = traits.remove(t); if (b) markDirty(); return b; }
    public void clearTraits() { traits.clear(); markDirty(); }

    // --- cooldowns ---
    public int cooldown(String key) { return cooldowns.getOrDefault(key, 0); }
    public void setCooldown(String key, int ticks) {
        if (ticks <= 0) cooldowns.remove(key); else cooldowns.put(key, ticks);
        markDirty();
    }
    public Map<String, Integer> cooldowns() { return Map.copyOf(cooldowns); }
    public void clearCooldowns() { cooldowns.clear(); markDirty(); }

    /** Decrements all cooldowns by one tick. Does not mark dirty: clients count down locally. */
    public void tickCooldowns() {
        cooldowns.replaceAll((k, v) -> v - 1);
        cooldowns.values().removeIf(v -> v <= 0);
    }

    // --- sync bookkeeping ---
    public boolean isDirty() { return dirty; }
    public void markDirty() { dirty = true; }
    public void clearDirty() { dirty = false; }

    /** Resets everything except identity fields an operator might want to keep (used by Reset Ticket). */
    public void wipe() {
        clearTechnique();
        energy = 0; energyCap = DEFAULT_ENERGY_CAP;
        strength = endurance = agility = investPoints = exp = 0; level = 1;
        grade = Grade.GRADE_4; fingers = 0; vessel = false;
        traits.clear(); cooldowns.clear();
        markDirty();
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag t = new CompoundTag();
        t.putString("technique", technique.toString());
        t.putInt("forms", forms);
        t.putInt("page", page);
        t.putInt("energy", energy);
        t.putInt("energyCap", energyCap);
        t.putInt("strength", strength);
        t.putInt("endurance", endurance);
        t.putInt("agility", agility);
        t.putInt("invest", investPoints);
        t.putInt("level", level);
        t.putInt("exp", exp);
        t.putString("grade", grade.name());
        t.putInt("fingers", fingers);
        t.putBoolean("vessel", vessel);
        ListTag tr = new ListTag();
        traits.forEach(r -> tr.add(StringTag.valueOf(r.toString())));
        t.put("traits", tr);
        CompoundTag cd = new CompoundTag();
        cooldowns.forEach(cd::putInt);
        t.put("cooldowns", cd);
        return t;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag t) {
        ResourceLocation tech = ResourceLocation.tryParse(t.getString("technique"));
        technique = tech == null ? CRBRegistries.NO_TECHNIQUE : tech;
        forms = t.getInt("forms");
        page = t.getInt("page");
        energyCap = t.contains("energyCap") ? t.getInt("energyCap") : DEFAULT_ENERGY_CAP;
        energy = t.getInt("energy");
        strength = t.getInt("strength");
        endurance = t.getInt("endurance");
        agility = t.getInt("agility");
        investPoints = t.getInt("invest");
        level = Math.max(1, t.getInt("level"));
        exp = t.getInt("exp");
        try { grade = Grade.valueOf(t.getString("grade")); } catch (IllegalArgumentException e) { grade = Grade.GRADE_4; }
        fingers = t.getInt("fingers");
        vessel = t.getBoolean("vessel");
        traits.clear();
        for (Tag e : t.getList("traits", Tag.TAG_STRING)) {
            ResourceLocation r = ResourceLocation.tryParse(e.getAsString());
            if (r != null) traits.add(r);
        }
        cooldowns.clear();
        CompoundTag cd = t.getCompound("cooldowns");
        for (String k : cd.getAllKeys()) cooldowns.put(k, cd.getInt(k));
        dirty = true;
    }
}
