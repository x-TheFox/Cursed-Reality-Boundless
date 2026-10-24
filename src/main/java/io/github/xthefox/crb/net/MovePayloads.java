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
     * Server → trackers: a move started (or is in progress, for late joiners). Clients play the
     * script from countdown value {@code counter}; {@code start} is its first value.
     */
    public record Start(int entityId, ResourceLocation moveId, int counter, int start, long seed,
                        List<String> tags, Map<String, Integer> scores) implements CustomPacketPayload {
        public static final Type<Start> TYPE = new Type<>(CRB.id("move_start"));
        public static final StreamCodec<FriendlyByteBuf, Start> STREAM_CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.entityId);
            buf.writeResourceLocation(p.moveId);
            buf.writeVarInt(p.counter);
            buf.writeVarInt(p.start);
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
    /** Server → trackers: a move's tags/scores changed, or its countdown jumped ({@code counter} is processed next). */
    public record State(int entityId, List<String> tags, Map<String, Integer> scores, int counter) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(CRB.id("move_state"));
        public static final StreamCodec<FriendlyByteBuf, State> STREAM_CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.entityId);
            buf.writeCollection(p.tags, FriendlyByteBuf::writeUtf);
            buf.writeMap(p.scores, FriendlyByteBuf::writeUtf, FriendlyByteBuf::writeVarInt);
            buf.writeVarInt(p.counter);
        }, buf -> new State(buf.readVarInt(), buf.readList(FriendlyByteBuf::readUtf),
                buf.readMap(HashMap::new, FriendlyByteBuf::readUtf, FriendlyByteBuf::readVarInt), buf.readVarInt()));

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

    /**
     * Server → trackers: one presentation cue outside a move (M1 swings and hits, which in JJA are
     * not moves and must not block casting). {@code cue} is a move-script cue object.
     */
    public record Cue(int entityId, String cue) implements CustomPacketPayload {
        public static final Type<Cue> TYPE = new Type<>(CRB.id("cue"));
        public static final StreamCodec<FriendlyByteBuf, Cue> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Cue::entityId,
                ByteBufCodecs.STRING_UTF8, Cue::cue,
                Cue::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client → server: melee input in Combat Mode. */
    public record Melee(int action) implements CustomPacketPayload {
        public static final int M1 = 0;
        public static final Type<Melee> TYPE = new Type<>(CRB.id("melee"));
        public static final StreamCodec<FriendlyByteBuf, Melee> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Melee::action, Melee::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Client → server: movement techniques. {@code dir} bits: 1 forward, 2 back, 4 left, 8 right
     * (the keys held, as JJA's {@code moveAngle} derived from velocity).
     */
    public record Movement(int kind, int dir) implements CustomPacketPayload {
        public static final int DASH = 0, EVADE = 1, DOUBLE_JUMP = 2;
        public static final Type<Movement> TYPE = new Type<>(CRB.id("movement"));
        public static final StreamCodec<FriendlyByteBuf, Movement> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Movement::kind,
                ByteBufCodecs.VAR_INT, Movement::dir,
                Movement::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
