package io.github.xthefox.crb.technique.limitless;

import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.ability.Ability;
import io.github.xthefox.crb.ability.AbilityService;
import io.github.xthefox.crb.ability.Tri;
import io.github.xthefox.crb.ability.Variant;
import io.github.xthefox.crb.combat.Infinity;
import io.github.xthefox.crb.combat.TargetTags;
import io.github.xthefox.crb.core.CRBConfig;
import io.github.xthefox.crb.core.CRBRegistries;
import io.github.xthefox.crb.data.CRBAttachments;
import io.github.xthefox.crb.progression.Traits;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Limitless (Satoru Gojo), from JJA's {@code limitless/limitless_click}: five technique keys with
 * every variant in JJA's gate order, plus the Blue Teleport upgrade (GJ4).
 *
 * <p>JJA's look tags are easy to misread: {@code uplook} is set whenever the player is <i>not</i>
 * looking more than 75° down, so "look-up" variants in the research tables are the normal ones
 * and {@code !uplook} variants need the player to look at the ground. {@code lookingup} is a real
 * look straight up (pitch past −80°).
 *
 * <p>Left out on purpose: JJA's two joke items in this kit ({@code ds:jfk_technique},
 * {@code ds:glocks_technique}), which reference real-world shootings.
 */
public final class Limitless {
    public static final ResourceLocation BLUE_TELEPORT_UNLOCK = CRB.id("blue_teleport");

    private Limitless() {}

    static String move(String path) {
        return "limitless/" + path;
    }

    static ResourceLocation icon(String item) {
        return CRB.id("textures/items/" + item + ".png");
    }

    public static void init() {
        CRBRegistries.LIMITLESS.get().define(List.of(
                infinity(), lapseBlue(), reversalRed(), spatial(), hollowPurple(),
                blueTeleport()
        ), null);
        LimitlessMoves.register();
        LimitlessProjectiles.register();

        // JJA tags every Limitless user `limitless`; its pulls and blasts skip them. Here that is "Infinity is up".
        TargetTags.register((t, tag) -> tag.equals("infinity") || tag.equals("limitless") ? Infinity.active(t) : null);

        // GJ2: Six Eyes' atom-level precision, -90 % cursed energy on Limitless
        AbilityService.registerCostModifier((player, v) -> CRBConfig.SERVER.gojoOverpowered.get()
                && v.moveId().startsWith("limitless/") && player.getData(CRBAttachments.SORCERER).has(Traits.SIX_EYES) ? 0.1f : 1f);

        AbilityService.registerOverride((player, ability, mods) -> {
            if (ability.id().equals("blue_teleport") && !CRBConfig.SERVER.gojoOverpowered.get()) {
                player.displayClientMessage(Component.translatable("crb.ability.disabled").withStyle(ChatFormatting.RED), true);
                return true;
            }
            // Infinity key, plain press: switch Infinity off while it is up, or back on if passive Infinity was switched off
            if (!ability.id().equals("infinity") || mods.sneak() || mods.airborne()) return false;
            if (Infinity.toggleOff(player)) return true;
            var state = player.getData(CRBAttachments.COMBAT);
            if (state.infinitySuppressed()) {
                state.suppressInfinity(false);
                player.displayClientMessage(Component.translatable("crb.infinity.on").withStyle(ChatFormatting.AQUA), true);
                return true;
            }
            return false;
        });
    }

    // ------------------------------------------------------------------ keys (JJA items ds:limitless1..5)

    static Ability infinity() {
        return new Ability("infinity", "ability.crb.infinity", 1, List.of(
                Variant.builder("form1b", move("limitless1b")).name("move.crb.infinite_pressure")
                        .sneak(Tri.YES).airborne(Tri.ANY).energy(60).cooldown("ability1", 500).duration(81).build(),
                Variant.builder("form1c", move("limitless1c")).name("move.crb.infinity_levitate")
                        .airborne(Tri.YES).energy(50).cooldown("ability1c", 0).duration(15).build(),
                Variant.builder("form1", move("limitless1")).name("ability.crb.infinity")
                        .energy(50).cooldown("ability1", 1200).duration(15).build()
        ), icon("limitless1"));
    }

    static Ability lapseBlue() {
        return new Ability("lapse_blue", "ability.crb.lapse_blue", 2, List.of(
                Variant.builder("form2e", move("limitless2e")).name("move.crb.enchanted_lapse_blue")
                        .sneak(Tri.YES).airborne(Tri.ANY).lookDown(Tri.NO).requires(Traits.SIX_EYES.toString())
                        .energy(300).cooldown("ability7", 500).duration(66).build(),
                Variant.builder("form2b", move("limitless2b")).name("move.crb.max_lapse_blue")
                        .sneak(Tri.YES).airborne(Tri.ANY).lookDown(Tri.YES).energy(250).cooldown("ability2", 400).duration(141).build(),
                Variant.builder("form2c", move("limitless2c")).name("ability.crb.lapse_blue")
                        .airborne(Tri.YES).energy(50).cooldown("ability2", 240).duration(21).build(),
                Variant.builder("form2", move("limitless2")).name("ability.crb.lapse_blue")
                        .energy(50).cooldown("ability2", 250).duration(15).build()
        ), icon("limitless2"));
    }

    static Ability reversalRed() {
        return new Ability("reversal_red", "ability.crb.reversal_red", 3, List.of(
                Variant.builder("form3e", move("limitless3e")).name("move.crb.enchanted_reversal_red")
                        .sneak(Tri.YES).airborne(Tri.ANY).requires(Traits.SIX_EYES.toString())
                        .energy(300).cooldown("ability8", 500).duration(66).build(),
                Variant.builder("form3c", move("limitless3c")).name("move.crb.max_reversal_red")
                        .airborne(Tri.YES).lookDown(Tri.NO).energy(200).cooldown("ability3", 500).duration(51).build(),
                Variant.builder("form3b", move("limitless3b")).name("ability.crb.reversal_red")
                        .airborne(Tri.ANY).lookDown(Tri.YES).energy(15).cooldown("ability3", 80).duration(16).build(),
                Variant.builder("form3", move("limitless3")).name("ability.crb.reversal_red")
                        .lookDown(Tri.NO).energy(30).cooldown("ability3", 300).duration(41).build()
        ), icon("limitless3"));
    }

    static Ability spatial() {
        return new Ability("spatial", "ability.crb.spatial", 4, List.of(
                Variant.builder("form4b", move("limitless4b")).name("move.crb.spatial_burst")
                        .sneak(Tri.YES).energy(100).cooldown("ability4", 600).duration(15).build(),
                Variant.builder("form4c", move("limitless4c")).name("move.crb.spatial_warp")
                        .airborne(Tri.YES).energy(100).cooldown("ability4", 250).duration(36).build(),
                Variant.builder("form4", move("limitless4")).name("move.crb.spatial_blitz")
                        .energy(45).cooldown("ability4", 80).duration(16).build()
        ), icon("limitless4"));
    }

    static Ability hollowPurple() {
        return new Ability("hollow_purple", "ability.crb.hollow_purple", 5, List.of(
                Variant.builder("form5d", move("limitless5d")).name("move.crb.limitless_purple")
                        .sneak(Tri.YES).airborne(Tri.ANY).lookUp(Tri.YES).energy(150).energyRequired(7500)
                        .cooldown("ability12", 32000).duration(481).build(),
                Variant.builder("form5b", move("limitless5b")).name("move.crb.hollow_purple_chant")
                        .sneak(Tri.YES).airborne(Tri.ANY).lookUp(Tri.NO).energy(150).cooldown("ability5", 2400).duration(481).build(),
                Variant.builder("form5c", move("limitless5c")).name("move.crb.hollow_purple_200")
                        .airborne(Tri.ANY).lookDown(Tri.YES).energy(3500).cooldown("ability5", 6000).duration(321).build(),
                Variant.builder("form5", move("limitless5")).name("ability.crb.hollow_purple")
                        .airborne(Tri.ANY).lookDown(Tri.NO).energy(80).cooldown("ability5", 800).duration(101).build()
        ), icon("limitless5"));
    }

    /** GJ4: canon teleportation through Blue's attraction. 3 s cooldown, line of sight only. */
    static Ability blueTeleport() {
        String unlock = BLUE_TELEPORT_UNLOCK.toString();
        return new Ability("blue_teleport", "ability.crb.blue_teleport", 4, List.of(
                Variant.builder("gj4b", move("blue_teleport_behind")).name("move.crb.blue_teleport_behind")
                        .sneak(Tri.YES).airborne(Tri.ANY).requires(unlock).energy(30).cooldown("blue_teleport", 60).duration(6).build(),
                Variant.builder("gj4c", move("blue_teleport_chain")).name("move.crb.blue_teleport_chain")
                        .airborne(Tri.YES).requires(unlock).energy(45).cooldown("blue_teleport", 60).duration(20).build(),
                Variant.builder("gj4", move("blue_teleport")).name("ability.crb.blue_teleport")
                        .requires(unlock).energy(20).cooldown("blue_teleport", 60).duration(6).build()
        ), icon("limitless4"));
    }
}
