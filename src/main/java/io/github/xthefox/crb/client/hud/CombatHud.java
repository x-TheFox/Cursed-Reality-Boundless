package io.github.xthefox.crb.client.hud;

import io.github.xthefox.crb.ability.Ability;
import io.github.xthefox.crb.ability.AbilityService;
import io.github.xthefox.crb.ability.Modifiers;
import io.github.xthefox.crb.ability.Technique;
import io.github.xthefox.crb.ability.Variant;
import io.github.xthefox.crb.client.input.CombatMode;
import io.github.xthefox.crb.data.CRBAttachments;
import io.github.xthefox.crb.data.SorcererData;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.Optional;

/**
 * Cursed energy bar and, in Combat Mode, the six skill slots (keys 1-6) with JJA's skill icons,
 * cooldown sweeps and a preview of which variant each key fires with the current stance
 * (sneak / airborne / look-up), replacing JJA's hotbar items.
 */
public final class CombatHud {
    private static final int SLOT = 22;
    private static final int ENERGY_COLOR = 0xFF6A3FD8, ENERGY_BG = 0xAA140A24;

    private CombatHud() {}

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.options.hideGui) return;
        SorcererData d = p.getData(CRBAttachments.SORCERER);
        int w = g.guiWidth(), h = g.guiHeight();
        Font font = mc.font;

        // ---- cursed energy (above the hotbar, left side)
        if (d.energyCap() > 0) {
            int bw = 90, bx = w / 2 - 91, by = h - 49 - (p.isCreative() ? 0 : 10);
            float frac = Mth.clamp(d.energy() / (float) d.energyCap(), 0, 1);
            g.fill(bx, by, bx + bw, by + 4, ENERGY_BG);
            g.fill(bx, by, bx + (int) (bw * frac), by + 4, ENERGY_COLOR);
            if (CombatMode.enabled()) {
                g.drawString(font, d.energy() + " CE", bx, by - 9, 0xD0C0FF, true);
            }
        }

        if (!CombatMode.enabled()) return;
        Technique tech = CombatMode.technique();
        int total = 6 * SLOT + 5 * 2, x0 = w - total - 6, y0 = h - SLOT - 6;
        if (tech == null) {
            g.drawString(font, Component.translatable("crb.combat_mode.no_technique"), x0, y0 + 6, 0xA0A0A0, true);
            return;
        }
        Modifiers mods = CombatMode.modifiers();
        for (int key = 0; key < 6; key++) {
            int x = x0 + key * (SLOT + 2);
            Optional<Ability> ab = CombatMode.abilityAt(key);
            g.fill(x, y0, x + SLOT, y0 + SLOT, 0xAA101018);
            g.renderOutline(x, y0, SLOT, SLOT, key == AbilityService.DOMAIN_KEY ? 0xFF9050D0 : 0xFF504060);
            g.drawString(font, String.valueOf(key + 1), x + 2, y0 + 2, 0xFFFFFF, true);
            if (ab.isEmpty()) continue;
            Ability a = ab.get();
            boolean locked = d.forms() < a.unlockForm();
            ResourceLocation icon = a.icon();
            if (icon != null) g.blit(icon, x + 3, y0 + 3, 0, 0, 16, 16, 16, 16);
            Variant v = preview(a, mods, d);
            if (locked) {
                g.fill(x + 1, y0 + 1, x + SLOT - 1, y0 + SLOT - 1, 0xC0000000);
                continue;
            }
            if (v != null) {
                int cd = d.cooldown(v.cooldownKey());
                if (cd > 0 && v.cooldownTicks() > 0) {
                    int hgt = (int) ((SLOT - 2) * Mth.clamp(cd / (float) v.cooldownTicks(), 0, 1));
                    g.fill(x + 1, y0 + SLOT - 1 - hgt, x + SLOT - 1, y0 + SLOT - 1, 0x99000000);
                    String s = String.format(cd >= 200 ? "%.0f" : "%.1f", cd / 20f);
                    g.drawCenteredString(font, s, x + SLOT / 2, y0 + 8, 0xFFFF7070);
                }
                if (d.energy() < v.energyCost()) g.renderOutline(x, y0, SLOT, SLOT, 0xFF802020);
            }
        }
        // name of the variant the hovered stance would fire on key 1..5: show the first available
        Optional<Ability> first = CombatMode.abilityAt(0);
        first.map(a -> preview(a, mods, d)).ifPresent(v ->
                g.drawString(font, Component.translatable(v.nameKey()), x0, y0 - 10, 0xE0D0FF, true));
        if (tech.pages() > 1) {
            g.drawString(font, (CombatMode.page() + 1) + "/" + tech.pages(), x0 + total - 18, y0 - 10, 0xA090C0, true);
        }
    }

    /** The variant a key would fire right now (same rules as the server's resolver, minus cooldown/energy). */
    static Variant preview(Ability a, Modifiers mods, SorcererData d) {
        for (Variant v : a.variants()) {
            if (!v.matches(mods) || d.forms() < v.minForms()) continue;
            boolean ok = true;
            for (String req : v.requirements()) {
                ResourceLocation id = ResourceLocation.tryParse(req);
                if (id == null || !d.has(id)) { ok = false; break; }
            }
            if (ok) return v;
        }
        return null;
    }
}
