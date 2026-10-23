package io.github.xthefox.crb.net;

import io.github.xthefox.crb.data.CRBAttachments;
import io.github.xthefox.crb.data.CombatState;
import io.github.xthefox.crb.data.SorcererData;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Payload registration and send helpers. Protocol design: research doc 05. */
public final class CRBNetwork {
    public static final String PROTOCOL = "1";

    private CRBNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar(PROTOCOL);
        r.playToClient(SorcererSyncPayload.TYPE, SorcererSyncPayload.STREAM_CODEC, ClientPayloads::onSorcererSync);
        r.playToClient(CombatSyncPayload.TYPE, CombatSyncPayload.STREAM_CODEC, ClientPayloads::onCombatSync);
    }

    /** Sends the player's full sorcerer data to themselves and clears the dirty flag. */
    public static void syncSorcerer(ServerPlayer player) {
        SorcererData data = player.getData(CRBAttachments.SORCERER);
        if (canReceive(player, SorcererSyncPayload.TYPE)) {
            PacketDistributor.sendToPlayer(player, new SorcererSyncPayload(data.serializeNBT(player.registryAccess())));
        }
        data.clearDirty();
    }

    /** False for connections that never negotiated our channel (GameTest mock players, mid-login). */
    public static boolean canReceive(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return player.connection != null && player.connection.hasChannel(type);
    }

    /** Sends an entity's combat state to everyone tracking it (and the entity itself if a player). */
    public static void syncCombat(LivingEntity entity) {
        CombatState state = entity.getData(CRBAttachments.COMBAT);
        CombatSyncPayload payload = new CombatSyncPayload(entity.getId(), state.serializeNBT(entity.registryAccess()));
        PacketDistributor.sendToPlayersTrackingEntity(entity, payload);
        if (entity instanceof ServerPlayer self && canReceive(self, CombatSyncPayload.TYPE)) PacketDistributor.sendToPlayer(self, payload);
        state.clearDirty();
    }

    public static void syncCombatTo(ServerPlayer viewer, LivingEntity entity) {
        if (!canReceive(viewer, CombatSyncPayload.TYPE)) return;
        CombatState state = entity.getData(CRBAttachments.COMBAT);
        PacketDistributor.sendToPlayer(viewer, new CombatSyncPayload(entity.getId(), state.serializeNBT(entity.registryAccess())));
    }
}
