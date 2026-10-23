package io.github.xthefox.crb.ability;

import io.github.xthefox.crb.core.CRBConfig;
import io.github.xthefox.crb.core.CRBRegistries;
import io.github.xthefox.crb.data.CRBAttachments;
import io.github.xthefox.crb.data.CombatState;
import io.github.xthefox.crb.data.SorcererData;
import io.github.xthefox.crb.move.MoveEngine;
import io.github.xthefox.crb.net.MovePayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.Optional;

/** Handles {@link MovePayloads.AbilityInput}: picks the variant, charges it, starts the move. */
public final class AbilityService {
    public static final int DOMAIN_KEY = 5;
    /** The combat style's arts (JJA: right-click with the style item). */
    public static final int STYLE_ART_KEY = 6;
    /** Inputs closer together than this are dropped (anti-spam; JJA's item use fires at most once per tick). */
    private static final int MIN_INPUT_INTERVAL = 2;

    private AbilityService() {}

    /** A player as an {@link AbilityUser}. Requirement tags are full trait ids (e.g. {@code crb:six_eyes}). */
    public record PlayerUser(ServerPlayer player, SorcererData data, CombatState combat) implements AbilityUser {
        public static PlayerUser of(ServerPlayer p) {
            return new PlayerUser(p, p.getData(CRBAttachments.SORCERER), p.getData(CRBAttachments.COMBAT));
        }

        @Override public int energy() { return data.energy(); }
        @Override public int cooldown(String key) { return data.cooldown(key); }
        @Override public int forms() { return data.forms(); }
        @Override public boolean has(String tag) {
            ResourceLocation id = ResourceLocation.tryParse(tag);
            return id != null && data.has(id);
        }
        @Override public boolean canAct() {
            return !combat.stunned() && !MoveEngine.isBusy(player) && player.isAlive() && !player.isSpectator();
        }
    }

    public static void handle(ServerPlayer player, MovePayloads.AbilityInput input) {
        long now = player.level().getGameTime();
        if (now - player.getData(CRBAttachments.LAST_INPUT) < MIN_INPUT_INTERVAL) return;
        player.setData(CRBAttachments.LAST_INPUT, now);

        SorcererData data = player.getData(CRBAttachments.SORCERER);
        Optional<Ability> ability;
        if (input.key() == STYLE_ART_KEY) {
            ability = Optional.of(io.github.xthefox.crb.combat.CombatStyle.of(player).arts());
        } else {
            if (!data.hasTechnique()) return;
            Technique tech = CRBRegistries.technique(data.technique());
            ability = input.key() == DOMAIN_KEY ? tech.domain()
                    : tech.abilityAt(Math.floorMod(input.page(), tech.pages()), input.key());
        }
        if (ability.isEmpty()) return;

        PlayerUser user = PlayerUser.of(player);
        VariantResolver.Result r = VariantResolver.resolve(ability.get(), Modifiers.fromBits(input.modifierBits()), user,
                CRBConfig.SERVER.cooldowns.get());
        if (!r.success()) {
            feedback(player, r);
            return;
        }
        Variant v = r.variant().orElseThrow();
        data.addEnergy(-v.energyCost());
        if (v.cooldownTicks() > 0) data.setCooldown(v.cooldownKey(), v.cooldownTicks());
        player.getData(CRBAttachments.COMBO).resetString(); // JJA: starting a move clears the M1 string
        MoveEngine.start(player, ResourceLocation.fromNamespaceAndPath("crb", v.moveId()), v.durationTicks(), v);
    }

    /** JJA answers failed casts with a low note.pling and a chat line; we use the action bar. */
    private static void feedback(ServerPlayer player, VariantResolver.Result r) {
        if (r.failure() == VariantResolver.Failure.BUSY || r.failure() == VariantResolver.Failure.NO_VARIANT) return;
        Component name = r.variant().map(v -> (Component) Component.translatable(v.nameKey())).orElse(Component.empty());
        Component msg = switch (r.failure()) {
            case ON_COOLDOWN -> Component.translatable("crb.ability.cooldown", name,
                    String.format("%.1f", player.getData(CRBAttachments.SORCERER).cooldown(r.variant().get().cooldownKey()) / 20f));
            case NOT_ENOUGH_ENERGY -> Component.translatable("crb.ability.energy", name, r.variant().get().energyCost());
            case LOCKED -> Component.translatable("crb.ability.locked");
            default -> Component.empty();
        };
        player.displayClientMessage(msg.copy().withStyle(ChatFormatting.RED), true);
        player.level().playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS,
                0.3f, r.failure() == VariantResolver.Failure.ON_COOLDOWN ? 0.4f : 0.87f);
    }
}
