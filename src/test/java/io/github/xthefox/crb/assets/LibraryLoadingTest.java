package io.github.xthefox.crb.assets;

import com.zigythebird.playeranimcore.loading.UniversalAnimLoader;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.bernie.geckolib.loading.FileLoader;
import software.bernie.geckolib.loading.object.BakedModelFactory;
import software.bernie.geckolib.loading.object.GeometryTree;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs every converted model and animation through the same loaders the client uses:
 * GeckoLib (parse + bake geometry, parse animations) and Player Animation Library.
 */
class LibraryLoadingTest {
    static final Path RES = Path.of(System.getProperty("crb.projectDir", "."), "src/main/resources");
    static ResourceManager resources;

    @BeforeAll
    static void setUp() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var info = new PackLocationInfo("crb_test", Component.literal("crb"), PackSource.BUILT_IN, Optional.empty());
        resources = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(new PathPackResources(info, RES)));
    }

    static List<Path> list(String dir, String suffix) throws Exception {
        try (Stream<Path> s = Files.list(RES.resolve("assets/crb/" + dir))) {
            return s.filter(p -> p.toString().endsWith(suffix)).sorted().toList();
        }
    }

    static String summary(List<String> failures, int total) {
        return failures.size() + "/" + total + " failed: " + failures.subList(0, Math.min(15, failures.size()));
    }

    @Test
    void geckoLibParsesAndBakesEveryModel() throws Exception {
        List<String> failures = new ArrayList<>();
        List<Path> geos = list("geo", ".geo.json");
        for (Path p : geos) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("crb", "geo/" + p.getFileName());
            try {
                var model = FileLoader.loadModelFile(id, resources);
                BakedModelFactory.getForNamespace("crb").constructGeoModel(GeometryTree.fromModel(model));
            } catch (Exception e) {
                failures.add(p.getFileName() + ": " + e);
            }
        }
        assertTrue(failures.isEmpty(), summary(failures, geos.size()));
    }

    @Test
    void geckoLibParsesEveryAnimationFile() throws Exception {
        List<String> failures = new ArrayList<>();
        List<Path> files = list("animations", ".animation.json");
        for (Path p : files) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("crb", "animations/" + p.getFileName());
            try {
                // GeckoLib logs and drops animations it cannot parse, so compare counts
                int declared = com.google.gson.JsonParser.parseString(Files.readString(p)).getAsJsonObject()
                        .getAsJsonObject("animations").size();
                int baked = FileLoader.loadAnimationsFile(id, resources).animations().size();
                if (baked != declared) failures.add(p.getFileName() + ": " + baked + "/" + declared + " animations parsed");
            } catch (Exception e) {
                failures.add(p.getFileName() + ": " + e);
            }
        }
        assertTrue(failures.isEmpty(), summary(failures, files.size()));
    }

    @Test
    void playerAnimationLibraryParsesEveryPlayerAnimation() throws Exception {
        List<String> failures = new ArrayList<>();
        List<Path> files = list("player_animations", ".json");
        int animations = 0;
        for (Path p : files) {
            try (InputStream in = Files.newInputStream(p)) {
                animations += UniversalAnimLoader.loadAnimations(in).size();
            } catch (Exception e) {
                failures.add(p.getFileName() + ": " + e);
            }
        }
        assertTrue(failures.isEmpty(), summary(failures, files.size()));
        assertTrue(animations > 1000, "only " + animations + " player animations loaded");
    }
}
