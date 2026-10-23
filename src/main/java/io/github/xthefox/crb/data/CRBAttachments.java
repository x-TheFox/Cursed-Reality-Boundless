package io.github.xthefox.crb.data;

import io.github.xthefox.crb.CRB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

public final class CRBAttachments {
    private static final DeferredRegister<AttachmentType<?>> TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, CRB.MOD_ID);

    /** Persistent; copied to the new player entity on death and respawn. */
    public static final Supplier<AttachmentType<SorcererData>> SORCERER = TYPES.register("sorcerer",
            () -> AttachmentType.serializable(SorcererData::new).copyOnDeath().build());

    /** Transient; reset on death. Serialized only so it survives chunk unloads for NPCs. */
    public static final Supplier<AttachmentType<CombatState>> COMBAT = TYPES.register("combat",
            () -> AttachmentType.serializable(CombatState::new).build());

    private CRBAttachments() {}

    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
    }
}
