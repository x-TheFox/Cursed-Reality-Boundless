package io.github.xthefox.crb.net;

import io.github.xthefox.crb.CRB;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server → trackers: an entity's {@link io.github.xthefox.crb.data.CombatState}. */
public record CombatSyncPayload(int entityId, CompoundTag state) implements CustomPacketPayload {
    public static final Type<CombatSyncPayload> TYPE = new Type<>(CRB.id("combat_sync"));
    public static final StreamCodec<ByteBuf, CombatSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, CombatSyncPayload::entityId,
            ByteBufCodecs.COMPOUND_TAG, CombatSyncPayload::state,
            CombatSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
