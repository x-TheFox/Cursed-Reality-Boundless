package io.github.xthefox.crb.gametest;

import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.combat.CombatDamage;
import io.github.xthefox.crb.combat.DamageKind;
import io.github.xthefox.crb.combat.Infinity;
import io.github.xthefox.crb.move.MoveEngine;
import io.github.xthefox.crb.move.MoveLogic;
import io.github.xthefox.crb.projectile.TechniqueProjectile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * Limitless projectiles (extracted runner lines + Java pull), Infinity, Warp and Blue Teleport.
 * Each test runs in its own batch: Lapse Blue pulls everything within 22 blocks, neighbouring
 * test plots included.
 */
@GameTestHolder(CRB.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LimitlessGameTests {
    private LimitlessGameTests() {}

    /** Explosions and carving must not wreck neighbouring test plots. */
    private static void noGriefing(GameTestHelper h) {
        h.getLevel().getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(false, h.getLevel().getServer());
    }

    private static ServerPlayer gojo(GameTestHelper h) {
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        Vec3 base = h.absoluteVec(new Vec3(2.5, 1, 1.5));
        p.moveTo(base.x, base.y, base.z, 0, 0); // facing +Z
        return p;
    }

    private static LivingEntity dummy(GameTestHelper h, Vec3 at, float yaw) {
        LivingEntity z = h.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        z.moveTo(at.x, at.y, at.z, yaw, 0);
        z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        z.setHealth(1000);
        return z;
    }

    private static List<TechniqueProjectile> projectiles(GameTestHelper h, String name, Vec3 near) {
        return h.getLevel().getEntitiesOfClass(TechniqueProjectile.class, new AABB(near, near).inflate(24), p -> p.name().equals(name));
    }

    @GameTest(template = "empty5", timeoutTicks = 60, batch = "limitless1")
    public static void lapseBluePullsAndHurts(GameTestHelper h) {
        noGriefing(h);
        ServerPlayer p = gojo(h);
        // everything stays inside the 5x5 plot so GameTest cleans it up
        LivingEntity z = dummy(h, p.position().add(0, 0, 3), 180);
        TechniqueProjectile lapse = TechniqueProjectile.spawn(h.getLevel(), "Lapse2", p, p.position().add(-1.5, 0, 1), 0, 0);
        lapse.setRemaining(40);
        double before = z.position().distanceTo(lapse.position().add(0, 1.5, 0)); // it rises 1.5 on its first tick
        // before its first implosion explosions (Deleter 35) throw everything back out
        h.runAfterDelay(3, () -> h.assertTrue(z.distanceTo(lapse) < before - 1,
                "Lapse Blue pulls (" + before + " -> " + z.distanceTo(lapse) + ")"));
        h.runAfterDelay(10, () -> {
            h.assertTrue(z.getHealth() < 1000, "pull damage lands (health " + z.getHealth() + ")");
            h.assertTrue(p.getHealth() == p.getMaxHealth(), "the owner is untouched");
            lapse.discard();
            z.discard();
            h.succeed();
        });
    }

    @GameTest(template = "empty5", timeoutTicks = 60, batch = "limitless2")
    public static void lapseStopsAtAWallAndImplodes(GameTestHelper h) {
        noGriefing(h);
        ServerPlayer p = gojo(h);
        for (int y = 0; y < 4; y++) for (int x = 0; x < 5; x++) h.setBlock(new BlockPos(x, y, 7), Blocks.STONE);
        TechniqueProjectile lapse = TechniqueProjectile.spawn(h.getLevel(), "Lapse", p, p.position().add(0, 1, 0.5), 0, 0);
        lapse.setRemaining(45);
        lapse.launch(new Vec3(0, 0, 1));
        h.succeedWhen(() -> {
            List<TechniqueProjectile> blue = projectiles(h, "Lapse2", p.position());
            h.assertTrue(!blue.isEmpty(), "Lapse2 spawns on contact");
            h.assertTrue(blue.getFirst().ownedBy(p), "the implosion keeps its owner");
            h.assertTrue(blue.getFirst().getZ() < h.absoluteVec(new Vec3(0, 0, 8)).z, "it implodes at the wall, not past it");
            blue.forEach(TechniqueProjectile::discard);
        });
    }

    @GameTest(template = "empty5", timeoutTicks = 40, batch = "limitless3")
    public static void infinityStopsDamageButNotBlackFlash(GameTestHelper h) {
        // mock players are creative (never hurt), so a zombie holds Infinity here; it works on any entity
        LivingEntity p = dummy(h, h.absoluteVec(new Vec3(2.5, 1, 1.5)), 0);
        LivingEntity z = dummy(h, p.position().add(0, 0, 1.5), 180);
        Infinity.cast(p);
        float hp = p.getHealth();
        h.assertTrue(Infinity.active(p), "Infinity is up after casting");
        h.assertFalse(CombatDamage.deal(z, p, CombatDamage.Hit.of(6, DamageKind.BLUNT)), "technique damage is stopped");
        p.hurt(h.getLevel().damageSources().mobAttack(z), 5);
        h.assertTrue(p.getHealth() == hp, "vanilla damage is stopped (health " + p.getHealth() + ")");
        h.assertTrue(Infinity.piercing(() -> CombatDamage.deal(z, p, CombatDamage.Hit.of(2, DamageKind.BLUNT))), "Black Flash pierces");
        h.assertFalse(Infinity.blocks(p, z, DamageKind.SURE_HIT), "sure-hit is never blocked");
        Infinity.toggleOff(p);
        h.assertFalse(Infinity.active(p), "pressing Infinity again drops it");
        p.discard();
        z.discard();
        h.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 40, batch = "limitless4")
    public static void blueTeleportBlinksToTheCrosshair(GameTestHelper h) {
        ServerPlayer p = gojo(h);
        // inside the plot: the ray must stop at this wall, not at whatever is past the test area
        for (int y = 0; y < 5; y++) for (int x = 0; x < 5; x++) h.setBlock(new BlockPos(x, y, 4), Blocks.STONE);
        double z0 = p.getZ();
        MoveEngine.start(p, CRB.id("limitless/blue_teleport"), 6);
        h.runAfterDelay(1, () -> {
            double wall = h.absoluteVec(new Vec3(0, 0, 4)).z;
            h.assertTrue(p.getZ() > z0 + 1.5, "Gojo moved forward (" + z0 + " -> " + p.getZ() + ")");
            h.assertTrue(p.getZ() < wall, "and stopped in front of the wall");
            MoveEngine.end(p, MoveLogic.EndReason.CANCELLED);
            h.succeed();
        });
    }

    @GameTest(template = "empty5", timeoutTicks = 60, batch = "limitless5")
    public static void spatialWarpLandsBehindTheTarget(GameTestHelper h) {
        noGriefing(h);
        ServerPlayer p = gojo(h);
        LivingEntity z = dummy(h, p.position().add(0, 0, 3), 180); // facing Gojo, inside the plot
        MoveEngine.start(p, CRB.id("limitless/limitless4c"), 36);
        h.runAfterDelay(14, () -> h.assertTrue(p.getZ() > z.getZ(), "Gojo is behind the target (" + p.getZ() + " vs " + z.getZ() + ")"));
        h.runAfterDelay(24, () -> {
            h.assertTrue(z.getHealth() <= 1000 - 8, "the warp kick lands (health " + z.getHealth() + ")");
            z.discard();
            h.succeed();
        });
    }
}
