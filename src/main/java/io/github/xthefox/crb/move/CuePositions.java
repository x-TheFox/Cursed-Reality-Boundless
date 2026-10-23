package io.github.xthefox.crb.move;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Resolves a cue's position ops the way Minecraft's {@code execute} does:
 * {@code anchored eyes} lifts the origin for the next {@code ^} (local) offset, {@code positioned}
 * resets the anchor to the feet, {@code ~} offsets are world-axis. Local axes follow
 * {@code LocalCoordinates}: x = left, y = up, z = forward, from the executor's yaw and pitch.
 *
 * <p>Shared by client cue players and server logic (projectile spawn points use the same offsets).
 */
public final class CuePositions {
    private CuePositions() {}

    public static Vec3 resolve(JsonArray ops, Vec3 feet, float eyeHeight, float yRot, float xRot) {
        Vec3 pos = feet;
        boolean eyes = false;
        if (ops == null) return pos;
        for (JsonElement el : ops) {
            JsonObject op = el.getAsJsonObject();
            if (op.has("anchor")) {
                eyes = "eyes".equals(op.get("anchor").getAsString());
            } else if (op.has("local")) {
                JsonArray v = op.getAsJsonArray("local");
                Vec3 origin = eyes ? pos.add(0, eyeHeight, 0) : pos;
                pos = local(origin, yRot, xRot, v.get(0).getAsDouble(), v.get(1).getAsDouble(), v.get(2).getAsDouble());
                eyes = false;
            } else if (op.has("rel")) {
                JsonArray v = op.getAsJsonArray("rel");
                pos = pos.add(v.get(0).getAsDouble(), v.get(1).getAsDouble(), v.get(2).getAsDouble());
                eyes = false;
            }
        }
        return eyes ? pos.add(0, eyeHeight, 0) : pos;
    }

    /** Vanilla {@code LocalCoordinates#getPosition} math. */
    public static Vec3 local(Vec3 origin, float yRot, float xRot, double left, double up, double forwards) {
        float f = Mth.cos((yRot + 90.0F) * Mth.DEG_TO_RAD);
        float f1 = Mth.sin((yRot + 90.0F) * Mth.DEG_TO_RAD);
        float f2 = Mth.cos(-xRot * Mth.DEG_TO_RAD);
        float f3 = Mth.sin(-xRot * Mth.DEG_TO_RAD);
        float f4 = Mth.cos((-xRot + 90.0F) * Mth.DEG_TO_RAD);
        float f5 = Mth.sin((-xRot + 90.0F) * Mth.DEG_TO_RAD);
        Vec3 fwd = new Vec3(f * f2, f3, f1 * f2);
        Vec3 upv = new Vec3(f * f4, f5, f1 * f4);
        Vec3 leftv = fwd.cross(upv).scale(-1.0);
        return origin.add(fwd.x * forwards + upv.x * up + leftv.x * left,
                fwd.y * forwards + upv.y * up + leftv.y * left,
                fwd.z * forwards + upv.z * up + leftv.z * left);
    }
}
