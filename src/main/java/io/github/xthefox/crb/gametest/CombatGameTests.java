package io.github.xthefox.crb.gametest;

import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.combat.Melee;
import io.github.xthefox.crb.data.CRBAttachments;
import io.github.xthefox.crb.move.MoveEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Melee string, Black Flash and extracted move simulation against a real target. */
@GameTestHolder(CRB.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CombatGameTests {
    private CombatGameTests() {}

    /** A mock player at the south edge facing north (+Z → yaw 0 faces south, so yaw 180) and a zombie 1.6 blocks ahead. */
    private static ServerPlayer attacker(GameTestHelper h, LivingEntity[] target, double distance) {
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        Vec3 base = h.absoluteVec(new Vec3(2.5, 1, 1.5));
        p.moveTo(base.x, base.y, base.z, 0, 0); // yaw 0: facing +Z
        LivingEntity z = h.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(2, 1, 1));
        z.moveTo(base.x, base.y, base.z + distance, 180, 0);
        z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        z.setHealth(1000);
        target[0] = z;
        return p;
    }

    @GameTest(template = "empty5", timeoutTicks = 60)
    public static void m1StringDamagesAndStuns(GameTestHelper h) {
        LivingEntity[] t = new LivingEntity[1];
        ServerPlayer p = attacker(h, t, 1.6);
        Melee.press(p);
        h.runAfterDelay(7, () -> {
            h.assertTrue(t[0].getHealth() <= 996, "first M1 should deal 4 (health " + t[0].getHealth() + ")");
            h.assertTrue(t[0].getData(CRBAttachments.COMBAT).stunned(), "M1 stuns");
            h.succeed();
        });
    }

    @GameTest(template = "empty5", timeoutTicks = 80)
    public static void fourthHitEndsTheString(GameTestHelper h) {
        LivingEntity[] t = new LivingEntity[1];
        ServerPlayer p = attacker(h, t, 1.6);
        for (int i = 0; i < 4; i++) {
            h.runAfterDelay(1 + i * 11, () -> Melee.press(p));
        }
        h.runAfterDelay(4 * 11 + 6, () -> {
            h.assertTrue(t[0].getHealth() <= 1000 - 16, "four hits should land (health " + t[0].getHealth() + ")");
            h.assertTrue(p.getData(CRBAttachments.COMBO).m1Count() == 0, "string resets after the finisher");
            h.succeed();
        });
    }

    @GameTest(template = "empty5", timeoutTicks = 60)
    public static void blackFlashMultipliesAndEntersTheZone(GameTestHelper h) {
        LivingEntity[] t = new LivingEntity[1];
        ServerPlayer p = attacker(h, t, 1.6);
        Melee.forceNextBlackFlash = true;
        Melee.press(p);
        h.runAfterDelay(7, () -> {
            h.assertTrue(t[0].getHealth() <= 1000 - 4 * 7, "Black Flash deals x7 (health " + t[0].getHealth() + ")");
            h.assertTrue(p.getData(CRBAttachments.COMBAT).inTheZone(), "attacker enters The Zone");
            h.assertTrue(MoveEngine.active(p).map(m -> m.moveId().getPath().equals("blackflash1")).orElse(false), "Black Flash cinematic plays");
            MoveEngine.end(p, io.github.xthefox.crb.move.MoveLogic.EndReason.CANCELLED);
            h.succeed();
        });
    }

    @GameTest(template = "empty5", timeoutTicks = 60)
    public static void extractedStrongPunchHitsOnItsTick(GameTestHelper h) {
        LivingEntity[] t = new LivingEntity[1];
        ServerPlayer p = attacker(h, t, 2.0);
        MoveEngine.start(p, CRB.id("fighting/basic/strong_punch"), 21);
        h.runAfterDelay(9, () -> h.assertTrue(t[0].getHealth() == 1000, "no damage before tick 11"));
        h.runAfterDelay(14, () -> {
            h.assertTrue(t[0].getHealth() <= 996, "JJA's strong punch hits at tick 11 for 4+ (health " + t[0].getHealth() + ")");
            h.succeed();
        });
    }
}
