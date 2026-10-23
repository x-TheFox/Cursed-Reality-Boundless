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
        r.playToClient(MovePayloads.Start.TYPE, MovePayloads.Start.STREAM_CODEC, ClientPayloads::onMoveStart);
        r.playToClient(MovePayloads.End.TYPE, MovePayloads.End.STREAM_CODEC, ClientPayloads::onMoveEnd);
        r.playToClient(MovePayloads.State.TYPE, MovePayloads.State.STREAM_CODEC, ClientPayloads::onMoveState);
        r.playToClient(MovePayloads.Cue.TYPE, MovePayloads.Cue.STREAM_CODEC, ClientPayloads::onCue);
        r.playToServer(MovePayloads.Melee.TYPE, MovePayloads.Melee.STREAM_CODEC,
                (p, ctx) -> io.github.xthefox.crb.combat.Melee.press((net.minecraft.server.level.ServerPlayer) ctx.player()));
        r.playToServer(MovePayloads.Movement.TYPE, MovePayloads.Movement.STREAM_CODEC,
                (p, ctx) -> io.github.xthefox.crb.combat.Movement.handle((net.minecraft.server.level.ServerPlayer) ctx.player(), p));
        r.playToServer(MovePayloads.AbilityInput.TYPE, MovePayloads.AbilityInput.STREAM_CODEC,
                (p, ctx) -> io.github.xthefox.crb.ability.AbilityService.handle((net.minecraft.server.level.ServerPlayer) ctx.player(), p));
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

    /**
     * Sends to every player that can see {@code e} (within its tracking range) and can receive the
     * payload, plus {@code e} itself if it is a player. Unlike {@code sendToPlayersTrackingEntity}
     * this never throws for connections without our channel (vanilla clients, GameTest mock players).
     */
    public static void sendTrackingAndSelf(net.minecraft.world.entity.Entity e, CustomPacketPayload p) {
        if (!(e.level() instanceof net.minecraft.server.level.ServerLevel sl)) return;
        double range = Math.max(e.getType().clientTrackingRange(), 1) * 16.0;
        for (ServerPlayer viewer : sl.players()) {
            if (viewer != e && viewer.distanceToSqr(e) <= range * range && canReceive(viewer, p.type())) {
                PacketDistributor.sendToPlayer(viewer, p);
            }
        }
        if (e instanceof ServerPlayer self && canReceive(self, p.type())) PacketDistributor.sendToPlayer(self, p);
    }

    /** Sends an entity's combat state to everyone tracking it (and the entity itself if a player). */
    public static void syncCombat(LivingEntity entity) {
        CombatState state = entity.getData(CRBAttachments.COMBAT);
        CombatSyncPayload payload = new CombatSyncPayload(entity.getId(), state.serializeNBT(entity.registryAccess()));
        sendTrackingAndSelf(entity, payload);
        state.clearDirty();
    }

    public static void syncCombatTo(ServerPlayer viewer, LivingEntity entity) {
        if (!canReceive(viewer, CombatSyncPayload.TYPE)) return;
        CombatState state = entity.getData(CRBAttachments.COMBAT);
        PacketDistributor.sendToPlayer(viewer, new CombatSyncPayload(entity.getId(), state.serializeNBT(entity.registryAccess())));
    }
}
