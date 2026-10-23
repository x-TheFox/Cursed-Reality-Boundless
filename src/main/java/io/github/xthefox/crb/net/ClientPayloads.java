package io.github.xthefox.crb.net;

import io.github.xthefox.crb.data.CRBAttachments;
import io.github.xthefox.crb.data.CombatState;
import io.github.xthefox.crb.data.SorcererData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client-side handlers. Only touches common classes (attachments on client entities), so it is
 * safe to reference from common registration code. Unknown entity ids are ignored.
 */
final class ClientPayloads {
    private ClientPayloads() {}

    static void onSorcererSync(SorcererSyncPayload payload, IPayloadContext ctx) {
        Player player = ctx.player();
        SorcererData data = new SorcererData();
        data.deserializeNBT(player.registryAccess(), payload.data());
        player.setData(CRBAttachments.SORCERER, data);
    }

    static void onCombatSync(CombatSyncPayload payload, IPayloadContext ctx) {
        Entity e = ctx.player().level().getEntity(payload.entityId());
        if (!(e instanceof LivingEntity living)) return;
        CombatState state = new CombatState();
        state.deserializeNBT(living.registryAccess(), payload.state());
        living.setData(CRBAttachments.COMBAT, state);
    }
}
