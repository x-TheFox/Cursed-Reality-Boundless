package io.github.xthefox.crb.client.cinematic;

import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.core.CRBConfig;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Screen-space presentation: shakes, colour fades, impact frames, flashes and FOV punches.
 * All timings use {@link CinematicDirector#now()} so they line up with move playback.
 */
public final class ScreenEffects {
    // ---- shake (Bedrock camerashake add <intensity> <seconds> positional|rotational)
    private record Shake(double intensity, double start, double duration, boolean rotational, double phase) {}

    private static final List<Shake> SHAKES = new ArrayList<>();
    /** Degrees of view jitter per unit of Bedrock intensity. JJA uses 0.025 (light taps) to 4 (ultimates). */
    private static final double ROTATIONAL_DEG = 1.6, POSITIONAL_DEG = 0.9;
    private static final RandomSource RNG = RandomSource.create();

    // ---- fade (Bedrock camera fade time <in> <hold> <out> color <r> <g> <b>)
    private static double fadeStart = -1, fadeIn, fadeHold, fadeOut;
    private static int fadeColor;

    // ---- impact frame (JJA: full-screen glyph via /title)
    private static ResourceLocation impact;
    private static double impactStart;
    private static String impactGlyph;
    /** A title that is never cleared would linger; JJA always clears within a few ticks. */
    private static final double IMPACT_MAX_TICKS = 20;
    private static final double FOV_PUNCH_TICKS = 4, FOV_PUNCH = 0.06;
    private static double punchStart = -100;

    // ---- flash (night vision "pop")
    private static double flashStart = -100, flashTicks = 12, flashAlpha;

    private ScreenEffects() {}

    // ------------------------------------------------------------------ API used by cue handlers

    public static void shake(double intensity, double seconds, boolean rotational) {
        double scale = CRBConfig.CLIENT.shakeScale.get();
        if (scale <= 0 || intensity <= 0 || seconds <= 0) return;
        SHAKES.add(new Shake(intensity * scale, CinematicDirector.now(), seconds * 20, rotational, RNG.nextDouble() * 1000));
    }

    public static void fade(double in, double hold, double out, int r, int g, int b) {
        fadeStart = CinematicDirector.now();
        fadeIn = in * 20;
        fadeHold = hold * 20;
        fadeOut = out * 20;
        fadeColor = (r & 255) << 16 | (g & 255) << 8 | (b & 255);
    }

    /** Fades out quickly from wherever the current fade is (used when a move is cancelled). */
    public static void endFade() {
        double a = fadeAlpha(CinematicDirector.now());
        if (a <= 0) return;
        fadeStart = CinematicDirector.now() - (fadeIn + fadeHold);
        fadeOut = 4;
    }

    /** Shows impact frame {@code glyph} (hex codepoint, e.g. "E305"). */
    public static void impact(String glyph) {
        if (!CRBConfig.CLIENT.impactFrames.get()) return;
        String g = glyph.toLowerCase(Locale.ROOT);
        ResourceLocation tex = CRB.id("textures/glyph/" + g.substring(0, 2) + "/" + g.substring(2) + ".png");
        boolean fresh = impact == null || !g.equals(impactGlyph);
        impact = tex;
        impactGlyph = g;
        impactStart = CinematicDirector.now();
        if (fresh && CRBConfig.CLIENT.cinematicCamera.get()) punchStart = impactStart;
    }

    public static void clearImpact() {
        impact = null;
        impactGlyph = null;
    }

    public static void flash(double alpha, double ticks) {
        flashStart = CinematicDirector.now();
        flashAlpha = alpha;
        flashTicks = ticks;
    }

    public static void reset() {
        SHAKES.clear();
        fadeStart = -1;
        impact = null;
        flashStart = -100;
        punchStart = -100;
    }

    // ------------------------------------------------------------------ per-frame queries

    /** Additive (pitch, yaw, roll) jitter for this frame. */
    public static float[] shakeAngles() {
        double now = CinematicDirector.now();
        double pitch = 0, yaw = 0, roll = 0;
        Iterator<Shake> it = SHAKES.iterator();
        while (it.hasNext()) {
            Shake s = it.next();
            double age = now - s.start;
            if (age >= s.duration) {
                it.remove();
                continue;
            }
            double falloff = 1 - age / s.duration;
            double amp = s.intensity * falloff * falloff * (s.rotational ? ROTATIONAL_DEG : POSITIONAL_DEG);
            double t = (now + s.phase) * 1.9;
            pitch += amp * noise(t, 0);
            yaw += amp * noise(t, 17.3);
            roll += (s.rotational ? amp * 1.4 : amp * 0.4) * noise(t, 41.7);
        }
        return new float[]{(float) pitch, (float) yaw, (float) roll};
    }

    /** Multiplier for the field of view (impact-frame punch). */
    public static double fovMultiplier() {
        double age = CinematicDirector.now() - punchStart;
        if (age < 0 || age > FOV_PUNCH_TICKS) return 1;
        return 1 - FOV_PUNCH * (1 - age / FOV_PUNCH_TICKS);
    }

    static double fadeAlpha(double now) {
        if (fadeStart < 0) return 0;
        double age = now - fadeStart;
        if (age < 0) return 0;
        if (age < fadeIn) return age / Math.max(fadeIn, 1e-3);
        age -= fadeIn;
        if (age < fadeHold) return 1;
        age -= fadeHold;
        if (age < fadeOut) return 1 - age / Math.max(fadeOut, 1e-3);
        fadeStart = -1;
        return 0;
    }

    /** Smooth value noise in [-1, 1]: a few incommensurate sines, cheap and continuous. */
    private static double noise(double t, double seed) {
        return (Math.sin(t * 1.0 + seed) * 0.5 + Math.sin(t * 2.31 + seed * 1.7) * 0.3 + Math.sin(t * 5.17 + seed * 2.3) * 0.2);
    }

    // ------------------------------------------------------------------ rendering (GUI layer, above everything)

    public static void render(GuiGraphics g, DeltaTracker delta) {
        double now = CinematicDirector.now();
        int w = g.guiWidth(), h = g.guiHeight();
        float maxOpacity = CRBConfig.CLIENT.flashOpacity.get().floatValue();

        if (impact != null) {
            if (now - impactStart > IMPACT_MAX_TICKS) {
                impact = null;
            } else {
                int size = Math.max(w, h);
                g.blit(impact, (w - size) / 2, (h - size) / 2, size, size, 0, 0, 1, 1, 1, 1);
            }
        }

        double fa = fadeAlpha(now) * maxOpacity;
        if (fa > 0) g.fill(0, 0, w, h, ((int) (Mth.clamp(fa, 0, 1) * 255) << 24) | fadeColor);

        double fage = now - flashStart;
        if (fage >= 0 && fage < flashTicks) {
            double a = flashAlpha * (1 - fage / flashTicks) * maxOpacity;
            g.fill(0, 0, w, h, ((int) (Mth.clamp(a, 0, 1) * 255) << 24) | 0xFFFFFF);
        }
    }
}
