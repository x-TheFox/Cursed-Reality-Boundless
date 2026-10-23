package io.github.xthefox.crb.core;

import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.ability.Technique;
import io.github.xthefox.crb.ability.Technique.Rarity;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NewRegistryEvent;
import net.neoforged.neoforge.registries.RegistryBuilder;

/** Custom registries and the technique list (every JJA technique plus the stubbed ones). */
public final class CRBRegistries {
    public static final ResourceKey<Registry<Technique>> TECHNIQUE_KEY = ResourceKey.createRegistryKey(CRB.id("technique"));
    public static final ResourceLocation NO_TECHNIQUE = CRB.id("none");
    public static final Registry<Technique> TECHNIQUES = new RegistryBuilder<>(TECHNIQUE_KEY)
            .sync(true)
            .defaultKey(NO_TECHNIQUE)
            .create();

    private static final DeferredRegister<Technique> TECH = DeferredRegister.create(TECHNIQUE_KEY, CRB.MOD_ID);

    public static final DeferredHolder<Technique, Technique> NONE = tech("none", Rarity.COMMON, false, 0);

    // Honored / Dishonored (named, clan or curse techniques)
    public static final DeferredHolder<Technique, Technique> LIMITLESS = tech("limitless", Rarity.HONORED, true, 6);
    public static final DeferredHolder<Technique, Technique> TEN_SHADOWS = tech("ten_shadows", Rarity.HONORED, true, 12);
    public static final DeferredHolder<Technique, Technique> MYTHICAL_BEAST_AMBER = tech("mythical_beast_amber", Rarity.HONORED, true, 5);
    public static final DeferredHolder<Technique, Technique> DISASTER_FLAMES = tech("disaster_flames", Rarity.HONORED, true, 7);
    public static final DeferredHolder<Technique, Technique> IDLE_TRANSFIGURATION = tech("idle_transfiguration", Rarity.HONORED, true, 5);
    // Exotic
    public static final DeferredHolder<Technique, Technique> JUDGEMAN = tech("judgeman", Rarity.EXOTIC, true, 5);
    public static final DeferredHolder<Technique, Technique> LOVE_TRAIN = tech("love_train", Rarity.EXOTIC, true, 5);
    public static final DeferredHolder<Technique, Technique> QUEEN_OF_CURSES = tech("queen_of_curses", Rarity.EXOTIC, true, 5);
    // Rare
    public static final DeferredHolder<Technique, Technique> CURSED_SPEECH = tech("cursed_speech", Rarity.RARE, true, 3);
    public static final DeferredHolder<Technique, Technique> ABSORPTION = tech("absorption", Rarity.RARE, false, 3);
    public static final DeferredHolder<Technique, Technique> BOOGIE_WOOGIE = tech("boogie_woogie", Rarity.RARE, true, 3);
    // Uncommon / common
    public static final DeferredHolder<Technique, Technique> EXPLOSIVE = tech("explosive", Rarity.UNCOMMON, false, 3);
    public static final DeferredHolder<Technique, Technique> WIND_UP = tech("wind_up", Rarity.UNCOMMON, false, 1);
    public static final DeferredHolder<Technique, Technique> IMMOBILIZING_SIGHT = tech("immobilizing_sight", Rarity.COMMON, false, 1);
    public static final DeferredHolder<Technique, Technique> MIRACLES = tech("miracles", Rarity.COMMON, false, 1);
    public static final DeferredHolder<Technique, Technique> PAIN_KILLER = tech("pain_killer", Rarity.COMMON, false, 1);
    // Not rolled: obtained through traits, fingers or story
    public static final DeferredHolder<Technique, Technique> SHRINE = tech("shrine", Rarity.UNROLLABLE, true, 6);
    public static final DeferredHolder<Technique, Technique> SOUL_SHRINE = tech("soul_shrine", Rarity.UNROLLABLE, true, 3);
    public static final DeferredHolder<Technique, Technique> HEAVENLY_RESTRICTION = tech("heavenly_restriction", Rarity.EXOTIC, false, 3);
    public static final DeferredHolder<Technique, Technique> BLOOD_MANIPULATION = tech("blood_manipulation", Rarity.UNROLLABLE, true, 6);

    private CRBRegistries() {}

    private static DeferredHolder<Technique, Technique> tech(String name, Rarity rarity, boolean unique, int forms) {
        return TECH.register(name, () -> new Technique(rarity, unique, forms));
    }

    public static void register(IEventBus modBus) {
        modBus.addListener((NewRegistryEvent e) -> e.register(TECHNIQUES));
        TECH.register(modBus);
    }

    public static Technique technique(ResourceLocation id) {
        return TECHNIQUES.get(id);
    }
}
