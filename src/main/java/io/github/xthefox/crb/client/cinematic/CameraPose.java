package io.github.xthefox.crb.client.cinematic;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** A camera position and orientation in degrees (pitch, yaw, roll). */
public record CameraPose(Vec3 pos, float pitch, float yaw, float roll) {

    /** Interpolates two poses; yaw takes the shortest way round. */
    public static CameraPose lerp(CameraPose a, CameraPose b, double t) {
        float tf = (float) t;
        return new CameraPose(a.pos.lerp(b.pos, t),
                Mth.lerp(tf, a.pitch, b.pitch),
                a.yaw + Mth.wrapDegrees(b.yaw - a.yaw) * tf,
                Mth.lerp(tf, a.roll, b.roll));
    }

    /** Rotation that looks from {@code from} at {@code target}, in Minecraft's convention (yaw 0 = +Z). */
    public static float[] lookAt(Vec3 from, Vec3 target) {
        Vec3 d = target.subtract(from);
        double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
        float pitch = (float) -(Mth.atan2(d.y, horizontal) * Mth.RAD_TO_DEG);
        return new float[]{pitch, yaw};
    }
}
