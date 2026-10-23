package io.github.xthefox.crb.move;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.xthefox.crb.client.move.MoveScript;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MoveScriptTest {
    static final Path SCRIPTS = Path.of(System.getProperty("crb.projectDir", "."), "src/main/resources/assets/crb/move_scripts");

    static MoveScript load(String rel) throws Exception {
        return MoveScript.parse(JsonParser.parseString(Files.readString(SCRIPTS.resolve(rel + ".json"))).getAsJsonObject());
    }

    @Test
    void everyScriptParses() throws Exception {
        try (Stream<Path> s = Files.walk(SCRIPTS)) {
            List<Path> files = s.filter(p -> p.toString().endsWith(".json")).toList();
            assertTrue(files.size() > 600, "found " + files.size());
            int cues = 0;
            for (Path p : files) {
                MoveScript ms = MoveScript.parse(JsonParser.parseString(Files.readString(p)).getAsJsonObject());
                assertTrue(ms.duration() > 0, p.toString());
                for (MoveScript.Cue c : ms.cues()) {
                    assertTrue(c.t() >= 0 && c.until() >= c.t() && c.t() <= ms.duration(), p + " cue at " + c.t());
                }
                cues += ms.cues().size();
            }
            assertTrue(cues > 10000);
        }
    }

    /** The Hollow Purple beats documented in research doc 03, as extracted. */
    @Test
    void hollowPurpleMatchesResearch() throws Exception {
        MoveScript purple = load("limitless/limitless5");
        assertEquals(101, purple.duration());
        assertTrue(purple.cues().stream().anyMatch(c -> c.t() == 1 && c.type().equals("anim")
                && c.str("anim", "").equals("animation.imaginary_purple")));
        assertTrue(purple.cues().stream().anyMatch(c -> c.t() == 57 && c.type().equals("shake") && c.num("intensity", 0) == 2f));
        assertTrue(purple.cues().stream().anyMatch(c -> c.t() == 65 && c.type().equals("camera_fade")));
        assertTrue(purple.cues().stream().filter(c -> c.type().equals("impact")).count() >= 4);
    }

    @Test
    void localCoordinatesMatchVanilla() {
        // yaw 0 faces +Z: ^ ^ ^1 is one block south, ^1 ^ ^ is one block east ("left" of a south-facing player)
        Vec3 fwd = CuePositions.local(Vec3.ZERO, 0, 0, 0, 0, 1);
        assertEquals(0, fwd.x, 1e-5);
        assertEquals(1, fwd.z, 1e-5);
        Vec3 left = CuePositions.local(Vec3.ZERO, 0, 0, 1, 0, 0);
        assertEquals(1, left.x, 1e-5);
        // looking straight up, forward is +Y
        assertEquals(1, CuePositions.local(Vec3.ZERO, 0, -90, 0, 0, 1).y, 1e-5);
    }

    @Test
    void anchoredEyesAppliesToTheNextLocalOffset() {
        JsonArray ops = new JsonArray();
        JsonObject eyes = new JsonObject();
        eyes.addProperty("anchor", "eyes");
        ops.add(eyes);
        JsonObject local = new JsonObject();
        JsonArray v = new JsonArray();
        v.add(0); v.add(0); v.add(2);
        local.add("local", v);
        ops.add(local);
        Vec3 p = CuePositions.resolve(ops, new Vec3(10, 64, 10), 1.62f, 0, 0);
        assertEquals(new Vec3(10, 65.62, 12).distanceTo(p), 0, 1e-4);
    }

    @Test
    void conditions() {
        Set<String> tags = Set.of("limitless", "player");
        assertTrue(CueConditions.test(List.of("tag=limitless", "tag=!curse"), tags, Map.of()));
        assertFalse(CueConditions.test(List.of("tag=sixeyes"), tags, Map.of()));
        assertFalse(CueConditions.test(List.of("type=!player"), tags, Map.of()));
        assertTrue(CueConditions.test(List.of("score:chance=1..50"), tags, Map.of("chance", 20)));
        assertFalse(CueConditions.test(List.of("score:chance=51.."), tags, Map.of("chance", 20)));
        assertFalse(CueConditions.test(List.of("score:Stun=1.."), tags, Map.of()), "stun checks see an unstunned caster");
        assertTrue(CueConditions.test(List.of("score:Stun=0"), tags, Map.of()));
        assertFalse(CueConditions.test(List.of("if:entity"), tags, Map.of()));
        assertTrue(CueConditions.test(List.of("if:entity"), Set.of("if:entity"), Map.of()));
        assertTrue(CueConditions.test(List.of("unless:block"), tags, Map.of()));
    }
}
