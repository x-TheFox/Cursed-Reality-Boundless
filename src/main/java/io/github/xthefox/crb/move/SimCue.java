package io.github.xthefox.crb.move;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * One extracted simulation line ({@code data/crb/move_sim.json}, {@code data/crb/projectiles.json}).
 *
 * @param t          first tick (elapsed for caster moves, remaining Deleter for projectiles)
 * @param until      last tick, inclusive
 * @param cond       conditions on the caster / projectile
 * @param targetCond JJA target selector filters for hits ({@link io.github.xthefox.crb.combat.TargetTags})
 */
public record SimCue(int t, int until, String type, JsonObject data, List<String> cond, List<String> targetCond) {

    public static SimCue parse(JsonObject o, Predicate<String> keepCond) {
        return new SimCue(o.get("t").getAsInt(), o.get("until").getAsInt(), o.get("type").getAsString(), o,
                strings(o.getAsJsonArray("cond"), keepCond), strings(o.getAsJsonArray("audience_cond"), c -> true));
    }

    public static List<SimCue> parseAll(JsonArray arr, Predicate<String> keepCond) {
        List<SimCue> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) out.add(parse(el.getAsJsonObject(), keepCond));
        return List.copyOf(out);
    }

    public String str(String key, String fallback) {
        JsonElement e = data.get(key);
        return e == null || e.isJsonNull() ? fallback : e.getAsString();
    }

    public double num(String key, double fallback) {
        JsonElement e = data.get(key);
        return e == null || e.isJsonNull() ? fallback : e.getAsDouble();
    }

    public JsonArray array(String key) {
        JsonElement e = data.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    private static List<String> strings(JsonArray a, Predicate<String> keep) {
        if (a == null) return List.of();
        List<String> out = new ArrayList<>(a.size());
        a.forEach(e -> {
            String s = e.getAsString();
            if (keep.test(s)) out.add(s);
        });
        return List.copyOf(out);
    }
}
