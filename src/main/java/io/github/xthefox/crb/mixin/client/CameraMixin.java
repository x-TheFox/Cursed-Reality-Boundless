package io.github.xthefox.crb.mixin.client;

import io.github.xthefox.crb.client.cinematic.CinematicDirector;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The level renderer only draws the camera's own entity when the camera is detached. During a
 * scripted shot the local player must be visible (JJA's shots frame the caster).
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Inject(method = "isDetached", at = @At("HEAD"), cancellable = true)
    private void crb$detachedDuringCinematic(CallbackInfoReturnable<Boolean> cir) {
        if (CinematicDirector.isActive()) cir.setReturnValue(true);
    }
}
