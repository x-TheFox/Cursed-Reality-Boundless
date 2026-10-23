package io.github.xthefox.crb.net;

import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.move.MoveLogic;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Move lifecycle payloads (research doc 05). One start + one end per move; no per-tick traffic. */
public final class MovePayloads {
    private MovePayloads() {}

    /**
     * Server → trackers: a move started (or is in progress, for late joiners: {@code elapsed > 0}).
     * Clients play the move script from {@code elapsed}.
     */
    public record Start(int entityId, ResourceLocation moveId, int elapsed, int duration, long seed,
                        List<String> tags, Map<String, Integer> scores) implements CustomPacketPayload {
        public static final Type<Start> TYPE = new Type<>(CRB.id("move_start"));
        public static final StreamCodec<FriendlyByteBuf, Start> STREAM_CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.entityId);
            buf.writeResourceLocation(p.moveId);
            buf.writeVarInt(p.elapsed);
            buf.writeVarInt(p.duration);
            buf.writeLong(p.seed);
            buf.writeCollection(p.tags, FriendlyByteBuf::writeUtf);
            buf.writeMap(p.scores, FriendlyByteBuf::writeUtf, FriendlyByteBuf::writeVarInt);
        }, buf -> new Start(buf.readVarInt(), buf.readResourceLocation(), buf.readVarInt(), buf.readVarInt(), buf.readLong(),
                buf.readList(FriendlyByteBuf::readUtf), buf.readMap(HashMap::new, FriendlyByteBuf::readUtf, FriendlyByteBuf::readVarInt)));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Server → trackers: the move ended. Non-finished endings stop its animation and looping effects. */
    public record End(int entityId, ResourceLocation moveId, MoveLogic.EndReason reason) implements CustomPacketPayload {
        public static final Type<End> TYPE = new Type<>(CRB.id("move_end"));
        public static final StreamCodec<FriendlyByteBuf, End> STREAM_CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.entityId);
            buf.writeResourceLocation(p.moveId);
            buf.writeEnum(p.reason);
        }, buf -> new End(buf.readVarInt(), buf.readResourceLocation(), buf.readEnum(MoveLogic.EndReason.class)));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Server → trackers: cue-condition state changed mid-move (e.g. a hit landed → {@code tag=blackflashed}). */
    public record State(int entityId, List<String> tags, Map<String, Integer> scores) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(CRB.id("move_state"));
        public static final StreamCodec<FriendlyByteBuf, State> STREAM_CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.entityId);
            buf.writeCollection(p.tags, FriendlyByteBuf::writeUtf);
            buf.writeMap(p.scores, FriendlyByteBuf::writeUtf, FriendlyByteBuf::writeVarInt);
        }, buf -> new State(buf.readVarInt(), buf.readList(FriendlyByteBuf::readUtf),
                buf.readMap(HashMap::new, FriendlyByteBuf::readUtf, FriendlyByteBuf::readVarInt)));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Client → server: an ability key was pressed. {@code key} 0-4 are the page's skills, 5 is the
     * domain/ultimate. Modifier bits follow {@link io.github.xthefox.crb.ability.Modifiers#toBits()}.
     */
    public record AbilityInput(int page, int key, int modifierBits) implements CustomPacketPayload {
        public static final Type<AbilityInput> TYPE = new Type<>(CRB.id("ability_input"));
        public static final StreamCodec<FriendlyByteBuf, AbilityInput> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, AbilityInput::page,
                ByteBufCodecs.VAR_INT, AbilityInput::key,
                ByteBufCodecs.VAR_INT, AbilityInput::modifierBits,
                AbilityInput::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
