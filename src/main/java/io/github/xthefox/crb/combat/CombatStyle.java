package io.github.xthefox.crb.combat;

import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.ability.Ability;
import io.github.xthefox.crb.ability.Tri;
import io.github.xthefox.crb.ability.Variant;
import io.github.xthefox.crb.data.CRBAttachments;
import io.github.xthefox.crb.data.SorcererData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A JJA combat style ({@code ds:basic_combat}, {@code ds:limitless_fists}, ...): the M1 string and
 * the style's "arts" on the style-art key (JJA: right-click with the style item, variants by
 * sneak / airborne like techniques).
 *
 * @param windups ticks each of the four M1s takes (JJA adds 8/8/9/10 to {@code BasicM1})
 * @param animations animation per M1 hit
 */
public record CombatStyle(ResourceLocation id, int[] windups, String[] animations, float damage, double reach, double radius,
                          String swingSound, String hitSound, DamageKind kind, Ability arts) {
    /** JJA lands the hit when the countdown reaches 3. */
    public static final int HIT_AT = 3;
    public static final int STRING_RESET = 25, END_LAG = 15;

    private static final Map<ResourceLocation, CombatStyle> STYLES = new LinkedHashMap<>();

    public static final CombatStyle BASIC = register(new CombatStyle(CRB.id("basic_combat"), new int[]{8, 8, 9, 10},
            new String[]{"animation.basic_attack1", "animation.basic_attack2", "animation.basic_attack3", "animation.basic_attack4"},
            4, 1.85, 1.4, "mob.witch.throw", "mob.punch", DamageKind.BLUNT,
            new Ability("basic_arts", "ability.crb.basic_arts", 0, List.of(
                    Variant.builder("wep1", "fighting/basic/strong_punch").name("move.crb.strong_punch").cooldown("wep1", 201).duration(21).build(),
                    Variant.builder("wep2", "fighting/basic/rush").name("move.crb.rush").sneak(Tri.YES).cooldown("wep2", 201).duration(31).build(),
                    Variant.builder("wep3", "fighting/basic/downslam").name("move.crb.downslam").airborne(Tri.YES).cooldown("wep3", 201).duration(17).build()))));

    public static CombatStyle register(CombatStyle style) {
        STYLES.put(style.id(), style);
        return style;
    }

    public static CombatStyle get(ResourceLocation id) {
        return STYLES.get(id);
    }

    /** The entity's style: the first style it has unlocked, else Basic Fist Combat. */
    public static CombatStyle of(LivingEntity e) {
        if (!e.hasData(CRBAttachments.SORCERER)) return BASIC;
        SorcererData d = e.getData(CRBAttachments.SORCERER);
        for (ResourceLocation t : d.traits()) {
            CombatStyle s = STYLES.get(t);
            if (s != null) return s;
        }
        return BASIC;
    }

    public int windup(int hit) {
        return windups[Math.max(0, Math.min(windups.length - 1, hit - 1))];
    }

    public String animation(int hit) {
        return animations[Math.max(0, Math.min(animations.length - 1, hit - 1))];
    }
}
