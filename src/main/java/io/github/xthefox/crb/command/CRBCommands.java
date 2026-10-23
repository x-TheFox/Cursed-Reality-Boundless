package io.github.xthefox.crb.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.xthefox.crb.core.CRBRegistries;
import io.github.xthefox.crb.data.CRBAttachments;
import io.github.xthefox.crb.data.SorcererData;
import io.github.xthefox.crb.net.CRBNetwork;
import io.github.xthefox.crb.progression.Grade;
import io.github.xthefox.crb.progression.TechniqueOwners;
import io.github.xthefox.crb.progression.Traits;
import io.github.xthefox.crb.progression.kit.Kit;
import io.github.xthefox.crb.progression.kit.KitApplier;
import io.github.xthefox.crb.progression.kit.KitManager;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * {@code /crb} operator commands (permission level 2). See research doc 06 §3.
 */
public final class CRBCommands {
    private static final SuggestionProvider<CommandSourceStack> KITS = (c, b) ->
            SharedSuggestionProvider.suggestResource(KitManager.INSTANCE.all().keySet(), b);
    private static final SuggestionProvider<CommandSourceStack> TECHNIQUES = (c, b) ->
            SharedSuggestionProvider.suggestResource(CRBRegistries.TECHNIQUES.keySet(), b);
    private static final SuggestionProvider<CommandSourceStack> TRAITS = (c, b) ->
            SharedSuggestionProvider.suggestResource(Traits.all(), b);
    private static final SuggestionProvider<CommandSourceStack> CLAIMABLE = (c, b) ->
            SharedSuggestionProvider.suggestResource(Stream.concat(CRBRegistries.TECHNIQUES.keySet().stream(), Traits.UNIQUE.stream()), b);
    private static final SuggestionProvider<CommandSourceStack> STATS = (c, b) ->
            SharedSuggestionProvider.suggest(new String[]{"strength", "endurance", "agility", "energy_cap", "invest", "level", "exp"}, b);
    private static final SuggestionProvider<CommandSourceStack> GRADES = (c, b) ->
            SharedSuggestionProvider.suggest(Stream.of(Grade.values()).map(Grade::serializedName), b);

    private static final SuggestionProvider<CommandSourceStack> MOVES = (c, b) ->
            SharedSuggestionProvider.suggestResource(io.github.xthefox.crb.move.MoveDurations.INSTANCE.ids(), b);

    private CRBCommands() {}

    private static int movePlay(CommandContext<CommandSourceStack> c, int duration) throws CommandSyntaxException {
        ResourceLocation id = ResourceLocationArgument.getId(c, "move");
        int d = duration > 0 ? duration : io.github.xthefox.crb.move.MoveDurations.INSTANCE.get(id).orElse(-1);
        if (d <= 0) {
            c.getSource().sendFailure(Component.translatable("commands.crb.move.unknown", id.toString()));
            return 0;
        }
        int n = 0;
        for (var e : EntityArgument.getEntities(c, "targets")) {
            if (e instanceof net.minecraft.world.entity.LivingEntity l) {
                io.github.xthefox.crb.move.MoveEngine.start(l, id, d);
                n++;
            }
        }
        int count = n;
        c.getSource().sendSuccess(() -> Component.translatable("commands.crb.move.played", id.toString(), count, d), true);
        return n;
    }

    private static int moveStop(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        int n = 0;
        for (var e : EntityArgument.getEntities(c, "targets")) {
            if (e instanceof net.minecraft.world.entity.LivingEntity l && io.github.xthefox.crb.move.MoveEngine.isBusy(l)) {
                io.github.xthefox.crb.move.MoveEngine.end(l, io.github.xthefox.crb.move.MoveLogic.EndReason.CANCELLED);
                n++;
            }
        }
        return n;
    }

    public static void register(CommandDispatcher<CommandSourceStack> d, CommandBuildContext ctx) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("crb").requires(s -> s.hasPermission(2));

        root.then(Commands.literal("kit")
                .then(Commands.literal("list").executes(CRBCommands::kitList))
                .then(Commands.literal("give")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("kit", ResourceLocationArgument.id()).suggests(KITS)
                                        .executes(c -> kitGive(c, false))
                                        .then(Commands.argument("force", BoolArgumentType.bool())
                                                .executes(c -> kitGive(c, BoolArgumentType.getBool(c, "force"))))))));

        root.then(Commands.literal("technique")
                .then(Commands.literal("set")
                        .then(Commands.argument("target", EntityArgument.player())
                                .then(Commands.argument("technique", ResourceLocationArgument.id()).suggests(TECHNIQUES)
                                        .executes(c -> techniqueSet(c, Kit.MAX_FORMS, false))
                                        .then(Commands.argument("forms", IntegerArgumentType.integer(0, 99))
                                                .executes(c -> techniqueSet(c, IntegerArgumentType.getInteger(c, "forms"), false))
                                                .then(Commands.argument("force", BoolArgumentType.bool())
                                                        .executes(c -> techniqueSet(c, IntegerArgumentType.getInteger(c, "forms"),
                                                                BoolArgumentType.getBool(c, "force"))))))))
                .then(Commands.literal("clear")
                        .then(Commands.argument("target", EntityArgument.player()).executes(CRBCommands::techniqueClear))));

        root.then(Commands.literal("trait")
                .then(Commands.literal("add").then(Commands.argument("target", EntityArgument.player())
                        .then(Commands.argument("trait", ResourceLocationArgument.id()).suggests(TRAITS).executes(c -> trait(c, true)))))
                .then(Commands.literal("remove").then(Commands.argument("target", EntityArgument.player())
                        .then(Commands.argument("trait", ResourceLocationArgument.id()).suggests(TRAITS).executes(c -> trait(c, false))))));

        root.then(Commands.literal("stats").then(Commands.literal("set").then(Commands.argument("target", EntityArgument.player())
                .then(Commands.argument("stat", StringArgumentType.word()).suggests(STATS)
                        .then(Commands.argument("value", IntegerArgumentType.integer(0)).executes(CRBCommands::statSet))))));

        root.then(Commands.literal("grade").then(Commands.literal("set").then(Commands.argument("target", EntityArgument.player())
                .then(Commands.argument("grade", StringArgumentType.word()).suggests(GRADES).executes(CRBCommands::gradeSet)))));

        root.then(Commands.literal("fingers").then(Commands.literal("set").then(Commands.argument("target", EntityArgument.player())
                .then(Commands.argument("count", IntegerArgumentType.integer(0, 20)).executes(CRBCommands::fingersSet)))));

        root.then(Commands.literal("energy")
                .then(Commands.literal("fill").then(Commands.argument("targets", EntityArgument.players()).executes(CRBCommands::energyFill)))
                .then(Commands.literal("set").then(Commands.argument("target", EntityArgument.player())
                        .then(Commands.argument("value", IntegerArgumentType.integer(0)).executes(CRBCommands::energySet)))));

        root.then(Commands.literal("cooldowns").then(Commands.literal("reset")
                .then(Commands.argument("targets", EntityArgument.players()).executes(CRBCommands::cooldownsReset))));

        root.then(Commands.literal("owner")
                .then(Commands.literal("list").executes(CRBCommands::ownerList))
                .then(Commands.literal("release").then(Commands.argument("id", ResourceLocationArgument.id()).suggests(CLAIMABLE)
                        .executes(CRBCommands::ownerRelease)))
                .then(Commands.literal("transfer").then(Commands.argument("id", ResourceLocationArgument.id()).suggests(CLAIMABLE)
                        .then(Commands.argument("target", EntityArgument.player()).executes(CRBCommands::ownerTransfer)))));

        root.then(Commands.literal("move")
                .then(Commands.literal("play").then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.argument("move", ResourceLocationArgument.id()).suggests(MOVES)
                                .executes(c -> movePlay(c, -1))
                                .then(Commands.argument("duration", IntegerArgumentType.integer(1, 20000))
                                        .executes(c -> movePlay(c, IntegerArgumentType.getInteger(c, "duration")))))))
                .then(Commands.literal("stop").then(Commands.argument("targets", EntityArgument.entities())
                        .executes(CRBCommands::moveStop))));

        root.then(Commands.literal("info").then(Commands.argument("target", EntityArgument.player()).executes(CRBCommands::info)));

        d.register(root);
    }

    // ---------------------------------------------------------------- kits

    private static int kitList(CommandContext<CommandSourceStack> c) {
        var kits = KitManager.INSTANCE.all();
        c.getSource().sendSuccess(() -> Component.translatable("commands.crb.kit.list", kits.size()), false);
        kits.forEach((id, kit) -> c.getSource().sendSuccess(() -> Component.literal(" " + id + " — " + kit.display()), false));
        return kits.size();
    }

    private static int kitGive(CommandContext<CommandSourceStack> c, boolean force) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(c, "targets");
        ResourceLocation id = ResourceLocationArgument.getId(c, "kit");
        Optional<Kit> kit = KitManager.INSTANCE.get(id);
        if (kit.isEmpty()) {
            c.getSource().sendFailure(Component.translatable("commands.crb.kit.unknown", id.toString()));
            return 0;
        }
        int ok = 0;
        for (ServerPlayer p : targets) {
            ok += report(c, KitApplier.apply(p, kit.get(), force));
            CRBNetwork.syncSorcerer(p);
        }
        return ok;
    }

    // ---------------------------------------------------------------- techniques & traits

    private static int techniqueSet(CommandContext<CommandSourceStack> c, int forms, boolean force) throws CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(c, "target");
        ResourceLocation id = ResourceLocationArgument.getId(c, "technique");
        int r = report(c, KitApplier.setTechnique(p, id, forms, force));
        CRBNetwork.syncSorcerer(p);
        return r;
    }

    private static int techniqueClear(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(c, "target");
        SorcererData d = data(p);
        ResourceLocation old = d.technique();
        TechniqueOwners owners = TechniqueOwners.get(p.server);
        if (owners.owner(old).filter(p.getUUID()::equals).isPresent()) owners.release(old);
        d.clearTechnique();
        CRBNetwork.syncSorcerer(p);
        c.getSource().sendSuccess(() -> Component.translatable("commands.crb.technique.cleared", p.getDisplayName()), true);
        return 1;
    }

    private static int trait(CommandContext<CommandSourceStack> c, boolean add) throws CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(c, "target");
        ResourceLocation id = ResourceLocationArgument.getId(c, "trait");
        SorcererData d = data(p);
        TechniqueOwners owners = TechniqueOwners.get(p.server);
        if (add) {
            if (!owners.available(id, p.getUUID())) {
                c.getSource().sendFailure(Component.translatable("commands.crb.kit.taken", id.toString(),
                        KitApplier.ownerName(p, owners.owner(id))));
                return 0;
            }
            owners.claim(id, p.getUUID());
            d.addTrait(id);
        } else {
            d.removeTrait(id);
            if (owners.owner(id).filter(p.getUUID()::equals).isPresent()) owners.release(id);
        }
        CRBNetwork.syncSorcerer(p);
        c.getSource().sendSuccess(() -> Component.translatable(add ? "commands.crb.trait.added" : "commands.crb.trait.removed",
                id.toString(), p.getDisplayName()), true);
        return 1;
    }

    // ---------------------------------------------------------------- stats

    private static int statSet(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(c, "target");
        String stat = StringArgumentType.getString(c, "stat");
        int value = IntegerArgumentType.getInteger(c, "value");
        if (!KitApplier.applyStat(data(p), stat, value)) {
            c.getSource().sendFailure(Component.translatable("commands.crb.stats.unknown", stat));
            return 0;
        }
        CRBNetwork.syncSorcerer(p);
        c.getSource().sendSuccess(() -> Component.translatable("commands.crb.stats.set", stat, value, p.getDisplayName()), true);
        return 1;
    }

    private static int gradeSet(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(c, "target");
        Grade g;
        try { g = Grade.parse(StringArgumentType.getString(c, "grade")); }
        catch (IllegalArgumentException e) {
            c.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        data(p).setGrade(g);
        CRBNetwork.syncSorcerer(p);
        c.getSource().sendSuccess(() -> Component.translatable("commands.crb.grade.set", p.getDisplayName(), g.serializedName()), true);
        return 1;
    }

    private static int fingersSet(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(c, "target");
        int count = IntegerArgumentType.getInteger(c, "count");
        SorcererData d = data(p);
        TechniqueOwners owners = TechniqueOwners.get(p.server);
        ResourceLocation shrine = CRBRegistries.SHRINE.getId();
        if (count > 0) {
            if (!owners.available(shrine, p.getUUID())) {
                c.getSource().sendFailure(Component.translatable("commands.crb.fingers.taken", KitApplier.ownerName(p, owners.owner(shrine))));
                return 0;
            }
            owners.claim(shrine, p.getUUID());
            d.setVessel(true);
        } else {
            d.setVessel(false);
            if (owners.owner(shrine).filter(p.getUUID()::equals).isPresent() && !d.technique().equals(shrine)) owners.release(shrine);
        }
        d.setFingers(count);
        CRBNetwork.syncSorcerer(p);
        c.getSource().sendSuccess(() -> Component.translatable("commands.crb.fingers.set", p.getDisplayName(), count), true);
        return 1;
    }

    private static int energyFill(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Collection<ServerPlayer> ps = EntityArgument.getPlayers(c, "targets");
        for (ServerPlayer p : ps) { SorcererData d = data(p); d.setEnergy(d.energyCap()); CRBNetwork.syncSorcerer(p); }
        c.getSource().sendSuccess(() -> Component.translatable("commands.crb.energy.filled", ps.size()), true);
        return ps.size();
    }

    private static int energySet(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(c, "target");
        int v = IntegerArgumentType.getInteger(c, "value");
        data(p).setEnergy(v);
        CRBNetwork.syncSorcerer(p);
        c.getSource().sendSuccess(() -> Component.translatable("commands.crb.energy.set", p.getDisplayName(), data(p).energy()), true);
        return 1;
    }

    private static int cooldownsReset(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Collection<ServerPlayer> ps = EntityArgument.getPlayers(c, "targets");
        for (ServerPlayer p : ps) { data(p).clearCooldowns(); CRBNetwork.syncSorcerer(p); }
        c.getSource().sendSuccess(() -> Component.translatable("commands.crb.cooldowns.reset", ps.size()), true);
        return ps.size();
    }

    // ---------------------------------------------------------------- ownership

    private static int ownerList(CommandContext<CommandSourceStack> c) {
        ServerPlayer ctx = c.getSource().getPlayer();
        var all = TechniqueOwners.get(c.getSource().getServer()).book().all();
        c.getSource().sendSuccess(() -> Component.translatable("commands.crb.owner.list", all.size()), false);
        all.forEach((id, uuid) -> c.getSource().sendSuccess(() -> Component.literal(" " + id + " → "
                + (ctx != null ? KitApplier.ownerName(ctx, Optional.of(uuid)) : uuid.toString())), false));
        return all.size();
    }

    private static int ownerRelease(CommandContext<CommandSourceStack> c) {
        ResourceLocation id = ResourceLocationArgument.getId(c, "id");
        Optional<UUID> prev = TechniqueOwners.get(c.getSource().getServer()).release(id);
        if (prev.isEmpty()) {
            c.getSource().sendFailure(Component.translatable("commands.crb.owner.none", id.toString()));
            return 0;
        }
        ServerPlayer holder = c.getSource().getServer().getPlayerList().getPlayer(prev.get());
        if (holder != null) { KitApplier.stripClaim(holder, id); CRBNetwork.syncSorcerer(holder); }
        c.getSource().sendSuccess(() -> Component.translatable("commands.crb.owner.released", id.toString()), true);
        return 1;
    }

    private static int ownerTransfer(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ResourceLocation id = ResourceLocationArgument.getId(c, "id");
        ServerPlayer target = EntityArgument.getPlayer(c, "target");
        TechniqueOwners owners = TechniqueOwners.get(c.getSource().getServer());
        Optional<UUID> prev = owners.transfer(id, target.getUUID());
        prev.filter(u -> !u.equals(target.getUUID()))
                .map(u -> c.getSource().getServer().getPlayerList().getPlayer(u))
                .ifPresent(old -> { KitApplier.stripClaim(old, id); CRBNetwork.syncSorcerer(old); });
        SorcererData d = data(target);
        if (CRBRegistries.TECHNIQUES.containsKey(id) && !id.equals(CRBRegistries.SHRINE.getId())) {
            d.setTechnique(id, CRBRegistries.technique(id).maxForms());
        } else if (id.equals(CRBRegistries.SHRINE.getId())) {
            d.setVessel(true);
            d.setFingers(Math.max(1, d.fingers()));
        } else {
            d.addTrait(id);
        }
        CRBNetwork.syncSorcerer(target);
        c.getSource().sendSuccess(() -> Component.translatable("commands.crb.owner.transferred", id.toString(), target.getDisplayName()), true);
        return 1;
    }

    // ---------------------------------------------------------------- info

    private static int info(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(c, "target");
        SorcererData d = data(p);
        String text = "%s: technique=%s forms=%d grade=%s CE=%d/%d STR=%d END=%d AGI=%d invest=%d lvl=%d vessel=%s fingers=%d traits=%s"
                .formatted(p.getGameProfile().getName(), d.technique(), d.forms(), d.grade().serializedName(), d.energy(), d.energyCap(),
                        d.strength(), d.endurance(), d.agility(), d.investPoints(), d.level(), d.isVessel(), d.fingers(), d.traits());
        c.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    // ---------------------------------------------------------------- helpers

    private static SorcererData data(ServerPlayer p) {
        return p.getData(CRBAttachments.SORCERER);
    }

    private static int report(CommandContext<CommandSourceStack> c, KitApplier.Outcome o) {
        if (!o.success()) {
            o.messages().forEach(c.getSource()::sendFailure);
            return 0;
        }
        o.messages().forEach(m -> c.getSource().sendSuccess(() -> m, true));
        return 1;
    }
}
