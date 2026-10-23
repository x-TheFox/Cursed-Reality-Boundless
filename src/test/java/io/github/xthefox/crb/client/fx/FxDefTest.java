package io.github.xthefox.crb.client.fx;

import com.google.gson.JsonParser;
import io.github.xthefox.crb.molang.MolangContext;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FxDefTest {
    static final Path DIR = Path.of(System.getProperty("crb.projectDir", "."), "src/main/resources/assets/crb/bedrock_particles");

    @Test
    void everyJjaEffectParses() throws Exception {
        List<String> failures = new ArrayList<>();
        int n = 0, gradients = 0, flipbooks = 0;
        try (Stream<Path> s = Files.walk(DIR)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".json")).toList()) {
                try {
                    FxDef d = FxDef.parse(JsonParser.parseString(Files.readString(p)).getAsJsonObject(), ResourceLocation::tryParse);
                    n++;
                    if (d.gradientKeys != null) gradients++;
                    if (d.flipbook) flipbooks++;
                    // every expression evaluates without throwing
                    MolangContext ctx = new MolangContext(new SplittableRandom(3));
                    d.maxLifetime.eval(ctx);
                    d.size[0].eval(ctx);
                    d.initialSpeed.eval(ctx);
                    d.numParticles.eval(ctx);
                    for (FxCurve c : d.curves) c.apply(ctx);
                } catch (RuntimeException e) {
                    failures.add(p.getFileName() + ": " + e);
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " failed: " + failures.subList(0, Math.min(10, failures.size())));
        assertTrue(n > 3400, "parsed " + n);
        assertTrue(gradients > 3000 && flipbooks > 500, gradients + " gradients, " + flipbooks + " flipbooks");
    }

    @Test
    void bedrockHexColorsAreArgb() {
        assertEquals(0xFF00FF00, FxDef.hex("#00FF00"));
        assertEquals(0x80FF0000, FxDef.hex("#80FF0000"));
        assertEquals(0xFF7F7F7F, FxParticle.lerpColor(0xFF000000, 0xFFFFFFFF, 0.5f));
    }

    @Test
    void curvesEvaluate() {
        var json = JsonParser.parseString("{\"type\":\"linear\",\"input\":\"v.particle_age\",\"horizontal_range\":1,\"nodes\":[0,10]}").getAsJsonObject();
        FxCurve c = FxCurve.parse("variable.size", json);
        MolangContext ctx = new MolangContext(new SplittableRandom(1));
        ctx.set(MolangContext.PARTICLE_AGE, 0.25);
        assertEquals(2.5, c.eval(ctx), 1e-9);
        var chain = JsonParser.parseString("{\"type\":\"bezier_chain\",\"input\":\"v.particle_age\",\"nodes\":{\"0\":{\"value\":0,\"slope\":0},\"1\":{\"value\":1,\"slope\":0}}}").getAsJsonObject();
        FxCurve b = FxCurve.parse("variable.c", chain);
        ctx.set(MolangContext.PARTICLE_AGE, 0.5);
        assertEquals(0.5, b.eval(ctx), 1e-9, "smoothstep midpoint");
    }
}
