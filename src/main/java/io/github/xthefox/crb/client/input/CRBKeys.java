package io.github.xthefox.crb.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/** All Cursed Reality keys (rebindable in Controls → Cursed Reality: Boundless). */
public final class CRBKeys {
    public static final String CATEGORY = "key.categories.crb";

    public static final KeyMapping COMBAT_MODE = key("combat_mode", GLFW.GLFW_KEY_GRAVE_ACCENT);
    public static final KeyMapping RADIAL = key("radial", GLFW.GLFW_KEY_H);
    public static final KeyMapping DASH = key("dash", GLFW.GLFW_KEY_LEFT_ALT);
    public static final KeyMapping EVADE = key("evade", GLFW.GLFW_KEY_V);
    public static final KeyMapping CE_ART = key("ce_art", GLFW.GLFW_KEY_C);
    public static final KeyMapping STYLE_ART = key("style_art", GLFW.GLFW_KEY_R);
    /** Optional direct ability keys for players who prefer them over 1-6 in Combat Mode (unbound by default). */
    public static final KeyMapping[] ABILITY = new KeyMapping[6];

    static {
        for (int i = 0; i < ABILITY.length; i++) ABILITY[i] = key("ability_" + (i + 1), InputConstants.UNKNOWN.getValue());
    }

    private CRBKeys() {}

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.crb." + name, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, code, CATEGORY);
    }

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(COMBAT_MODE);
        event.register(RADIAL);
        event.register(DASH);
        event.register(EVADE);
        event.register(CE_ART);
        event.register(STYLE_ART);
        for (KeyMapping k : ABILITY) event.register(k);
    }
}
