package io.github.xthefox.crb.gametest;

import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.data.CRBAttachments;
import io.github.xthefox.crb.move.MoveEngine;
import io.github.xthefox.crb.move.MoveInstance;
import io.github.xthefox.crb.move.MoveLogic;
import io.github.xthefox.crb.move.MoveLogics;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/** Server move engine: tick order, duration, stun cancellation. */
@GameTestHolder(CRB.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MoveEngineGameTests {
    static final ResourceLocation COUNTER = CRB.id("test/counter");
    static final List<String> LOG = new ArrayList<>();

    static {
        MoveLogics.register(COUNTER, new MoveLogic() {
            @Override
            public void tick(MoveInstance move, int t) {
                move.put("ticks", move.<Integer>get("ticks", 0) + 1);
                move.put("last", t);
            }

            @Override
            public void end(MoveInstance move, EndReason reason) {
                LOG.add(move.caster().getUUID() + ":" + reason + ":" + move.get("ticks", 0) + ":" + move.get("last", -1));
            }
        });
    }

    private MoveEngineGameTests() {}

    private static LivingEntity dummy(GameTestHelper h) {
        LivingEntity zombie = h.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        zombie.setInvulnerable(true);
        return zombie;
    }

    @GameTest(template = "empty5", timeoutTicks = 60)
    public static void moveRunsForItsDuration(GameTestHelper h) {
        LivingEntity caster = dummy(h);
        MoveEngine.start(caster, COUNTER, 20);
        h.assertTrue(MoveEngine.isBusy(caster), "move should be active");
        String key = caster.getUUID() + ":FINISHED:20:19";
        h.succeedWhen(() -> {
            h.assertTrue(LOG.contains(key), "expected " + key + " in " + LOG);
            h.assertFalse(MoveEngine.isBusy(caster), "move should have ended");
        });
    }

    @GameTest(template = "empty5", timeoutTicks = 40)
    public static void stunCancelsMove(GameTestHelper h) {
        LivingEntity caster = dummy(h);
        MoveEngine.start(caster, COUNTER, 200);
        h.runAfterDelay(5, () -> caster.getData(CRBAttachments.COMBAT).stun(10));
        h.succeedWhen(() -> {
            h.assertTrue(LOG.stream().anyMatch(s -> s.startsWith(caster.getUUID() + ":STUNNED:")), "expected a stunned end in " + LOG);
            h.assertFalse(MoveEngine.isBusy(caster), "move should have ended");
        });
    }

    @GameTest(template = "empty5", timeoutTicks = 20)
    public static void newMoveInterruptsOld(GameTestHelper h) {
        LivingEntity caster = dummy(h);
        MoveEngine.start(caster, COUNTER, 200);
        MoveEngine.start(caster, COUNTER, 200);
        h.assertTrue(LOG.stream().anyMatch(s -> s.startsWith(caster.getUUID() + ":INTERRUPTED:")), "first move should be interrupted");
        MoveEngine.end(caster, MoveLogic.EndReason.CANCELLED);
        h.succeed();
    }
}
