package io.github.xthefox.crb;

import com.mojang.logging.LogUtils;
import io.github.xthefox.crb.core.CRBConfig;
import io.github.xthefox.crb.core.CRBRegistries;
import io.github.xthefox.crb.data.CRBAttachments;
import io.github.xthefox.crb.net.CRBNetwork;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(CRB.MOD_ID)
public final class CRB {
    public static final String MOD_ID = "crb";
    public static final Logger LOG = LogUtils.getLogger();

    public CRB(IEventBus modBus, ModContainer container) {
        CRBRegistries.register(modBus);
        CRBAttachments.register(modBus);
        io.github.xthefox.crb.projectile.ProjectileTypes.register(modBus);
        modBus.addListener(CRBNetwork::register);

        container.registerConfig(ModConfig.Type.SERVER, CRBConfig.SERVER_SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, CRBConfig.CLIENT_SPEC);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
