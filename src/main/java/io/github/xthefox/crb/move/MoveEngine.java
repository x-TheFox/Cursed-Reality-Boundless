package io.github.xthefox.crb.move;

import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.ability.Variant;
import io.github.xthefox.crb.data.CRBAttachments;
import io.github.xthefox.crb.net.CRBNetwork;
import io.github.xthefox.crb.net.MovePayloads;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Server-authoritative move runner. One active move per entity, mirroring JJA where a new move's
 * {@code move} score overwrites the previous one.
 */
public final class MoveEngine {
    private MoveEngine() {}

    /** Holder for the entity's active move (transient attachment, never saved). */
    public static final class Holder {
        MoveInstance current;
    }

    public static Optional<MoveInstance> active(LivingEntity e) {
        return Optional.ofNullable(e.getData(CRBAttachments.MOVE).current);
    }

    public static boolean isBusy(LivingEntity e) {
        return active(e).isPresent();
    }

    /** Starts a move by id with an explicit duration (NPC brains, cutscenes, tests). */
    public static MoveInstance start(LivingEntity caster, ResourceLocation moveId, int duration) {
        return start(caster, moveId, duration, null);
    }

    public static MoveInstance start(LivingEntity caster, ResourceLocation moveId, int duration, Variant variant) {
        return start(caster, moveId, duration, variant, java.util.Set.of(), Map.of());
    }

    /** Starts a move with extra cue-condition tags/scores known up front (they go out with the start packet). */
    public static MoveInstance start(LivingEntity caster, ResourceLocation moveId, int duration, Variant variant,
                                     java.util.Set<String> extraTags, Map<String, Integer> extraScores) {
        if (caster.level().isClientSide()) throw new IllegalStateException("moves start on the server");
        Holder h = caster.getData(CRBAttachments.MOVE);
        if (h.current != null) end(caster, MoveLogic.EndReason.INTERRUPTED);
        long seed = caster.getRandom().nextLong();
        MoveInstance m = new MoveInstance(caster, moveId, variant, caster.level().getGameTime(), Math.max(1, duration), seed);
        m.tags().addAll(JjaTags.of(caster));
        // JJA's NPC and some player moves branch on a random `chance` score picked at the start
        m.scores().put("chance", 1 + m.random().nextInt(100));
        m.tags().addAll(extraTags);
        m.scores().putAll(extraScores);
        h.current = m;
        broadcast(m, 0);
        MoveEffects.INSTANCE.apply(m, 0);
        MoveSim.INSTANCE.apply(m, 0);
        MoveLogics.get(moveId).tick(m, 0);
        return m;
    }

    /** Called every server tick for every living entity with an active move. */
    public static void tick(LivingEntity e) {
        Holder h = e.getData(CRBAttachments.MOVE);
        MoveInstance m = h.current;
        if (m == null) return;
        if (!e.isAlive()) {
            end(e, MoveLogic.EndReason.DIED);
            return;
        }
        MoveLogic logic = MoveLogics.get(m.moveId());
        if (logic.cancelledByStun() && e.getData(CRBAttachments.COMBAT).stunned()) {
            end(e, MoveLogic.EndReason.STUNNED);
            return;
        }
        int t = m.elapsed();
        if (t >= m.duration()) {
            end(e, MoveLogic.EndReason.FINISHED);
            return;
        }
        if (t > 0) {
            MoveEffects.INSTANCE.apply(m, t);
            MoveSim.INSTANCE.apply(m, t);
            try {
                logic.tick(m, t);
            } catch (RuntimeException ex) {
                CRB.LOG.error("Move {} logic failed at tick {}; cancelling", m.moveId(), t, ex);
                end(e, MoveLogic.EndReason.CANCELLED);
                return;
            }
        }
        if (h.current == m && m.consumeStateDirty()) {
            sendTrackingAndSelf(e, new MovePayloads.State(e.getId(), List.copyOf(m.tags()), Map.copyOf(m.scores())));
        }
    }

    public static void end(LivingEntity e, MoveLogic.EndReason reason) {
        Holder h = e.getData(CRBAttachments.MOVE);
        MoveInstance m = h.current;
        if (m == null) return;
        h.current = null;
        try {
            MoveLogics.get(m.moveId()).end(m, reason);
        } finally {
            sendTrackingAndSelf(e, new MovePayloads.End(e.getId(), m.moveId(), reason));
        }
    }

    /** Re-sends an in-progress move to a player who just started tracking the caster. */
    public static void syncTo(ServerPlayer viewer, LivingEntity caster) {
        active(caster).ifPresent(m -> {
            if (CRBNetwork.canReceive(viewer, MovePayloads.Start.TYPE)) {
                PacketDistributor.sendToPlayer(viewer, startPayload(m, m.elapsed()));
            }
        });
    }

    private static void broadcast(MoveInstance m, int elapsed) {
        sendTrackingAndSelf(m.caster(), startPayload(m, elapsed));
    }

    private static MovePayloads.Start startPayload(MoveInstance m, int elapsed) {
        return new MovePayloads.Start(m.caster().getId(), m.moveId(), elapsed, m.duration(), m.seed(),
                List.copyOf(m.tags()), Map.copyOf(m.scores()));
    }

    private static void sendTrackingAndSelf(LivingEntity e, net.minecraft.network.protocol.common.custom.CustomPacketPayload p) {
        PacketDistributor.sendToPlayersTrackingEntity(e, p);
        if (e instanceof ServerPlayer sp && CRBNetwork.canReceive(sp, p.type())) PacketDistributor.sendToPlayer(sp, p);
    }
}
