package io.github.xthefox.crb.move;

import io.github.xthefox.crb.core.CRBRegistries;
import io.github.xthefox.crb.data.CRBAttachments;
import io.github.xthefox.crb.data.SorcererData;
import io.github.xthefox.crb.progression.Traits;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Translates our data into the tag names JJA's commands test ({@code tag=limitless},
 * {@code tag=sixeyes} ...), so extracted cue conditions can be evaluated on clients.
 */
public final class JjaTags {
    /** Technique registry path → JJA technique tag. */
    private static final Map<String, String> TECHNIQUE_TAGS = Map.ofEntries(
            Map.entry("limitless", "limitless"), Map.entry("ten_shadows", "shikigami"),
            Map.entry("mythical_beast_amber", "lightning"), Map.entry("disaster_flames", "flames"),
            Map.entry("idle_transfiguration", "idle_transfig"), Map.entry("judgeman", "judgeman"),
            Map.entry("love_train", "lovetrain"), Map.entry("cursed_speech", "cursed_speech"),
            Map.entry("absorption", "absorption"), Map.entry("boogie_woogie", "boogie_woogie"),
            Map.entry("explosive", "explosive"), Map.entry("shrine", "sukuna"),
            Map.entry("soul_shrine", "yuji_awakened"), Map.entry("heavenly_restriction", "heavenlyrestriction"),
            Map.entry("wind_up", "windup"), Map.entry("immobilizing_sight", "sight"),
            Map.entry("miracles", "miracles"), Map.entry("pain_killer", "painkiller"),
            Map.entry("queen_of_curses", "queen_of_curses"), Map.entry("blood_manipulation", "blood_manipulation"));

    /** Trait id → JJA tag. */
    private static final Map<ResourceLocation, String> TRAIT_TAGS = Map.ofEntries(
            Map.entry(Traits.SIX_EYES, "sixeyes"), Map.entry(Traits.RCT, "reversecursed"),
            Map.entry(Traits.HEIAN_ERA, "heian_era"), Map.entry(Traits.CURSE, "curse"),
            Map.entry(Traits.HEAVENLY_RESTRICTION, "heavenlyrestriction"), Map.entry(Traits.ROUGH_CE, "roughce"),
            Map.entry(Traits.FASTER_CHARGE, "fasterce"), Map.entry(Traits.EXOSKELETON, "exoskeleton"),
            Map.entry(Traits.SATIATED, "satiated"), Map.entry(Traits.INSANITY, "insanity"),
            Map.entry(Traits.UNDYING_WILL, "undying"), Map.entry(Traits.DELUSIONAL_IDOL_FAN, "delusional_trait"),
            Map.entry(Traits.MEGUNA, "meguna"));

    private JjaTags() {}

    public static Set<String> of(LivingEntity entity) {
        Set<String> tags = new HashSet<>();
        if (entity instanceof Player) tags.add("player");
        if (!entity.hasData(CRBAttachments.SORCERER)) return tags;
        SorcererData d = entity.getData(CRBAttachments.SORCERER);
        if (d.hasTechnique()) {
            String tag = TECHNIQUE_TAGS.get(d.technique().getPath());
            if (tag != null) tags.add(tag);
        }
        if (d.isVessel()) tags.add("has_sukuna");
        if (d.technique().equals(CRBRegistries.SHRINE.getId())) tags.add("has_sukuna");
        for (ResourceLocation t : d.traits()) {
            String tag = TRAIT_TAGS.get(t);
            if (tag != null) tags.add(tag);
        }
        return tags;
    }
}
