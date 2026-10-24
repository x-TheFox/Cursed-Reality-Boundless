package io.github.xthefox.crb.client.move;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A move's presentation timeline, extracted from JJA by {@code tools/jja/timelines.py}. Cues are
 * keyed on JJA's countdown value ({@code move=lo..hi}), in the function's line order, and the
 * script may contain unconditional {@code counter} cues (holds, loops) that the player follows
 * locally; conditional jumps come from the server.
 *
 * @param start the countdown's first value (the move's length when nothing jumps)
 */
public record MoveScript(String source, int start, List<Cue> cues) {

    /**
     * One presentation event, running while the countdown is in {@code lo..hi}. Most types repeat
     * every tick of the range like the Bedrock command did; animations fire once when the
     * countdown enters the range.
     */
    public record Cue(int lo, int hi, String type, List<String> cond, List<String> audienceCond, JsonObject data) {
        public String str(String key, String fallback) {
            JsonElement e = data.get(key);
            return e == null || e.isJsonNull() ? fallback : e.getAsString();
        }

        public float num(String key, float fallback) {
            JsonElement e = data.get(key);
            return e == null || e.isJsonNull() ? fallback : e.getAsFloat();
        }

        public JsonArray array(String key) {
            JsonElement e = data.get(key);
            return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
        }

        public JsonObject object(String key) {
            JsonElement e = data.get(key);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        }

        public boolean active(int v) {
            return v >= lo && v <= hi;
        }

        public boolean repeats() {
            return !"anim".equals(type);
        }
    }

    public static MoveScript parse(JsonObject json) {
        List<Cue> cues = new ArrayList<>();
        for (JsonElement el : json.getAsJsonArray("cues")) {
            JsonObject c = el.getAsJsonObject();
            cues.add(new Cue(c.get("lo").getAsInt(), c.get("hi").getAsInt(), c.get("type").getAsString(),
                    strings(c.getAsJsonArray("cond")), strings(c.getAsJsonArray("audience_cond")), c));
        }
        int start = json.has("start") ? json.get("start").getAsInt() : 1;
        return new MoveScript(json.get("source").getAsString(), start, List.copyOf(cues));
    }

    private static List<String> strings(JsonArray a) {
        if (a == null) return List.of();
        List<String> out = new ArrayList<>(a.size());
        a.forEach(e -> out.add(e.getAsString()));
        return List.copyOf(out);
    }
}
