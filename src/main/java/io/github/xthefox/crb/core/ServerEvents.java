package io.github.xthefox.crb.core;

import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.combat.EnergyRules;
import io.github.xthefox.crb.command.CRBCommands;
import io.github.xthefox.crb.data.CRBAttachments;
import io.github.xthefox.crb.data.CombatState;
import io.github.xthefox.crb.data.SorcererData;
import io.github.xthefox.crb.move.MoveEngine;
import io.github.xthefox.crb.move.MoveLogic;
import io.github.xthefox.crb.net.CRBNetwork;
import io.github.xthefox.crb.progression.Traits;
import io.github.xthefox.crb.progression.kit.KitManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@EventBusSubscriber(modid = CRB.MOD_ID)
public final class ServerEvents {
    /** Consecutive sneak ticks per player (JJA {@code Sneaking}). */
    private static final Map<UUID, Integer> SNEAK_TICKS = new ConcurrentHashMap<>();
    /** Full sorcerer sync at most this often for energy-only changes (ticks). */
    private static final int ENERGY_SYNC_INTERVAL = 10;

    private ServerEvents() {}

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        SorcererData data = player.getData(CRBAttachments.SORCERER);
        CombatState combat = player.getData(CRBAttachments.COMBAT);

        data.tickCooldowns();
        int sneak = player.isShiftKeyDown() ? SNEAK_TICKS.merge(player.getUUID(), 1, Integer::sum) : 0;
        if (sneak == 0) SNEAK_TICKS.remove(player.getUUID());

        int before = data.energy();
        int gain = EnergyRules.gainThisTick(new EnergyRules.Context(
                data.energyCap(),
                combat.stunned() || combat.movementLocked(),
                false, false,
                data.has(Traits.HEAVENLY_RESTRICTION),
                data.has(Traits.SIX_EYES),
                data.has(Traits.FASTER_CHARGE),
                sneak,
                player.level().getGameTime()));
        if (gain > 0 && data.energy() < data.energyCap()) {
            // energy changes are frequent; send them on a cadence instead of every tick
            int newEnergy = Math.min(data.energyCap(), before + gain);
            boolean wasDirty = data.isDirty();
            data.setEnergy(newEnergy);
            if (!wasDirty && player.tickCount % ENERGY_SYNC_INTERVAL != 0 && newEnergy < data.energyCap()) data.clearDirty();
        }

        if (data.isDirty()) CRBNetwork.syncSorcerer(player);
    }

    @SubscribeEvent
    static void onEntityTick(EntityTickEvent.Post event) {
        if (event.getEntity().level().isClientSide() || !(event.getEntity() instanceof LivingEntity living)) return;
        if (living.hasData(CRBAttachments.MOVE)) MoveEngine.tick(living);
        if (!living.hasData(CRBAttachments.COMBAT)) return;
        CombatState state = living.getData(CRBAttachments.COMBAT);
        state.tick();
        if (state.isDirty()) CRBNetwork.syncCombat(living);
    }

    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            p.getData(CRBAttachments.SORCERER).markDirty();
            CRBNetwork.syncSorcerer(p);
        }
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SNEAK_TICKS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) CRBNetwork.syncSorcerer(p);
    }

    @SubscribeEvent
    static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) CRBNetwork.syncSorcerer(p);
    }

    @SubscribeEvent
    static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer viewer && event.getTarget() instanceof LivingEntity target) {
            if (target.hasData(CRBAttachments.COMBAT)) CRBNetwork.syncCombatTo(viewer, target);
            if (target.hasData(CRBAttachments.MOVE)) MoveEngine.syncTo(viewer, target);
        }
    }

    @SubscribeEvent
    static void onDeath(LivingDeathEvent event) {
        LivingEntity e = event.getEntity();
        if (!e.level().isClientSide() && e.hasData(CRBAttachments.MOVE)) MoveEngine.end(e, MoveLogic.EndReason.DIED);
    }

    @SubscribeEvent
    static void onReload(AddReloadListenerEvent event) {
        event.addListener(KitManager.INSTANCE);
        event.addListener(io.github.xthefox.crb.move.MoveDurations.INSTANCE);
    }

    @SubscribeEvent
    static void onCommands(RegisterCommandsEvent event) {
        CRBCommands.register(event.getDispatcher(), event.getBuildContext());
    }
}
