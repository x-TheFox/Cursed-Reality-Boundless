package io.github.xthefox.crb.client.cinematic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.xthefox.crb.client.move.ClientMoves;
import io.github.xthefox.crb.client.move.MoveScript;
import io.github.xthefox.crb.move.CuePositions;
import io.github.xthefox.crb.move.JjaTags;
import io.github.xthefox.crb.move.MoveLogic;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/** Cue handlers for {@code camera_*}, {@code shake}, {@code impact*} and visual {@code effect} cues. */
public final class CinematicCues {
    private CinematicCues() {}

    public static void register() {
        ClientMoves.registerHandler("camera_set", CinematicCues::cameraSet);
        ClientMoves.registerHandler("camera_clear", (m, c, t) -> { if (audience(m, c)) CinematicDirector.clear(); });
        ClientMoves.registerHandler("camera_fade", CinematicCues::fade);
        ClientMoves.registerHandler("shake", (m, c, t) -> {
            if (audience(m, c)) ScreenEffects.shake(c.num("intensity", 0.5f), c.num("seconds", 1f), "rotational".equals(c.str("mode", "")));
        });
        ClientMoves.registerHandler("impact", (m, c, t) -> { if (audience(m, c)) ScreenEffects.impact(c.str("glyph", "E300")); });
        ClientMoves.registerHandler("impact_clear", (m, c, t) -> { if (audience(m, c)) ScreenEffects.clearImpact(); });
        // night vision in JJA is a brightness "pop" on impact; a short flash reads the same without a lingering effect
        ClientMoves.registerHandler("effect", (m, c, t) -> {
            if ("night_vision".equals(c.str("effect", "")) && t == c.t() && audience(m, c)) ScreenEffects.flash(0.35, 12);
        });
        ClientMoves.onEnd(CinematicCues::onEnd);
    }

    private static Set<String> localTags() {
        var p = Minecraft.getInstance().player;
        return p == null ? Set.of() : JjaTags.of(p);
    }

    private static boolean audience(ClientMoves.Playing m, MoveScript.Cue c) {
        return m.localInAudience(c, localTags());
    }

    private static void fade(ClientMoves.Playing m, MoveScript.Cue c, int elapsed) {
        if (!audience(m, c)) return;
        JsonArray col = c.array("color");
        int r = 0, g = 0, b = 0;
        if (col != null && col.size() == 3) {
            r = col.get(0).getAsInt();
            g = col.get(1).getAsInt();
            b = col.get(2).getAsInt();
        }
        ScreenEffects.fade(c.num("in", 0), c.num("hold", 0), c.num("out", 0), r, g, b);
    }

    private static void cameraSet(ClientMoves.Playing m, MoveScript.Cue c, int elapsed) {
        if (!audience(m, c)) return;
        LivingEntity caster = ClientMoves.caster(m);
        if (caster == null) return;
        String preset = c.str("preset", "free");
        switch (preset) {
            case "third_person", "follow_orbit" -> { CinematicDirector.preset(CameraType.THIRD_PERSON_BACK, m.entityId); return; }
            case "third_person_front" -> { CinematicDirector.preset(CameraType.THIRD_PERSON_FRONT, m.entityId); return; }
            default -> { }
        }
        float yRot = caster.getYRot(), xRot = caster.getXRot();
        float eye = caster.getEyeHeight();
        CameraPose current = CinematicDirector.isActive() ? CinematicDirector.pose() : null;
        var cam = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 pos = c.array("pos") != null
                ? CuePositions.resolve(c.array("pos"), caster.position(), eye, yRot, xRot)
                : current != null ? current.pos() : cam.getPosition();

        float pitch, yaw;
        if (c.array("rot") != null) {
            JsonArray rot = c.array("rot");
            pitch = relative(rot.get(0).getAsString(), xRot);
            yaw = relative(rot.get(1).getAsString(), yRot);
        } else if (c.data().has("facing")) {
            JsonObject f = c.data().getAsJsonObject("facing");
            Vec3 target = f.has("at")
                    ? CuePositions.resolve(f.getAsJsonArray("at"), caster.position(), eye, yRot, xRot)
                    : caster.getEyePosition();
            float[] look = CameraPose.lookAt(pos, target);
            pitch = look[0];
            yaw = look[1];
        } else {
            pitch = current != null ? current.pitch() : cam.getXRot();
            yaw = current != null ? current.yaw() : cam.getYRot();
        }
        float roll = c.num("roll", 0);

        double seconds = 0;
        Ease ease = Ease.LINEAR;
        JsonElement e = c.data().get("ease");
        if (e != null && e.isJsonObject()) {
            seconds = e.getAsJsonObject().get("seconds").getAsDouble();
            ease = Ease.parse(e.getAsJsonObject().get("type").getAsString());
        }
        CinematicDirector.shot(new CameraPose(pos, pitch, yaw, roll), seconds, ease, m.entityId);
    }

    /** "~-21" relative to {@code base}, "15" absolute, "~" = base. */
    static float relative(String s, float base) {
        if (s.startsWith("~")) {
            String rest = s.substring(1);
            return base + (rest.isEmpty() ? 0 : Float.parseFloat(rest));
        }
        return Float.parseFloat(s);
    }

    private static void onEnd(ClientMoves.Playing m, MoveLogic.EndReason reason) {
        CinematicDirector.releaseIfOwnedBy(m.entityId);
        if (reason != MoveLogic.EndReason.FINISHED) {
            ScreenEffects.endFade();
            ScreenEffects.clearImpact();
        }
    }
}
