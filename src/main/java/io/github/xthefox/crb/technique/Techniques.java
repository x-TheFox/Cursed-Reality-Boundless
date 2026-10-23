package io.github.xthefox.crb.technique;

import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.technique.limitless.Limitless;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/** Fills in each technique's abilities and logic once registries are frozen. */
@EventBusSubscriber(modid = CRB.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class Techniques {
    private static boolean done;

    private Techniques() {}

    @SubscribeEvent
    static void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(Techniques::init);
    }

    /** Idempotent so tests can call it. */
    public static synchronized void init() {
        if (done) return;
        done = true;
        Limitless.init();
    }
}
