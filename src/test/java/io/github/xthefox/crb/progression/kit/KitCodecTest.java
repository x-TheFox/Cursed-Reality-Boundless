package io.github.xthefox.crb.progression.kit;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.xthefox.crb.progression.Grade;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class KitCodecTest {
    static final Path KITS = Path.of(System.getProperty("crb.projectDir", "."), "src/main/resources/data/crb/crb_kits");

    static Kit parse(Path p) throws IOException {
        return Kit.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(Files.readString(p)))
                .getOrThrow(err -> new AssertionError(p + ": " + err));
    }

    @Test
    void everyShippedKitParses() throws IOException {
        try (Stream<Path> files = Files.list(KITS)) {
            long n = files.filter(f -> f.toString().endsWith(".json")).peek(f -> {
                try { assertNotNull(parse(f).display()); }
                catch (IOException e) { throw new RuntimeException(e); }
            }).count();
            assertTrue(n >= 19);
        }
    }

    @Test
    void gojoHasLimitlessAndSixEyes() throws IOException {
        Kit gojo = parse(KITS.resolve("gojo.json"));
        ResourceLocation sixEyes = ResourceLocation.fromNamespaceAndPath("crb", "six_eyes");
        assertEquals(ResourceLocation.fromNamespaceAndPath("crb", "limitless"), gojo.technique().orElseThrow());
        assertEquals(Kit.MAX_FORMS, gojo.forms());
        assertTrue(gojo.traits().contains(sixEyes));
        assertTrue(gojo.claims().contains(sixEyes));
        assertEquals(Grade.SPECIAL, gojo.grade().orElseThrow());
    }

    @Test
    void yujiStartsAsVesselWithOneFinger() throws IOException {
        Kit yuji = parse(KITS.resolve("yuji.json"));
        assertEquals(1, yuji.fingers().orElseThrow());
    }

    @Test
    void sukunaHasTenShadowsFromMeguna() throws IOException {
        Kit sukuna = parse(KITS.resolve("sukuna.json"));
        assertTrue(sukuna.claims().contains(ResourceLocation.fromNamespaceAndPath("crb", "ten_shadows")));
        assertTrue(sukuna.traits().contains(ResourceLocation.fromNamespaceAndPath("crb", "meguna")));
    }
}
