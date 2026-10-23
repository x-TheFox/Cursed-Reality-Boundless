package io.github.xthefox.crb.client.fx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.xthefox.crb.molang.Molang;
import io.github.xthefox.crb.molang.Molang.Expr;
import io.github.xthefox.crb.molang.MolangContext;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * A compiled Bedrock (Snowstorm) particle effect: every component JJA uses, with all Molang
 * precompiled. Field names follow the Bedrock component names.
 */
public final class FxDef {
    public enum Material { ADD, BLEND, ALPHA }
    public enum Shape { POINT, SPHERE, BOX, DISC }
    public enum Direction { OUTWARDS, INWARDS, VECTOR }
    public enum Facing { ROTATE_XYZ, ROTATE_Y, LOOKAT_XYZ, LOOKAT_Y, LOOKAT_DIRECTION, DIRECTION_X, DIRECTION_Y, DIRECTION_Z,
        EMITTER_TRANSFORM_XY, EMITTER_TRANSFORM_XZ, EMITTER_TRANSFORM_YZ }

    public String id;
    public ResourceLocation texture;
    public Material material = Material.BLEND;
    public final List<FxCurve> curves = new ArrayList<>();
    public final Map<String, List<EventAction>> events = new HashMap<>();

    // ---- emitter
    public Expr emitterCreation = Molang.ZERO, emitterUpdate = Molang.ZERO;
    public boolean localPosition, localRotation, localVelocity;
    public boolean rateInstant = true;
    public Expr numParticles = Molang.constant(10), spawnRate = Molang.constant(1), maxParticles = Molang.constant(50);
    public boolean looping;
    public Expr activeTime = Molang.constant(10), sleepTime = Molang.ZERO;
    public Expr activation, expiration;
    public Shape shape = Shape.POINT;
    public Expr[] shapeOffset = zeros();
    public Expr radius = Molang.ONE;
    public Expr[] halfDimensions = zeros();
    public Expr[] planeNormal = {Molang.ZERO, Molang.ONE, Molang.ZERO};
    public boolean surfaceOnly;
    public Direction direction = Direction.OUTWARDS;
    public Expr[] directionVector = zeros();
    public List<String> emitterCreationEvents = List.of(), emitterExpirationEvents = List.of();

    // ---- particle lifetime and motion
    public Expr maxLifetime = Molang.ONE, expirationExpr;
    public Expr initialRotation = Molang.ZERO, rotationRate = Molang.ZERO;
    public Expr initialSpeed = Molang.ZERO;
    public Expr[] initialSpeedVector;
    public Expr[] acceleration = zeros();
    public Expr linearDrag = Molang.ZERO, rotationAcceleration = Molang.ZERO, rotationDrag = Molang.ZERO;
    public Expr[] parametricPosition, parametricDirection;
    public Expr parametricRotation;
    public boolean collision;
    public Expr collisionEnabled = Molang.ONE;
    public float collisionDrag, restitution, collisionRadius = 0.1f;
    public boolean expireOnContact;
    public List<String> expireIfNotIn = List.of(), expireIfIn = List.of();

    // ---- appearance
    public Expr[] size = {Molang.constant(0.1), Molang.constant(0.1)};
    public Facing facing = Facing.ROTATE_XYZ;
    public boolean customDirection;
    public Expr[] customDirectionVector;
    public float minSpeedThreshold = 0.01f;
    public float textureWidth = 1, textureHeight = 1;
    public Expr[] uv, uvSize;
    public boolean flipbook;
    public Expr[] flipBase, flipSize;
    public float[] flipStep = {0, 0};
    public float framesPerSecond = 8;
    public Expr maxFrame = Molang.ONE;
    public boolean stretchToLifetime, loopFlipbook;
    public Expr[] color;
    public Expr gradientInterpolant;
    public float[] gradientKeys;
    public int[] gradientColors;
    public boolean lit;
    public List<String> particleCreationEvents = List.of(), particleExpirationEvents = List.of();
    public TreeMap<Float, List<String>> particleTimeline = new TreeMap<>();

    /** One effect spawned by an event (Bedrock {@code events.<name>.particle_effect}). */
    public record EventAction(String effect, boolean boundToEmitter, Expr preEffect) {}

    // ------------------------------------------------------------------------------------ parsing

    public static FxDef parse(JsonObject root, java.util.function.Function<String, ResourceLocation> textureResolver) {
        JsonObject pe = root.getAsJsonObject("particle_effect");
        FxDef d = new FxDef();
        JsonObject desc = pe.getAsJsonObject("description");
        d.id = desc.get("identifier").getAsString().toLowerCase(Locale.ROOT);
        JsonObject brp = obj(desc, "basic_render_parameters");
        if (brp != null) {
            String tex = str(brp, "texture");
            if (tex != null) d.texture = textureResolver.apply(tex);
            String mat = str(brp, "material");
            if (mat != null) {
                if (mat.equals("particles_add") || mat.contains("additive")) d.material = Material.ADD;
                else if (mat.equals("particles_alpha") || mat.equals("particles_blend_opaque")) d.material = Material.ALPHA;
            }
        }
        JsonObject curves = obj(pe, "curves");
        if (curves != null) curves.entrySet().forEach(e -> d.curves.add(FxCurve.parse(e.getKey(), e.getValue().getAsJsonObject())));
        JsonObject events = obj(pe, "events");
        if (events != null) events.entrySet().forEach(e -> d.events.put(e.getKey(), parseEvent(e.getValue())));

        JsonObject c = obj(pe, "components");
        if (c == null) return d;

        JsonObject o;
        if ((o = obj(c, "minecraft:emitter_initialization")) != null) {
            d.emitterCreation = expr(o.get("creation_expression"), Molang.ZERO);
            d.emitterUpdate = expr(o.get("per_update_expression"), Molang.ZERO);
        }
        if ((o = obj(c, "minecraft:emitter_local_space")) != null) {
            d.localPosition = bool(o, "position");
            d.localRotation = bool(o, "rotation");
            d.localVelocity = bool(o, "velocity");
        }
        if ((o = obj(c, "minecraft:emitter_rate_instant")) != null) {
            d.rateInstant = true;
            d.numParticles = expr(o.get("num_particles"), Molang.constant(10));
        } else if ((o = obj(c, "minecraft:emitter_rate_steady")) != null) {
            d.rateInstant = false;
            d.spawnRate = expr(o.get("spawn_rate"), Molang.ONE);
            d.maxParticles = expr(o.get("max_particles"), Molang.constant(50));
        }
        if ((o = obj(c, "minecraft:emitter_lifetime_once")) != null) {
            d.activeTime = expr(o.get("active_time"), Molang.constant(10));
        } else if ((o = obj(c, "minecraft:emitter_lifetime_looping")) != null) {
            d.looping = true;
            d.activeTime = expr(o.get("active_time"), Molang.constant(10));
            d.sleepTime = expr(o.get("sleep_time"), Molang.ZERO);
        } else if ((o = obj(c, "minecraft:emitter_lifetime_expression")) != null) {
            d.activation = expr(o.get("activation_expression"), Molang.ONE);
            d.expiration = expr(o.get("expiration_expression"), Molang.ZERO);
        }
        if ((o = obj(c, "minecraft:emitter_lifetime_events")) != null) {
            d.emitterCreationEvents = strings(o.get("creation_event"));
            d.emitterExpirationEvents = strings(o.get("expiration_event"));
        }
        for (Shape s : Shape.values()) {
            o = obj(c, "minecraft:emitter_shape_" + s.name().toLowerCase(Locale.ROOT));
            if (o == null) continue;
            d.shape = s;
            d.shapeOffset = vec3(o.get("offset"));
            d.radius = expr(o.get("radius"), Molang.ONE);
            d.halfDimensions = vec3(o.get("half_dimensions"));
            d.surfaceOnly = bool(o, "surface_only");
            JsonElement pn = o.get("plane_normal");
            if (pn != null) {
                if (pn.isJsonPrimitive() && pn.getAsJsonPrimitive().isString() && pn.getAsString().length() == 1) {
                    String axis = pn.getAsString();
                    d.planeNormal = new Expr[]{axis.equals("x") ? Molang.ONE : Molang.ZERO, axis.equals("y") ? Molang.ONE : Molang.ZERO,
                            axis.equals("z") ? Molang.ONE : Molang.ZERO};
                } else {
                    d.planeNormal = vec3(pn);
                }
            }
            JsonElement dir = o.get("direction");
            if (dir == null || (dir.isJsonPrimitive() && dir.getAsString().equals("outwards"))) d.direction = Direction.OUTWARDS;
            else if (dir.isJsonPrimitive() && dir.getAsString().equals("inwards")) d.direction = Direction.INWARDS;
            else { d.direction = Direction.VECTOR; d.directionVector = vec3(dir); }
            break;
        }

        if ((o = obj(c, "minecraft:particle_lifetime_expression")) != null) {
            d.maxLifetime = expr(o.get("max_lifetime"), Molang.ONE);
            if (o.has("expiration_expression")) d.expirationExpr = expr(o.get("expiration_expression"), Molang.ZERO);
        }
        if ((o = obj(c, "minecraft:particle_initial_spin")) != null) {
            d.initialRotation = expr(o.get("rotation"), Molang.ZERO);
            d.rotationRate = expr(o.get("rotation_rate"), Molang.ZERO);
        }
        JsonElement speed = c.get("minecraft:particle_initial_speed");
        if (speed != null) {
            if (speed.isJsonArray()) d.initialSpeedVector = vec3(speed);
            else d.initialSpeed = expr(speed, Molang.ZERO);
        }
        if ((o = obj(c, "minecraft:particle_motion_dynamic")) != null) {
            d.acceleration = vec3(o.get("linear_acceleration"));
            d.linearDrag = expr(o.get("linear_drag_coefficient"), Molang.ZERO);
            d.rotationAcceleration = expr(o.get("rotation_acceleration"), Molang.ZERO);
            d.rotationDrag = expr(o.get("rotation_drag_coefficient"), Molang.ZERO);
        }
        if ((o = obj(c, "minecraft:particle_motion_parametric")) != null) {
            if (o.has("relative_position")) d.parametricPosition = vec3(o.get("relative_position"));
            if (o.has("direction")) d.parametricDirection = vec3(o.get("direction"));
            if (o.has("rotation")) d.parametricRotation = expr(o.get("rotation"), Molang.ZERO);
        }
        if ((o = obj(c, "minecraft:particle_motion_collision")) != null) {
            d.collision = true;
            d.collisionEnabled = expr(o.get("enabled"), Molang.ONE);
            d.collisionDrag = num(o, "collision_drag", 0);
            d.restitution = num(o, "coefficient_of_restitution", 0);
            d.collisionRadius = num(o, "collision_radius", 0.1f);
            d.expireOnContact = bool(o, "expire_on_contact");
        }
        JsonElement notIn = c.get("minecraft:particle_expire_if_not_in_blocks");
        if (notIn != null) d.expireIfNotIn = strings(notIn);
        JsonElement in = c.get("minecraft:particle_expire_if_in_blocks");
        if (in != null) d.expireIfIn = strings(in);

        if ((o = obj(c, "minecraft:particle_appearance_billboard")) != null) {
            JsonElement sz = o.get("size");
            if (sz != null && sz.isJsonArray()) d.size = new Expr[]{expr(sz.getAsJsonArray().get(0), Molang.constant(0.1)),
                    expr(sz.getAsJsonArray().get(1), Molang.constant(0.1))};
            String mode = str(o, "facing_camera_mode");
            if (mode != null) {
                try { d.facing = Facing.valueOf(mode.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ignored) { }
            }
            JsonObject dir = obj(o, "direction");
            if (dir != null) {
                d.customDirection = "custom".equals(str(dir, "mode"));
                if (d.customDirection) d.customDirectionVector = vec3(dir.get("custom_direction"));
                d.minSpeedThreshold = num(dir, "min_speed_threshold", 0.01f);
            }
            JsonObject uv = obj(o, "uv");
            if (uv != null) {
                d.textureWidth = num(uv, "texture_width", 1);
                d.textureHeight = num(uv, "texture_height", 1);
                JsonObject fb = obj(uv, "flipbook");
                if (fb != null) {
                    d.flipbook = true;
                    d.flipBase = vec2(fb.get("base_UV"));
                    d.flipSize = vec2(fb.get("size_UV"));
                    JsonArray step = fb.has("step_UV") && fb.get("step_UV").isJsonArray() ? fb.getAsJsonArray("step_UV") : null;
                    if (step != null) d.flipStep = new float[]{step.get(0).getAsFloat(), step.get(1).getAsFloat()};
                    d.framesPerSecond = num(fb, "frames_per_second", 8);
                    d.maxFrame = expr(fb.get("max_frame"), Molang.ONE);
                    d.stretchToLifetime = bool(fb, "stretch_to_lifetime");
                    d.loopFlipbook = bool(fb, "loop");
                } else {
                    d.uv = vec2(uv.get("uv"));
                    d.uvSize = vec2(uv.get("uv_size"));
                }
            }
        }
        if ((o = obj(c, "minecraft:particle_appearance_tinting")) != null) {
            JsonElement col = o.get("color");
            if (col != null && col.isJsonArray()) {
                JsonArray a = col.getAsJsonArray();
                d.color = new Expr[]{expr(a.get(0), Molang.ONE), expr(a.get(1), Molang.ONE), expr(a.get(2), Molang.ONE),
                        a.size() > 3 ? expr(a.get(3), Molang.ONE) : Molang.ONE};
            } else if (col != null && col.isJsonObject()) {
                JsonObject g = col.getAsJsonObject();
                d.gradientInterpolant = expr(g.get("interpolant"), Molang.ZERO);
                parseGradient(d, g.get("gradient"));
            } else if (col != null && col.isJsonPrimitive()) {
                int argb = hex(col.getAsString());
                d.gradientInterpolant = Molang.ZERO;
                d.gradientKeys = new float[]{0};
                d.gradientColors = new int[]{argb};
            }
        }
        d.lit = c.has("minecraft:particle_appearance_lighting");
        if ((o = obj(c, "minecraft:particle_lifetime_events")) != null) {
            d.particleCreationEvents = strings(o.get("creation_event"));
            d.particleExpirationEvents = strings(o.get("expiration_event"));
            JsonObject tl = obj(o, "timeline");
            if (tl != null) tl.entrySet().forEach(e -> {
                try { d.particleTimeline.put(Float.parseFloat(e.getKey()), strings(e.getValue())); } catch (NumberFormatException ignored) { }
            });
        }
        return d;
    }

    private record Stop(float key, int argb) {}

    private static void parseGradient(FxDef d, JsonElement grad) {
        if (grad == null) return;
        List<Stop> stops = new ArrayList<>();
        if (grad.isJsonObject()) {
            for (var e : grad.getAsJsonObject().entrySet()) {
                try { stops.add(new Stop(Float.parseFloat(e.getKey()), color(e.getValue()))); }
                catch (NumberFormatException ignored) { }
            }
        } else if (grad.isJsonArray()) {
            JsonArray a = grad.getAsJsonArray();
            for (int i = 0; i < a.size(); i++) stops.add(new Stop(a.size() == 1 ? 0 : i / (float) (a.size() - 1), color(a.get(i))));
        }
        stops.sort((x, y) -> Float.compare(x.key(), y.key()));
        d.gradientKeys = new float[stops.size()];
        d.gradientColors = new int[stops.size()];
        for (int i = 0; i < stops.size(); i++) {
            d.gradientKeys[i] = stops.get(i).key();
            d.gradientColors[i] = stops.get(i).argb();
        }
    }

    /** "#AARRGGBB", "#RRGGBB" or [r, g, b, a] (0..1) → ARGB int. */
    static int color(JsonElement e) {
        if (e.isJsonPrimitive()) return hex(e.getAsString());
        JsonArray a = e.getAsJsonArray();
        float r = a.get(0).getAsFloat(), g = a.get(1).getAsFloat(), b = a.get(2).getAsFloat(), al = a.size() > 3 ? a.get(3).getAsFloat() : 1;
        return ((int) (al * 255) << 24) | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }

    static int hex(String s) {
        String h = s.startsWith("#") ? s.substring(1) : s;
        try {
            long v = Long.parseLong(h, 16);
            return h.length() <= 6 ? (int) (0xFF000000L | v) : (int) v;
        } catch (NumberFormatException e) {
            return 0xFFFFFFFF;
        }
    }

    private static List<EventAction> parseEvent(JsonElement e) {
        List<EventAction> out = new ArrayList<>();
        if (e == null || !e.isJsonObject()) return out;
        JsonObject o = e.getAsJsonObject();
        JsonObject pe = obj(o, "particle_effect");
        if (pe != null && pe.has("effect")) {
            String type = str(pe, "type");
            out.add(new EventAction(pe.get("effect").getAsString().toLowerCase(Locale.ROOT), "emitter_bound".equals(type),
                    expr(pe.get("pre_effect_expression"), Molang.ZERO)));
        }
        for (String key : new String[]{"sequence", "randomize"}) {
            JsonElement list = o.get(key);
            if (list != null && list.isJsonArray()) list.getAsJsonArray().forEach(x -> out.addAll(parseEvent(x)));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------ helpers

    static Expr[] zeros() {
        return new Expr[]{Molang.ZERO, Molang.ZERO, Molang.ZERO};
    }

    static Expr expr(JsonElement e, Expr fallback) {
        if (e == null || e.isJsonNull()) return fallback;
        if (e.isJsonPrimitive()) {
            if (e.getAsJsonPrimitive().isNumber()) return Molang.constant(e.getAsDouble());
            if (e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean() ? Molang.ONE : Molang.ZERO;
            return Molang.compile(e.getAsString());
        }
        return fallback;
    }

    static Expr[] vec3(JsonElement e) {
        if (e == null || !e.isJsonArray()) return zeros();
        JsonArray a = e.getAsJsonArray();
        return new Expr[]{expr(a.size() > 0 ? a.get(0) : null, Molang.ZERO), expr(a.size() > 1 ? a.get(1) : null, Molang.ZERO),
                expr(a.size() > 2 ? a.get(2) : null, Molang.ZERO)};
    }

    static Expr[] vec2(JsonElement e) {
        if (e == null || !e.isJsonArray()) return new Expr[]{Molang.ZERO, Molang.ZERO};
        JsonArray a = e.getAsJsonArray();
        return new Expr[]{expr(a.size() > 0 ? a.get(0) : null, Molang.ZERO), expr(a.size() > 1 ? a.get(1) : null, Molang.ZERO)};
    }

    static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && (e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : e.getAsDouble() != 0);
    }

    static float num(JsonObject o, String key, float fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return fallback;
        try { return e.getAsFloat(); } catch (NumberFormatException ex) { return fallback; }
    }

    static List<String> strings(JsonElement e) {
        if (e == null || e.isJsonNull()) return List.of();
        if (e.isJsonPrimitive()) return List.of(e.getAsString());
        List<String> out = new ArrayList<>();
        e.getAsJsonArray().forEach(x -> out.add(x.getAsString()));
        return out;
    }

    /** Evaluates a 3-vector. */
    static double[] eval3(Expr[] v, MolangContext ctx) {
        return new double[]{v[0].eval(ctx), v[1].eval(ctx), v[2].eval(ctx)};
    }
}
