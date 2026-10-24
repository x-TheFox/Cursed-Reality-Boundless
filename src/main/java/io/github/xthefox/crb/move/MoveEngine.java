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
        broadcast(m);
        process(m);
        return m;
    }

    /** Longest a move may run past its scripted length (holds wait for a press). */
    static final int MAX_OVERRUN = 1200;

    /** Called every server tick for every living entity with an active move. */
    public static void tick(LivingEntity e) {
        Holder h = e.getData(CRBAttachments.MOVE);
        MoveInstance m = h.current;
        if (m == null) return;
        if (!e.isAlive()) {
            end(e, MoveLogic.EndReason.DIED);
            return;
        }
        if (m.lastTick == e.level().getGameTime()) return; // started this tick: already processed
        MoveLogic logic = MoveLogics.get(m.moveId());
        if (logic.cancelledByStun() && e.getData(CRBAttachments.COMBAT).stunned()) {
            end(e, MoveLogic.EndReason.STUNNED);
            return;
        }
        if (m.counter < 1) {
            end(e, MoveLogic.EndReason.FINISHED);
            return;
        }
        if (m.elapsed() > m.duration() + MoveSim.INSTANCE.maxCounter(m.moveId()) + MAX_OVERRUN) {
            end(e, MoveLogic.EndReason.CANCELLED);
            return;
        }
        process(m);
    }

    /**
     * One JJA tick at the current countdown value: effects, the simulation lines in file order
     * (which may set the countdown), the Java logic, then the countdown drops by one.
     */
    private static void process(MoveInstance m) {
        LivingEntity e = m.caster();
        Holder h = e.getData(CRBAttachments.MOVE);
        m.lastTick = e.level().getGameTime();
        refreshLive(m);
        try {
            MoveEffects.INSTANCE.apply(m);
            MoveSim.INSTANCE.apply(m);
            if (h.current != m) return;
            MoveLogics.get(m.moveId()).tick(m, m.elapsed());
        } catch (RuntimeException ex) {
            CRB.LOG.error("Move {} failed at counter {}; cancelling", m.moveId(), m.counter, ex);
            end(e, MoveLogic.EndReason.CANCELLED);
            return;
        }
        if (h.current != m) return;
        m.inputs.clear();
        m.counter--;
        boolean jumped = m.jumped;
        m.jumped = false;
        if (m.consumeStateDirty() || jumped) {
            sendTrackingAndSelf(e, new MovePayloads.State(e.getId(), List.copyOf(m.tags()), Map.copyOf(m.scores()),
                    jumped ? m.counter : Integer.MIN_VALUE));
        }
    }

    /** JJA scores the move lines read (`Sneaking=1..`, `detect_jump=1`, `Stun=1..`, `Energy=..`). */
    private static void refreshLive(MoveInstance m) {
        LivingEntity e = m.caster();
        m.live.put("Sneaking", e.isShiftKeyDown() ? 1 : 0);
        m.live.put("detect_jump", e.onGround() ? 0 : 1);
        m.live.put("Stun", e.getData(CRBAttachments.COMBAT).stun());
        if (e.hasData(CRBAttachments.SORCERER)) m.live.put("Energy", e.getData(CRBAttachments.SORCERER).energy());
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
                PacketDistributor.sendToPlayer(viewer, startPayload(m));
            }
        });
    }

    private static void broadcast(MoveInstance m) {
        sendTrackingAndSelf(m.caster(), startPayload(m));
    }

    private static MovePayloads.Start startPayload(MoveInstance m) {
        return new MovePayloads.Start(m.caster().getId(), m.moveId(), m.counter, m.duration(), m.seed(),
                List.copyOf(m.tags()), Map.copyOf(m.scores()));
    }

    private static void sendTrackingAndSelf(LivingEntity e, net.minecraft.network.protocol.common.custom.CustomPacketPayload p) {
        CRBNetwork.sendTrackingAndSelf(e, p);
    }
}
