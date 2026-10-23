package io.github.xthefox.crb.progression;

import io.github.xthefox.crb.CRB;
import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/**
 * Trait and unlock ids. Traits come from JJA's roll table (catalog/roll_tables.md); unlocks are
 * learned skills (RCT, CE arts) and kit-granted extras.
 */
public final class Traits {
    // Honored
    public static final ResourceLocation SIX_EYES = CRB.id("six_eyes");
    public static final ResourceLocation PERFECT_VESSEL = CRB.id("perfect_vessel");
    // Exotic
    public static final ResourceLocation HEAVENLY_RESTRICTION = CRB.id("heavenly_restriction");
    public static final ResourceLocation HEAVENLY_RESTRICTION_ZENIN = CRB.id("heavenly_restriction_zenin");
    public static final ResourceLocation HEAVENLY_RESTRICTION_FUSHIGURO = CRB.id("heavenly_restriction_fushiguro");
    public static final ResourceLocation POTENTIAL_120 = CRB.id("potential_120");
    public static final ResourceLocation DELUSIONAL_IDOL_FAN = CRB.id("delusional_idol_fan");
    public static final ResourceLocation PRINCE_OF_BLACK_SPARKS = CRB.id("prince_of_black_sparks");
    // Rare
    public static final ResourceLocation ROUGH_CE = CRB.id("rough_ce");
    public static final ResourceLocation BLESSED_STRENGTH = CRB.id("blessed_strength");
    public static final ResourceLocation RCT_MASTER = CRB.id("rct_master");
    public static final ResourceLocation PRODIGY = CRB.id("prodigy");
    public static final ResourceLocation RESTLESS_GAMBLER = CRB.id("restless_gambler");
    public static final ResourceLocation BOUNDLESS_ENERGY = CRB.id("heavenly_restriction_boundless_energy");
    public static final ResourceLocation UNDYING_WILL = CRB.id("undying_will");
    public static final ResourceLocation INSANITY = CRB.id("insanity");
    // Uncommon
    public static final ResourceLocation IQ_530K = CRB.id("iq_530k");
    public static final ResourceLocation TACTICAL_INTELLECT = CRB.id("tactical_intellect");
    public static final ResourceLocation EXOSKELETON = CRB.id("exoskeleton");
    public static final ResourceLocation SATIATED = CRB.id("satiated");
    public static final ResourceLocation LONE_WOLF = CRB.id("lone_wolf");
    // Common
    public static final ResourceLocation ENHANCED_STRENGTH = CRB.id("enhanced_strength");
    public static final ResourceLocation QUICK_FEET = CRB.id("quick_feet");
    public static final ResourceLocation FASTER_CHARGE = CRB.id("faster_charge");
    public static final ResourceLocation ALLEY_CAT = CRB.id("alley_cat");
    // Body / clan / state tags used by JJA logic
    public static final ResourceLocation CURSE = CRB.id("curse");
    public static final ResourceLocation HEIAN_ERA = CRB.id("heian_era");

    // Unlocks
    public static final ResourceLocation RCT = CRB.id("rct");
    public static final ResourceLocation DIVERGENT_FIST = CRB.id("divergent_fist");
    public static final ResourceLocation SIMPLE_DOMAIN = CRB.id("simple_domain");
    public static final ResourceLocation DOMAIN_AMPLIFICATION = CRB.id("domain_amplification");
    public static final ResourceLocation HOLLOW_WICKER_BASKET = CRB.id("hollow_wicker_basket");
    public static final ResourceLocation FALLING_BLOSSOM_EMOTION = CRB.id("falling_blossom_emotion");
    public static final ResourceLocation INFINITY_PASSIVE = CRB.id("infinity_passive");
    public static final ResourceLocation BLUE_TELEPORT = CRB.id("blue_teleport");
    public static final ResourceLocation BLUE_FLIGHT = CRB.id("blue_flight");
    public static final ResourceLocation BLACK_FLASH_MASTERY = CRB.id("black_flash_mastery");
    public static final ResourceLocation MEGUNA = CRB.id("meguna");

    /** Traits that are claimable like techniques when {@code uniqueTechniques} is not OFF. */
    public static final Set<ResourceLocation> UNIQUE = Set.of(SIX_EYES, PERFECT_VESSEL);

    private static final java.util.List<ResourceLocation> ALL = java.util.Arrays.stream(Traits.class.getDeclaredFields())
            .filter(f -> java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == ResourceLocation.class)
            .map(f -> {
                try { return (ResourceLocation) f.get(null); }
                catch (IllegalAccessException e) { throw new IllegalStateException(e); }
            })
            .toList();

    private Traits() {}

    /** Every known trait and unlock id, for command suggestions. */
    public static java.util.List<ResourceLocation> all() {
        return ALL;
    }
}
