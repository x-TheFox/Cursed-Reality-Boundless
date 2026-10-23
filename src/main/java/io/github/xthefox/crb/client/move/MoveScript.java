package io.github.xthefox.crb.client.move;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** A move's presentation timeline, extracted from JJA by {@code tools/jja/timelines.py}. */
public record MoveScript(String source, int duration, List<Cue> cues) {

    /**
     * One presentation event. {@code t..until} is inclusive in elapsed ticks; most types repeat every
     * tick of the range like the Bedrock command did, animations fire once at {@code t}.
     */
    public record Cue(int t, int until, String type, List<String> cond, List<String> audienceCond, JsonObject data) {
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

        public boolean repeats() {
            return !"anim".equals(type);
        }
    }

    public static MoveScript parse(JsonObject json) {
        List<Cue> cues = new ArrayList<>();
        for (JsonElement el : json.getAsJsonArray("cues")) {
            JsonObject c = el.getAsJsonObject();
            cues.add(new Cue(c.get("t").getAsInt(), c.get("until").getAsInt(), c.get("type").getAsString(),
                    strings(c.getAsJsonArray("cond")), strings(c.getAsJsonArray("audience_cond")), c));
        }
        return new MoveScript(json.get("source").getAsString(), json.get("duration").getAsInt(), List.copyOf(cues));
    }

    private static List<String> strings(JsonArray a) {
        if (a == null) return List.of();
        List<String> out = new ArrayList<>(a.size());
        a.forEach(e -> out.add(e.getAsString()));
        return List.copyOf(out);
    }
}
