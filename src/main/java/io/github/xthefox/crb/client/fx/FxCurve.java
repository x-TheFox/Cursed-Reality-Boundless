package io.github.xthefox.crb.client.fx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.xthefox.crb.molang.Molang;
import io.github.xthefox.crb.molang.MolangContext;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * Bedrock particle curves ({@code curves."variable.x"}): evaluated before particle expressions and
 * written into their variable. Supports linear, catmull_rom, bezier and bezier_chain.
 */
public final class FxCurve {
    enum Type { LINEAR, CATMULL_ROM, BEZIER, BEZIER_CHAIN }

    final int slot;
    final Type type;
    final Molang.Expr input, range;
    final float[] values;
    // bezier_chain keys
    final float[] keyTimes, leftValues, rightValues, leftSlopes, rightSlopes;

    private FxCurve(int slot, Type type, Molang.Expr input, Molang.Expr range, float[] values,
                    float[] keyTimes, float[] lv, float[] rv, float[] ls, float[] rs) {
        this.slot = slot;
        this.type = type;
        this.input = input;
        this.range = range;
        this.values = values;
        this.keyTimes = keyTimes;
        this.leftValues = lv;
        this.rightValues = rv;
        this.leftSlopes = ls;
        this.rightSlopes = rs;
    }

    static FxCurve parse(String name, JsonObject o) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.startsWith("v.")) n = "variable." + n.substring(2);
        int slot = MolangContext.slot(n);
        String t = o.has("type") ? o.get("type").getAsString() : "linear";
        Type type = switch (t) {
            case "catmull_rom" -> Type.CATMULL_ROM;
            case "bezier" -> Type.BEZIER;
            case "bezier_chain" -> Type.BEZIER_CHAIN;
            default -> Type.LINEAR;
        };
        Molang.Expr input = FxDef.expr(o.get("input"), Molang.ZERO);
        Molang.Expr range = FxDef.expr(o.get("horizontal_range"), Molang.ONE);
        JsonElement nodes = o.get("nodes");
        if (type == Type.BEZIER_CHAIN && nodes != null && nodes.isJsonObject()) {
            List<float[]> keys = new ArrayList<>();
            for (var e : nodes.getAsJsonObject().entrySet()) {
                JsonObject k = e.getValue().getAsJsonObject();
                float value = k.has("value") ? k.get("value").getAsFloat() : 0;
                float slope = k.has("slope") ? k.get("slope").getAsFloat() : 0;
                keys.add(new float[]{Float.parseFloat(e.getKey()),
                        k.has("left_value") ? k.get("left_value").getAsFloat() : value,
                        k.has("right_value") ? k.get("right_value").getAsFloat() : value,
                        k.has("left_slope") ? k.get("left_slope").getAsFloat() : slope,
                        k.has("right_slope") ? k.get("right_slope").getAsFloat() : slope});
            }
            keys.sort((a, b) -> Float.compare(a[0], b[0]));
            int k = keys.size();
            float[] kt = new float[k], lv = new float[k], rv = new float[k], ls = new float[k], rs = new float[k];
            for (int i = 0; i < k; i++) {
                float[] x = keys.get(i);
                kt[i] = x[0]; lv[i] = x[1]; rv[i] = x[2]; ls[i] = x[3]; rs[i] = x[4];
            }
            return new FxCurve(slot, type, input, range, new float[0], kt, lv, rv, ls, rs);
        }
        float[] vals = new float[0];
        if (nodes != null && nodes.isJsonArray()) {
            JsonArray a = nodes.getAsJsonArray();
            vals = new float[a.size()];
            for (int i = 0; i < a.size(); i++) vals[i] = a.get(i).isJsonPrimitive() && a.get(i).getAsJsonPrimitive().isNumber() ? a.get(i).getAsFloat() : 0;
        }
        return new FxCurve(slot, type, input, range, vals, null, null, null, null, null);
    }

    void apply(MolangContext ctx) {
        ctx.set(slot, eval(ctx));
    }

    double eval(MolangContext ctx) {
        double r = range.eval(ctx);
        double x = input.eval(ctx);
        double t = r == 0 ? x : x / r;
        return switch (type) {
            case LINEAR -> linear(t);
            case CATMULL_ROM -> catmull(t);
            case BEZIER -> bezier(t);
            case BEZIER_CHAIN -> chain(x);
        };
    }

    private double linear(double t) {
        if (values.length == 0) return 0;
        if (values.length == 1) return values[0];
        double p = Mth.clamp(t, 0, 1) * (values.length - 1);
        int i = Math.min((int) p, values.length - 2);
        return Mth.lerp(p - i, values[i], values[i + 1]);
    }

    private double catmull(double t) {
        // first and last nodes are control points only
        if (values.length < 4) return linear(t);
        int segments = values.length - 3;
        double p = Mth.clamp(t, 0, 1) * segments;
        int i = Math.min((int) p, segments - 1);
        double u = p - i;
        double p0 = values[i], p1 = values[i + 1], p2 = values[i + 2], p3 = values[i + 3];
        return 0.5 * ((2 * p1) + (-p0 + p2) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u * u + (-p0 + 3 * p1 - 3 * p2 + p3) * u * u * u);
    }

    private double bezier(double t) {
        if (values.length < 4) return linear(t);
        double u = Mth.clamp(t, 0, 1), m = 1 - u;
        return m * m * m * values[0] + 3 * m * m * u * values[1] + 3 * m * u * u * values[2] + u * u * u * values[3];
    }

    /** Cubic Hermite between keys, using each key's right/left value and slope (Bedrock bezier_chain). */
    private double chain(double x) {
        int n = keyTimes.length;
        if (n == 0) return 0;
        if (x <= keyTimes[0]) return leftValues[0];
        if (x >= keyTimes[n - 1]) return rightValues[n - 1];
        int i = 0;
        while (i < n - 2 && x > keyTimes[i + 1]) i++;
        double t0 = keyTimes[i], t1 = keyTimes[i + 1], h = t1 - t0;
        double u = h == 0 ? 0 : (x - t0) / h;
        double p0 = rightValues[i], p1 = leftValues[i + 1];
        double m0 = rightSlopes[i] * h, m1 = leftSlopes[i + 1] * h;
        double u2 = u * u, u3 = u2 * u;
        return (2 * u3 - 3 * u2 + 1) * p0 + (u3 - 2 * u2 + u) * m0 + (-2 * u3 + 3 * u2) * p1 + (u3 - u2) * m1;
    }
}
