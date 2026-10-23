package io.github.xthefox.crb.client.input;

import io.github.xthefox.crb.ability.Ability;
import io.github.xthefox.crb.ability.AbilityService;
import io.github.xthefox.crb.ability.Modifiers;
import io.github.xthefox.crb.ability.Technique;
import io.github.xthefox.crb.core.CRBRegistries;
import io.github.xthefox.crb.data.CRBAttachments;
import io.github.xthefox.crb.data.SorcererData;
import io.github.xthefox.crb.net.MovePayloads;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Optional;

/**
 * Combat Mode replaces JJA's technique hotbar items. While enabled, the number keys 1-5 cast the
 * current technique page's skills and 6 casts the domain; the mouse wheel changes pages. The
 * hotbar itself is left alone (cursed tools are real items).
 */
public final class CombatMode {
    private static boolean enabled;
    private static int page;

    private CombatMode() {}

    public static boolean enabled() {
        return enabled;
    }

    public static int page() {
        return page;
    }

    public static void setPage(int p) {
        Technique t = technique();
        page = t == null ? 0 : Math.floorMod(p, t.pages());
    }

    public static Technique technique() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return null;
        SorcererData d = p.getData(CRBAttachments.SORCERER);
        return d.hasTechnique() ? CRBRegistries.technique(d.technique()) : null;
    }

    /** Current input state, for the HUD's variant preview and for casts. */
    public static Modifiers modifiers() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return Modifiers.NONE;
        return Modifiers.fromPitch(p.isShiftKeyDown(), !p.onGround(), p.getXRot());
    }

    public static Optional<Ability> abilityAt(int key) {
        if (key == AbilityService.STYLE_ART_KEY) {
            LocalPlayer p = Minecraft.getInstance().player;
            return p == null ? Optional.empty() : Optional.of(io.github.xthefox.crb.combat.CombatStyle.of(p).arts());
        }
        Technique t = technique();
        if (t == null) return Optional.empty();
        return key == AbilityService.DOMAIN_KEY ? t.domain() : t.abilityAt(page, key);
    }

    /** Runs before vanilla handles keybinds, so hotbar keys can be taken over in Combat Mode. */
    public static void preTick(ClientTickEvent.Pre e) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.screen != null) return;

        while (CRBKeys.COMBAT_MODE.consumeClick()) {
            enabled = !enabled;
            player.displayClientMessage(Component.translatable(enabled ? "crb.combat_mode.on" : "crb.combat_mode.off"), true);
            player.playSound(enabled ? SoundEvents.ARMOR_EQUIP_NETHERITE.value() : SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.6f, 1.2f);
        }
        while (CRBKeys.RADIAL.consumeClick()) {
            if (technique() != null) mc.setScreen(new RadialMenuScreen());
        }
        for (int i = 0; i < CRBKeys.ABILITY.length; i++) {
            while (CRBKeys.ABILITY[i].consumeClick()) cast(i);
        }
        if (!enabled) return;
        KeyMapping[] hotbar = mc.options.keyHotbarSlots;
        for (int i = 0; i < 6; i++) {
            while (hotbar[i].consumeClick()) cast(i);
        }
        while (CRBKeys.STYLE_ART.consumeClick()) cast(AbilityService.STYLE_ART_KEY);
        while (CRBKeys.DASH.consumeClick()) {
            var o = mc.options;
            int dir = (o.keyUp.isDown() ? 1 : 0) | (o.keyDown.isDown() ? 2 : 0) | (o.keyLeft.isDown() ? 4 : 0) | (o.keyRight.isDown() ? 8 : 0);
            PacketDistributor.sendToServer(new MovePayloads.Movement(MovePayloads.Movement.DASH, dir));
        }
        while (CRBKeys.EVADE.consumeClick()) PacketDistributor.sendToServer(new MovePayloads.Movement(MovePayloads.Movement.EVADE, 0));
        boolean jump = mc.options.keyJump.isDown();
        if (jump && !jumpWasDown && !player.onGround() && !player.getAbilities().flying && !player.isInWater() && !player.onClimbable()) {
            PacketDistributor.sendToServer(new MovePayloads.Movement(MovePayloads.Movement.DOUBLE_JUMP, 0));
        }
        jumpWasDown = jump;
    }

    private static boolean jumpWasDown;

    /**
     * In Combat Mode with an empty main hand, attack is an M1 and use is the style art (JJA:
     * left/right click with the combat-style item). Holding an item keeps vanilla behaviour.
     */
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered e) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || mc.player == null || !mc.player.getMainHandItem().isEmpty()) return;
        if (e.isAttack()) {
            PacketDistributor.sendToServer(new MovePayloads.Melee(MovePayloads.Melee.M1));
            e.setSwingHand(false);
            e.setCanceled(true);
        } else if (e.isUseItem() && e.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND) {
            cast(AbilityService.STYLE_ART_KEY);
            e.setSwingHand(false);
            e.setCanceled(true);
        }
    }

    public static void cast(int key) {
        if (abilityAt(key).isEmpty()) return;
        PacketDistributor.sendToServer(new MovePayloads.AbilityInput(page, key, modifiers().toBits()));
    }

    /** Mouse wheel flips technique pages instead of hotbar slots while in Combat Mode. */
    public static void onScroll(InputEvent.MouseScrollingEvent e) {
        if (!enabled || Minecraft.getInstance().screen != null) return;
        Technique t = technique();
        if (t == null || t.pages() <= 1) return;
        setPage(page + (e.getScrollDeltaY() > 0 ? -1 : 1));
        e.setCanceled(true);
    }

    /** For scripted checks (dev harness). */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static void reset() {
        enabled = false;
        page = 0;
    }
}
