package io.github.xthefox.crb.client;

import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.client.cinematic.CinematicCues;
import io.github.xthefox.crb.client.cinematic.CinematicDirector;
import io.github.xthefox.crb.client.cinematic.ScreenEffects;
import io.github.xthefox.crb.client.move.AnimationCues;
import io.github.xthefox.crb.client.move.ClientMoves;
import io.github.xthefox.crb.client.move.MoveScriptManager;
import io.github.xthefox.crb.client.move.SoundCues;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client bootstrap: resource listeners, cue handlers, per-tick move playback. */
@EventBusSubscriber(modid = CRB.MOD_ID, value = Dist.CLIENT)
public final class CRBClient {
    private CRBClient() {}

    @SubscribeEvent
    static void setup(FMLClientSetupEvent event) {
        io.github.xthefox.crb.client.dev.DevHarness.init();
        event.enqueueWork(() -> {
            SoundCues.register();
            AnimationCues.register();
            CinematicCues.register();
            io.github.xthefox.crb.client.fx.BedrockFX.register();
        });
        NeoForge.EVENT_BUS.addListener((ViewportEvent.ComputeCameraAngles e) -> {
            float[] s = ScreenEffects.shakeAngles();
            if (s[0] == 0 && s[1] == 0 && s[2] == 0) return;
            e.setPitch(e.getPitch() + s[0]);
            e.setYaw(e.getYaw() + s[1]);
            e.setRoll(e.getRoll() + s[2]);
        });
        // JJA hides the HUD during cinematics (`hud @a hide`); the hand and vanilla HUD go while the director owns the camera
        NeoForge.EVENT_BUS.addListener((RenderHandEvent e) -> { if (CinematicDirector.isActive()) e.setCanceled(true); });
        NeoForge.EVENT_BUS.addListener((RenderGuiLayerEvent.Pre e) -> {
            if (CinematicDirector.isActive() && !e.getName().equals(CRB.id("screen_effects"))) e.setCanceled(true);
        });
        NeoForge.EVENT_BUS.addListener((ViewportEvent.ComputeFov e) -> {
            double k = ScreenEffects.fovMultiplier();
            if (k != 1) e.setFOV(e.getFOV() * k);
        });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            if (Minecraft.getInstance().level != null && !Minecraft.getInstance().isPaused()) {
                ClientMoves.tick();
                io.github.xthefox.crb.client.fx.BedrockFX.INSTANCE.tick();
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            ClientMoves.clear();
            io.github.xthefox.crb.client.fx.BedrockFX.INSTANCE.clear();
            CinematicDirector.clear();
            ScreenEffects.reset();
        });
    }

    @SubscribeEvent
    static void guiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(CRB.id("screen_effects"), ScreenEffects::render);
    }

    @SubscribeEvent
    static void reloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(MoveScriptManager.INSTANCE);
        event.registerReloadListener(io.github.xthefox.crb.client.fx.BedrockFX.INSTANCE);
        event.registerReloadListener((ResourceManagerReloadListener) rm -> SoundCues.reset());
    }
}
