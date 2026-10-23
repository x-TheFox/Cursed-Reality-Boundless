package io.github.xthefox.crb.client.cinematic;

import cn.anecansaitin.freecameraapi.api.CameraPlugin;
import cn.anecansaitin.freecameraapi.api.ICameraModifier;
import cn.anecansaitin.freecameraapi.api.ICameraPlugin;
import cn.anecansaitin.freecameraapi.api.ModifierPriority;
import io.github.xthefox.crb.core.CRBConfig;

/** Bridges {@link CinematicDirector} to Free Camera API; found by its annotation scan. */
@CameraPlugin(value = "director", priority = ModifierPriority.HIGHEST)
public final class DirectorCameraPlugin implements ICameraPlugin {
    private ICameraModifier modifier;

    @Override
    public void initialize(ICameraModifier modifier) {
        this.modifier = modifier;
    }

    @Override
    public void update() {
        if (modifier == null) return;
        CameraPose pose = CRBConfig.CLIENT.cinematicCamera.get() ? CinematicDirector.pose() : null;
        if (pose == null) {
            if (modifier.isActive()) modifier.disable();
            return;
        }
        modifier.enable().enablePos().enableRotation().enableGlobalMode()
                .setPos((float) pose.pos().x, (float) pose.pos().y, (float) pose.pos().z)
                .setRotationYXZ(pose.pitch(), pose.yaw(), pose.roll());
    }
}
