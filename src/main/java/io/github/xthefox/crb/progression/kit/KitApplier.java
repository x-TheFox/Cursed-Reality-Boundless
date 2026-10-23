package io.github.xthefox.crb.progression.kit;

import io.github.xthefox.crb.ability.Technique;
import io.github.xthefox.crb.core.CRBRegistries;
import io.github.xthefox.crb.data.CRBAttachments;
import io.github.xthefox.crb.data.SorcererData;
import io.github.xthefox.crb.progression.TechniqueOwners;
import io.github.xthefox.crb.progression.Traits;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Applies kits and single techniques to players, enforcing technique ownership. */
public final class KitApplier {
    private KitApplier() {}

    public record Outcome(boolean success, List<Component> messages) {}

    /** Every unique id the kit would take: explicit claims, its technique, unique traits, and Shrine for vessels. */
    public static Set<ResourceLocation> claimsOf(Kit kit) {
        Set<ResourceLocation> ids = new LinkedHashSet<>(kit.claims());
        kit.technique().ifPresent(ids::add);
        kit.traits().stream().filter(Traits.UNIQUE::contains).forEach(ids::add);
        if (kit.fingers().isPresent()) ids.add(CRBRegistries.SHRINE.getId());
        ids.removeIf(id -> !TechniqueOwners.isUnique(id));
        return ids;
    }

    public static Outcome apply(ServerPlayer player, Kit kit, boolean force) {
        return apply(player, kit, force, false);
    }

    /**
     * @param techniqueOnly only replace the technique, keeping traits, vessel state and stats
     */
    private static Outcome apply(ServerPlayer player, Kit kit, boolean force, boolean techniqueOnly) {
        List<Component> msgs = new ArrayList<>();
        TechniqueOwners owners = TechniqueOwners.get(player.server);
        UUID uuid = player.getUUID();
        Set<ResourceLocation> claims = claimsOf(kit);

        if (kit.technique().isPresent() && !CRBRegistries.TECHNIQUES.containsKey(kit.technique().get())) {
            return new Outcome(false, List.of(Component.translatable("commands.crb.technique.unknown", kit.technique().get().toString())));
        }
        if (!force) {
            for (ResourceLocation id : claims) {
                if (!owners.available(id, uuid)) {
                    return new Outcome(false, List.of(Component.translatable("commands.crb.kit.taken",
                            id.toString(), ownerName(player, owners.owner(id)))));
                }
            }
        }

        SorcererData data = player.getData(CRBAttachments.SORCERER);
        Set<ResourceLocation> keep = new LinkedHashSet<>(claims);
        if (techniqueOnly) {
            keep.addAll(data.traits());
            if (data.isVessel()) keep.add(CRBRegistries.SHRINE.getId());
        }
        releaseClaimsNotIn(player, data, keep, owners, msgs);

        for (ResourceLocation id : claims) {
            Optional<UUID> prev = owners.transfer(id, uuid);
            prev.filter(p -> !p.equals(uuid)).ifPresent(p -> {
                msgs.add(Component.translatable("commands.crb.owner.moved", id.toString(), ownerName(player, prev)));
                ServerPlayer old = player.server.getPlayerList().getPlayer(p);
                if (old != null) stripClaim(old, id);
            });
        }

        if (techniqueOnly) {
            kit.technique().ifPresent(t -> {
                Technique tech = CRBRegistries.technique(t);
                data.setTechnique(t, kit.forms() == Kit.MAX_FORMS ? tech.maxForms() : Math.min(kit.forms(), tech.maxForms()));
            });
            msgs.add(0, Component.translatable("commands.crb.technique.set", kit.display(), player.getDisplayName()));
            return new Outcome(true, msgs);
        }

        data.clearTraits();
        kit.technique().ifPresentOrElse(t -> {
            Technique tech = CRBRegistries.technique(t);
            data.setTechnique(t, kit.forms() == Kit.MAX_FORMS ? tech.maxForms() : Math.min(kit.forms(), tech.maxForms()));
        }, data::clearTechnique);
        kit.traits().forEach(data::addTrait);
        kit.unlocks().forEach(data::addTrait);
        kit.combatStyles().forEach(data::addTrait);
        kit.grade().ifPresent(data::setGrade);
        kit.stats().forEach((k, v) -> applyStat(data, k, v));
        data.setEnergy(data.energyCap());
        data.clearCooldowns();
        data.setVessel(kit.fingers().isPresent());
        data.setFingers(kit.fingers().orElse(0));

        for (Kit.ItemEntry e : kit.items()) {
            Item item = BuiltInRegistries.ITEM.get(e.id());
            if (item == Items.AIR) {
                msgs.add(Component.translatable("commands.crb.kit.missing_item", e.id().toString()));
                continue;
            }
            ItemStack stack = new ItemStack(item, e.count());
            if (!player.getInventory().add(stack)) player.drop(stack, false);
        }

        msgs.add(0, Component.translatable("commands.crb.kit.applied", kit.display(), player.getDisplayName()));
        return new Outcome(true, msgs);
    }

    /** Sets a technique only (no traits or stats), used by {@code /crb technique set}. */
    public static Outcome setTechnique(ServerPlayer player, ResourceLocation technique, int forms, boolean force) {
        Kit kit = new Kit(technique.toString(), Optional.of(technique), forms, List.of(), List.of(), Optional.empty(),
                java.util.Map.of(), List.of(), List.of(), Optional.empty(), List.of());
        return apply(player, kit, force, true);
    }

    /** Frees unique ids the player holds that the new loadout does not include (an operator re-assigning a player). */
    private static void releaseClaimsNotIn(ServerPlayer player, SorcererData data, Set<ResourceLocation> keep,
                                           TechniqueOwners owners, List<Component> msgs) {
        Set<ResourceLocation> held = new LinkedHashSet<>(data.traits());
        held.add(data.technique());
        if (data.isVessel()) held.add(CRBRegistries.SHRINE.getId());
        for (ResourceLocation id : held) {
            if (keep.contains(id)) continue;
            if (owners.owner(id).filter(player.getUUID()::equals).isPresent()) {
                owners.release(id);
                msgs.add(Component.translatable("commands.crb.owner.released", id.toString()));
            }
        }
    }

    /** Removes a claimed technique/trait from its previous holder after an operator transfer. */
    public static void stripClaim(ServerPlayer holder, ResourceLocation id) {
        SorcererData d = holder.getData(CRBAttachments.SORCERER);
        if (d.technique().equals(id)) d.clearTechnique();
        d.removeTrait(id);
        if (id.equals(CRBRegistries.SHRINE.getId())) { d.setVessel(false); d.setFingers(0); }
        holder.sendSystemMessage(Component.translatable("crb.owner.lost", id.toString()));
    }

    public static boolean applyStat(SorcererData data, String stat, int value) {
        switch (stat) {
            case "strength" -> data.setStrength(value);
            case "endurance" -> data.setEndurance(value);
            case "agility" -> data.setAgility(value);
            case "energy_cap" -> data.setEnergyCap(value);
            case "invest" -> data.setInvestPoints(value);
            case "level" -> data.setLevel(value);
            case "exp" -> data.setExp(value);
            default -> { return false; }
        }
        return true;
    }

    public static String ownerName(ServerPlayer context, Optional<UUID> owner) {
        return owner.map(u -> {
            ServerPlayer p = context.server.getPlayerList().getPlayer(u);
            if (p != null) return p.getGameProfile().getName();
            return context.server.getProfileCache() == null ? u.toString()
                    : context.server.getProfileCache().get(u).map(gp -> gp.getName()).orElse(u.toString());
        }).orElse("?");
    }
}
