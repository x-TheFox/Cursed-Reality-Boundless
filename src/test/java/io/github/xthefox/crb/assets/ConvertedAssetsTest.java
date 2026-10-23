package io.github.xthefox.crb.assets;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the assets produced by {@code tools/jja/convert.py}: every path is a legal resource
 * location, every JSON parses, and every reference in the indexes, models, sounds and particles
 * points at a file that exists.
 */
class ConvertedAssetsTest {
    static final Path RES = Path.of(System.getProperty("crb.projectDir", "."), "src/main/resources");
    static final Path ASSETS = RES.resolve("assets/crb");
    static final Pattern VALID = Pattern.compile("[a-z0-9_.\\-/]+");

    static List<Path> all;

    @BeforeAll
    static void scan() throws IOException {
        try (Stream<Path> s = Files.walk(ASSETS)) {
            all = s.filter(Files::isRegularFile).toList();
        }
    }

    static JsonObject json(Path p) {
        try {
            return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
        } catch (IOException e) {
            throw new AssertionError(p.toString(), e);
        }
    }

    /** {@code crb:geo/x.geo.json} → file; {@code minecraft:*} refs are vanilla and skipped (null). */
    static Path resolve(String ref, String prefix, String suffix) {
        String[] parts = ref.split(":", 2);
        if (!parts[0].equals("crb")) return null;
        return RES.resolve("assets/crb/" + prefix + parts[1] + suffix);
    }

    static void assertExists(List<String> errors, String what, Path p) {
        if (p != null && !Files.isRegularFile(p)) errors.add(what + " -> missing " + RES.relativize(p));
    }

    @Test
    void conversionRan() {
        assertTrue(Files.isRegularFile(ASSETS.resolve("jja/geometry.json")),
                "run `python3 tools/jja/convert.py` to generate assets");
        assertTrue(all.size() > 5000, "expected thousands of converted files, found " + all.size());
    }

    @Test
    void everyPathIsAValidResourceLocation() {
        List<String> bad = all.stream().map(p -> ASSETS.relativize(p).toString().replace('\\', '/'))
                .filter(p -> !VALID.matcher(p).matches()).toList();
        assertTrue(bad.isEmpty(), "invalid resource paths: " + bad.subList(0, Math.min(20, bad.size())));
    }

    @Test
    void everyJsonParses() {
        List<String> bad = new ArrayList<>();
        for (Path p : all) {
            if (!p.toString().endsWith(".json")) continue;
            try {
                JsonParser.parseString(Files.readString(p));
            } catch (Exception e) {
                bad.add(ASSETS.relativize(p) + ": " + e.getMessage());
            }
        }
        assertTrue(bad.isEmpty(), bad.toString());
    }

    @Test
    void indexesPointAtRealFiles() {
        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : json(ASSETS.resolve("jja/geometry.json")).entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            assertExists(errors, "geometry " + e.getKey(), resolve(o.get("file").getAsString(), "", ""));
            if (o.has("poly_mesh")) assertExists(errors, "poly mesh " + e.getKey(), resolve(o.get("poly_mesh").getAsString(), "", ""));
        }
        for (Map.Entry<String, JsonElement> e : json(ASSETS.resolve("jja/animations.json")).entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            if (o.has("file")) assertExists(errors, "animation " + e.getKey(), resolve(o.get("file").getAsString(), "", ""));
        }
        for (Map.Entry<String, JsonElement> e : json(ASSETS.resolve("jja/particles.json")).entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            assertExists(errors, "particle " + e.getKey(), resolve(o.get("file").getAsString(), "", ""));
            if (o.has("texture") && !o.get("texture").isJsonNull())
                assertExists(errors, "particle texture " + e.getKey(), resolve(o.get("texture").getAsString(), "", ""));
        }
        for (Map.Entry<String, JsonElement> e : json(ASSETS.resolve("jja/glyphs.json")).entrySet()) {
            assertExists(errors, "glyph " + e.getKey(), resolve(e.getValue().getAsJsonObject().get("texture").getAsString(), "", ""));
        }
        for (Map.Entry<String, JsonElement> e : json(ASSETS.resolve("jja/entities.json")).entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            o.getAsJsonObject("geometry").entrySet().forEach(g ->
                    assertExists(errors, "entity geo " + e.getKey(), resolve(g.getValue().getAsString(), "", "")));
            o.getAsJsonObject("textures").entrySet().forEach(t ->
                    assertExists(errors, "entity texture " + e.getKey(), resolve(t.getValue().getAsString(), "", "")));
        }
        assertTrue(errors.isEmpty(), errors.subList(0, Math.min(30, errors.size())).toString());
    }

    @Test
    void soundsResolve() {
        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : json(ASSETS.resolve("sounds.json")).entrySet()) {
            for (JsonElement s : e.getValue().getAsJsonObject().getAsJsonArray("sounds")) {
                String name = s.isJsonPrimitive() ? s.getAsString() : s.getAsJsonObject().get("name").getAsString();
                assertExists(errors, "sound " + e.getKey(), resolve(name, "sounds/", ".ogg"));
            }
        }
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void itemModelsHaveTextures() throws IOException {
        List<String> errors = new ArrayList<>();
        try (Stream<Path> s = Files.list(ASSETS.resolve("models/item"))) {
            for (Path p : s.toList()) {
                String layer = json(p).getAsJsonObject("textures").get("layer0").getAsString();
                assertExists(errors, "item model " + p.getFileName(), resolve(layer, "textures/", ".png"));
            }
        }
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void playerAnimationsUsePlayerAnimationLibBones() throws IOException {
        var allowed = java.util.Set.of("head", "body", "torso", "right_arm", "left_arm", "right_leg", "left_leg",
                "right_item", "left_item", "cape");
        try (Stream<Path> s = Files.list(ASSETS.resolve("player_animations"))) {
            for (Path p : s.toList()) {
                for (Map.Entry<String, JsonElement> a : json(p).getAsJsonObject("animations").entrySet()) {
                    for (String bone : a.getValue().getAsJsonObject().getAsJsonObject("bones").keySet()) {
                        assertTrue(allowed.contains(bone), p.getFileName() + " " + a.getKey() + " uses bone " + bone);
                    }
                }
            }
        }
    }
}
