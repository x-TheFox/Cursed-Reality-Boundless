package io.github.xthefox.crb.client.cinematic;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CinematicMathTest {
    @Test
    void everyEaseStartsAtZeroAndEndsAtOne() {
        for (Ease e : Ease.values()) {
            assertEquals(0, e.apply(0), 1e-6, e.name());
            assertEquals(1, e.apply(1), e == Ease.SPRING ? 0.01 : 1e-6, e.name());
        }
        assertEquals(0.5, Ease.LINEAR.apply(0.5), 1e-9);
        assertTrue(Ease.IN_SINE.apply(0.5) < 0.5, "in_sine starts slow");
        assertTrue(Ease.OUT_BACK.apply(0.8) > 1, "out_back overshoots");
        assertEquals(Ease.IN_OUT_CIRC, Ease.parse("in_out_circ"));
        assertEquals(Ease.LINEAR, Ease.parse("no_such_ease"));
    }

    @Test
    void relativeRotationsLikeBedrock() {
        assertEquals(-11f, CinematicCues.relative("~-21", 10f), 1e-6);
        assertEquals(190f, CinematicCues.relative("~180", 10f), 1e-6);
        assertEquals(10f, CinematicCues.relative("~", 10f), 1e-6);
        assertEquals(45f, CinematicCues.relative("45", 10f), 1e-6);
    }

    @Test
    void lookAtMatchesMinecraftAngles() {
        float[] south = CameraPose.lookAt(Vec3.ZERO, new Vec3(0, 0, 5));
        assertEquals(0, south[0], 1e-4);
        assertEquals(0, south[1], 1e-4);
        float[] up = CameraPose.lookAt(Vec3.ZERO, new Vec3(0, 5, 0.0001));
        assertEquals(-90, up[0], 0.1);
        float[] west = CameraPose.lookAt(Vec3.ZERO, new Vec3(-5, 0, 0));
        assertEquals(90, west[1], 1e-3);
    }

    @Test
    void poseLerpTakesShortestYaw() {
        CameraPose a = new CameraPose(Vec3.ZERO, 0, 170, 0);
        CameraPose b = new CameraPose(new Vec3(2, 0, 0), 10, -170, 0);
        CameraPose mid = CameraPose.lerp(a, b, 0.5);
        assertEquals(180, mid.yaw(), 1e-4);
        assertEquals(5, mid.pitch(), 1e-4);
        assertEquals(1, mid.pos().x, 1e-9);
    }
}
