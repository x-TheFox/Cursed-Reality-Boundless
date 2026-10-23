package io.github.xthefox.crb.client.cinematic;

import io.github.xthefox.crb.client.move.ClientMoves;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Owns the scripted camera. Implements Bedrock's {@code camera set minecraft:free} semantics: a
 * shot either snaps or eases from wherever the camera is to a fixed world pose; re-issuing a shot
 * every tick (JJA's range cues) makes it follow its subject. Third-person presets switch the
 * vanilla camera type and restore it on clear.
 *
 * <p>Shots belong to a move (caster entity id). When that move ends for any reason the director
 * releases the camera, so a cancelled cinematic can never leave the player stuck in a free camera
 * (JJA ships {@code fix_black_screen} for exactly that).
 */
public final class CinematicDirector {
    private static CameraPose from, to, last;
    private static double startTime, durationTicks;
    private static Ease ease = Ease.LINEAR;
    private static boolean active;
    private static int ownerEntity = Integer.MIN_VALUE;
    private static CameraType savedType;

    private CinematicDirector() {}

    /** Client time in ticks including the partial tick, on the same clock as move playback. */
    public static double now() {
        return ClientMoves.ticks() + Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
    }

    /** Starts a shot to {@code target}; {@code seconds <= 0} snaps. */
    public static void shot(CameraPose target, double seconds, Ease e, int owner) {
        CameraPose start = active && last != null ? last : vanillaPose();
        from = start;
        to = target;
        startTime = now();
        durationTicks = Math.max(0, seconds * 20);
        ease = e;
        active = true;
        ownerEntity = owner;
    }

    /** Bedrock {@code third_person} / {@code third_person_front} / {@code follow_orbit} presets. */
    public static void preset(CameraType type, int owner) {
        Minecraft mc = Minecraft.getInstance();
        if (savedType == null) savedType = mc.options.getCameraType();
        mc.options.setCameraType(type);
        active = false;
        last = null;
        ownerEntity = owner;
    }

    public static void clear() {
        active = false;
        last = null;
        ownerEntity = Integer.MIN_VALUE;
        if (savedType != null) {
            Minecraft.getInstance().options.setCameraType(savedType);
            savedType = null;
        }
    }

    /** Releases the camera if the given caster's move owned it. */
    public static void releaseIfOwnedBy(int entityId) {
        if (ownerEntity == entityId) clear();
    }

    public static boolean isActive() {
        return active;
    }

    /** The pose for this frame, or null when the vanilla camera should be used. */
    public static CameraPose pose() {
        if (!active) return null;
        double t = durationTicks <= 0 ? 1 : (now() - startTime) / durationTicks;
        last = CameraPose.lerp(from, to, ease.apply(t));
        return last;
    }

    private static CameraPose vanillaPose() {
        var cam = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 p = cam.getPosition();
        return new CameraPose(p, cam.getXRot(), cam.getYRot(), 0);
    }
}
