package io.github.xthefox.crb.molang;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MolangTest {
    static double eval(String s) {
        return Molang.compileStrict(s).eval(new MolangContext(new SplittableRandom(1)));
    }

    @Test
    void arithmeticAndPrecedence() {
        assertEquals(7, eval("1 + 2 * 3"));
        assertEquals(9, eval("(1 + 2) * 3"));
        assertEquals(-4, eval("-2 * 2"));
        assertEquals(0, eval("5 / 0"), "division by zero is 0 in Molang");
        assertEquals(1, eval("3 > 2 && 1 != 2"));
        assertEquals(2, eval("0 ? 1 : 2"));
        assertEquals(0, eval("0 ? 1"));
        assertEquals(0.5, eval("0.5f"));
    }

    @Test
    void mathInDegrees() {
        assertEquals(1, eval("math.sin(90)"), 1e-9);
        assertEquals(-1, eval("Math.Cos(180)"), 1e-9, "names are case-insensitive");
        assertEquals(8, eval("math.pow(2, 3)"));
        assertEquals(5, eval("math.clamp(9, 0, 5)"));
        assertEquals(2.5, eval("math.lerp(0, 10, 0.25)"));
        double r = eval("math.random(1, -1)");
        assertTrue(r >= -1 && r <= 1);
        double i = eval("math.random_integer(3, 5)");
        assertTrue(i == 3 || i == 4 || i == 5);
    }

    @Test
    void variablesAssignmentsAndReturn() {
        MolangContext ctx = new MolangContext(new SplittableRandom(1));
        ctx.set(MolangContext.PARTICLE_AGE, 0.5);
        ctx.set(MolangContext.PARTICLE_LIFETIME, 2);
        assertEquals(0.25, Molang.compileStrict("v.particle_age / variable.particle_lifetime").eval(ctx));
        assertEquals(6, Molang.compileStrict("t.a = 2; t.b = t.a * 3; return t.b;").eval(ctx));
        assertEquals(0, Molang.compileStrict("v.unset").eval(ctx), "unset variables read as 0");
        assertEquals(4, Molang.compileStrict("v.unset ?? 4").eval(ctx));
        assertEquals(0.5, Molang.compileStrict("v.particle_age ?? 4").eval(ctx));
    }

    @Test
    void lenientRecoversFromJjaTypos() {
        MolangContext ctx = new MolangContext(new SplittableRandom(1));
        double v = Molang.compileLenient("math.random(-0.`1,0.1)").eval(ctx);
        assertTrue(v >= -0.1 && v <= 0.1);
        assertEquals(3, Molang.compileLenient("3  + ( variable.particle_age * 6 2)").eval(ctx));
        assertEquals(0, Molang.compileLenient(")").eval(ctx));
    }

    @Test
    void constantsFold() {
        assertTrue(Molang.compileStrict("2 * 3 + 1").isConstant());
        assertFalse(Molang.compileStrict("math.random(0, 1)").isConstant());
    }

    /** Every Molang string in every converted JJA particle effect compiles. */
    @Test
    void everyJjaParticleExpressionCompiles() throws Exception {
        Path dir = Path.of(System.getProperty("crb.projectDir", "."), "src/main/resources/assets/crb/bedrock_particles");
        List<String> typos = new ArrayList<>();
        int count = 0;
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                JsonElement comps = JsonParser.parseString(Files.readString(p)).getAsJsonObject()
                        .getAsJsonObject("particle_effect").get("components");
                List<String> strings = new ArrayList<>();
                collect(comps, strings);
                for (String s : strings) {
                    if (s.startsWith("#") || s.startsWith("textures/") || s.contains(":") && !s.contains("?")) continue;
                    count++;
                    try {
                        Molang.compileStrict(s);
                    } catch (RuntimeException e) {
                        typos.add(p.getFileName() + ": " + s);
                        Molang.compileLenient(s); // must recover
                    }
                }
            }
        }
        assertTrue(count > 10000, "only " + count + " expressions found");
        // known typos in JJA's own files (backticks, unbalanced parentheses); recovered by the lenient parser
        assertTrue(typos.size() <= 8, typos.size() + " malformed expressions: " + typos);
    }

    static void collect(JsonElement e, List<String> out) {
        if (e == null || e.isJsonNull()) return;
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) out.add(e.getAsString());
        else if (e.isJsonArray()) e.getAsJsonArray().forEach(x -> collect(x, out));
        else if (e.isJsonObject()) e.getAsJsonObject().entrySet().forEach(x -> collect(x.getValue(), out));
    }
}
