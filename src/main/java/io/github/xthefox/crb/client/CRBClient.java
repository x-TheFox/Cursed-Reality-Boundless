package io.github.xthefox.crb.client;

import io.github.xthefox.crb.CRB;
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
import net.neoforged.neoforge.common.NeoForge;

/** Client bootstrap: resource listeners, cue handlers, per-tick move playback. */
@EventBusSubscriber(modid = CRB.MOD_ID, value = Dist.CLIENT)
public final class CRBClient {
    private CRBClient() {}

    @SubscribeEvent
    static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            SoundCues.register();
            AnimationCues.register();
        });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            if (Minecraft.getInstance().level != null && !Minecraft.getInstance().isPaused()) ClientMoves.tick();
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> ClientMoves.clear());
    }

    @SubscribeEvent
    static void reloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(MoveScriptManager.INSTANCE);
        event.registerReloadListener((ResourceManagerReloadListener) rm -> SoundCues.reset());
    }
}
