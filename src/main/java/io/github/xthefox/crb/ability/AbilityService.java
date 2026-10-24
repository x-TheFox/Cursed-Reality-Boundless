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

    /** Scales a variant's cursed-energy cost for a player (1 = unchanged). */
    @FunctionalInterface
    public interface CostModifier {
        float scale(ServerPlayer player, Variant variant);
    }

    /**
     * Takes an ability press before variant selection (Infinity: pressing it while Infinity is up
     * switches it off). Returns true if the press was handled.
     */
    @FunctionalInterface
    public interface PressOverride {
        boolean handle(ServerPlayer player, Ability ability, Modifiers modifiers);
    }

    private static final java.util.List<CostModifier> COST_MODIFIERS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final java.util.List<PressOverride> OVERRIDES = new java.util.concurrent.CopyOnWriteArrayList<>();

    private AbilityService() {}

    public static void registerCostModifier(CostModifier m) {
        COST_MODIFIERS.add(m);
    }

    public static void registerOverride(PressOverride o) {
        OVERRIDES.add(o);
    }

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
        @Override public float costScale(Variant v) {
            float scale = 1f;
            for (CostModifier m : COST_MODIFIERS) scale *= m.scale(player, v);
            return scale;
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

        Modifiers mods = Modifiers.fromBits(input.modifierBits());
        // JJA re-press: pressing the running move's ability again (any stance) launches a charge, picks a chant
        var running = MoveEngine.active(player);
        if (running.isPresent() && ability.get().id().equals(running.get().get("ability", ""))
                && io.github.xthefox.crb.move.MoveSim.INSTANCE.readsInput(running.get().moveId(), "input")) {
            running.get().input("input");
            return;
        }
        for (PressOverride o : OVERRIDES) if (o.handle(player, ability.get(), mods)) return;
        PlayerUser user = PlayerUser.of(player);
        VariantResolver.Result r = VariantResolver.resolve(ability.get(), mods, user, CRBConfig.SERVER.cooldowns.get());
        if (!r.success()) {
            feedback(player, r);
            return;
        }
        Variant v = r.variant().orElseThrow();
        data.addEnergy(-user.cost(v));
        if (v.cooldownTicks() > 0) data.setCooldown(v.cooldownKey(), v.cooldownTicks());
        player.getData(CRBAttachments.COMBO).resetString(); // JJA: starting a move clears the M1 string
        MoveEngine.start(player, ResourceLocation.fromNamespaceAndPath("crb", v.moveId()), v.durationTicks(), v)
                .put("ability", ability.get().id());
    }

    /** JJA answers failed casts with a low note.pling and a chat line; we use the action bar. */
    private static void feedback(ServerPlayer player, VariantResolver.Result r) {
        if (r.failure() == VariantResolver.Failure.BUSY || r.failure() == VariantResolver.Failure.NO_VARIANT) return;
        Component name = r.variant().map(v -> (Component) Component.translatable(v.nameKey())).orElse(Component.empty());
        Component msg = switch (r.failure()) {
            case ON_COOLDOWN -> Component.translatable("crb.ability.cooldown", name,
                    String.format("%.1f", player.getData(CRBAttachments.SORCERER).cooldown(r.variant().get().cooldownKey()) / 20f));
            case NOT_ENOUGH_ENERGY -> Component.translatable("crb.ability.energy", name,
                    (int) Math.ceil(r.variant().get().energyRequired() * PlayerUser.of(player).costScale(r.variant().get())));
            case LOCKED -> Component.translatable("crb.ability.locked");
            default -> Component.empty();
        };
        player.displayClientMessage(msg.copy().withStyle(ChatFormatting.RED), true);
        player.level().playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS,
                0.3f, r.failure() == VariantResolver.Failure.ON_COOLDOWN ? 0.4f : 0.87f);
    }
}
