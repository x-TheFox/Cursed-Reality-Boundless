package io.github.xthefox.crb.client.input;

import io.github.xthefox.crb.ability.Ability;
import io.github.xthefox.crb.ability.Technique;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Hold-to-open radial menu (default H). Sectors are technique pages; release (or click) picks the
 * hovered one. Later rows add combat styles, Cursed Energy arts and shikigami.
 */
public final class RadialMenuScreen extends Screen {
    private static final int RADIUS = 70;
    private int hovered = -1;

    RadialMenuScreen() {
        super(Component.translatable("crb.radial.title"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int sectors() {
        Technique t = CombatMode.technique();
        return t == null ? 0 : t.pages();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        int n = sectors();
        if (n == 0) { onClose(); return; }
        int cx = width / 2, cy = height / 2;
        double ang = Math.atan2(mouseY - cy, mouseX - cx);
        double dist = Math.hypot(mouseX - cx, mouseY - cy);
        hovered = dist < 18 ? -1 : Math.floorMod((int) Math.round((ang + Math.PI / 2) / (2 * Math.PI) * n), n);

        g.fill(0, 0, width, height, 0x66000000);
        Technique t = CombatMode.technique();
        for (int i = 0; i < n; i++) {
            double a = i * 2 * Math.PI / n - Math.PI / 2;
            int x = cx + (int) (Math.cos(a) * RADIUS), y = cy + (int) (Math.sin(a) * RADIUS);
            boolean sel = i == hovered, current = i == CombatMode.page();
            g.fill(x - 38, y - 14, x + 38, y + 14, sel ? 0xCC5A2D82 : current ? 0xAA2A1A40 : 0x99101010);
            g.renderOutline(x - 38, y - 14, 76, 28, sel ? 0xFFE0B0FF : 0xFF604080);
            Component label = Component.translatable("crb.radial.page", i + 1);
            g.drawCenteredString(font, label, x, y - 9, 0xFFFFFF);
            // first skill of the page as a hint
            t.abilityAt(i, 0).map(Ability::nameKey).ifPresent(k ->
                    g.drawCenteredString(font, Component.translatable(k), x, y + 2, 0xB0A0C0));
        }
        g.drawCenteredString(font, title, cx, cy - 4, 0xFFFFFF);
        super.render(g, mouseX, mouseY, partial);
    }

    private void pick() {
        if (hovered >= 0) CombatMode.setPage(hovered);
        onClose();
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (CRBKeys.RADIAL.matches(keyCode, scanCode)) {
            pick();
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            pick();
            return true;
        }
        return super.mouseClicked(x, y, button);
    }
}
