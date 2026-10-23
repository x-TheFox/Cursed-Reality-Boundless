package io.github.xthefox.crb.projectile;

import io.github.xthefox.crb.core.CRBConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

/**
 * Terrain destruction: JJA's air-sphere structure loads ({@code function 4x4sphere}) and fluid
 * drains ({@code fill ... air replace water}). Obeys the {@code griefing} setting and the
 * {@code mobGriefing} game rule; unbreakable blocks (bedrock, barriers) and block entities survive.
 */
public final class Carve {
    private Carve() {}

    public static boolean griefingAllowed(ServerLevel level) {
        return CRBConfig.SERVER.griefing.get() && level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
    }

    /** Clears a sphere. Returns the number of blocks removed. */
    public static int sphere(ServerLevel level, Vec3 center, double radius) {
        if (!griefingAllowed(level)) return 0;
        int n = 0;
        int r = (int) Math.ceil(radius);
        double r2 = radius * radius;
        BlockPos c = BlockPos.containing(center);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
            if (x * x + y * y + z * z > r2) continue;
            p.set(c.getX() + x, c.getY() + y, c.getZ() + z);
            BlockState s = level.getBlockState(p);
            if (s.isAir() || !breakable(level, p, s)) continue;
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            n++;
        }
        return n;
    }

    /** Removes water and lava within a cube of half-size {@code r} (JJA's Lapse Blue drain). */
    public static int drain(ServerLevel level, Vec3 center, int r) {
        if (!griefingAllowed(level)) return 0;
        int n = 0;
        BlockPos c = BlockPos.containing(center);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
            p.set(c.getX() + x, c.getY() + y, c.getZ() + z);
            BlockState s = level.getBlockState(p);
            if (s.getFluidState().isEmpty() || !(s.getFluidState().is(Fluids.WATER) || s.getFluidState().is(Fluids.FLOWING_WATER)
                    || s.getFluidState().is(Fluids.LAVA) || s.getFluidState().is(Fluids.FLOWING_LAVA))) continue;
            if (!s.is(Blocks.WATER) && !s.is(Blocks.LAVA)) continue; // waterlogged blocks keep their block
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            n++;
        }
        return n;
    }

    static boolean breakable(ServerLevel level, BlockPos p, BlockState s) {
        return s.getDestroySpeed(level, p) >= 0 && !s.hasBlockEntity();
    }
}
