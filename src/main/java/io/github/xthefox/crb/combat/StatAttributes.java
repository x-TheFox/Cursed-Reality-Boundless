package io.github.xthefox.crb.combat;

import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.data.CRBAttachments;
import io.github.xthefox.crb.data.SorcererData;
import io.github.xthefox.crb.progression.Traits;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Stats as attributes instead of JJA's permanent potion effects:
 * <ul>
 *   <li>Endurance: health_boost amplifier endurance + 4 → +4 × (endurance + 5) max health.</li>
 *   <li>Agility: jump boost 0/1/2 at 15/30/45; sprinting speed 1-5 levels at 10/20/30/40/50
 *       (Heian Sukuna: level 16).</li>
 * </ul>
 */
public final class StatAttributes {
    static final ResourceLocation HEALTH = CRB.id("endurance"), JUMP = CRB.id("agility_jump"), SPRINT = CRB.id("agility_sprint");

    private StatAttributes() {}

    public static void apply(ServerPlayer p) {
        SorcererData d = p.getData(CRBAttachments.SORCERER);
        int end = d.endurance();
        set(p, Attributes.MAX_HEALTH, HEALTH, end > 0 ? 4.0 * (end + 5) : 0, AttributeModifier.Operation.ADD_VALUE, true);
        if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
        int agi = d.agility();
        int jump = agi >= 45 ? 3 : agi >= 30 ? 2 : agi >= 15 ? 1 : 0;
        set(p, Attributes.JUMP_STRENGTH, JUMP, 0.1 * jump, AttributeModifier.Operation.ADD_VALUE, true);
    }

    /** Sprint speed tiers; refreshed every tick because they depend on sprinting. */
    public static void tickSprint(ServerPlayer p) {
        SorcererData d = p.getData(CRBAttachments.SORCERER);
        int agi = d.agility();
        int level = d.has(Traits.HEIAN_ERA) ? 16 : agi >= 50 ? 5 : agi >= 40 ? 4 : agi >= 30 ? 3 : agi >= 20 ? 2 : agi >= 10 ? 1 : 0;
        set(p, Attributes.MOVEMENT_SPEED, SPRINT, p.isSprinting() ? 0.2 * level : 0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE, false);
    }

    /** @param permanent saved with the player (max health must survive relogging), else transient */
    private static void set(ServerPlayer p, Holder<Attribute> attr, ResourceLocation id, double value, AttributeModifier.Operation op,
                            boolean permanent) {
        AttributeInstance inst = p.getAttribute(attr);
        if (inst == null) return;
        AttributeModifier current = inst.getModifier(id);
        if (value == 0) {
            if (current != null) inst.removeModifier(id);
            return;
        }
        if (current != null && current.amount() == value) return;
        AttributeModifier mod = new AttributeModifier(id, value, op);
        if (permanent) {
            inst.removeModifier(id);
            inst.addPermanentModifier(mod);
        } else {
            inst.addOrUpdateTransientModifier(mod);
        }
    }
}
