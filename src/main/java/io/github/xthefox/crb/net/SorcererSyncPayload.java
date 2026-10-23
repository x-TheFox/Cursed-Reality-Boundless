package io.github.xthefox.crb.net;

import io.github.xthefox.crb.CRB;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server → owner: full {@link io.github.xthefox.crb.data.SorcererData}. Small (< 1 KB), sent on change. */
public record SorcererSyncPayload(CompoundTag data) implements CustomPacketPayload {
    public static final Type<SorcererSyncPayload> TYPE = new Type<>(CRB.id("sorcerer_sync"));
    public static final StreamCodec<ByteBuf, SorcererSyncPayload> STREAM_CODEC =
            ByteBufCodecs.COMPOUND_TAG.map(SorcererSyncPayload::new, SorcererSyncPayload::data);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
