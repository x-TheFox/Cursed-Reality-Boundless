package io.github.xthefox.crb.client.fx;

import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.xthefox.crb.molang.MolangContext;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/**
 * One Bedrock particle. Motion and appearance are evaluated per tick; rendering interpolates the
 * previous and current tick's position, size, colour, UV and rotation with the partial tick.
 */
final class FxParticle extends Particle {
    private final FxDef def;
    private final FxEmitter emitter;
    private final MolangContext ctx;
    private final double lifeSeconds;
    private final double[] vel;            // blocks per second
    private final double[] local;          // position relative to the emitter (local-space position)
    private double rot, prevRot, rotRate;
    private float w, h, prevW, prevH;
    private float u0, v0, u1, v1;
    private int argb = 0xFFFFFFFF, prevArgb = 0xFFFFFFFF;
    private final Vector3f dir = new Vector3f(0, 0, 1);
    private Map.Entry<Float, List<String>> nextTimeline;
    private boolean dead;

    FxParticle(ClientLevel level, FxEmitter emitter, MolangContext ctx, double[] local, double[] vel) {
        super(level, emitter.pos.x + local[0], emitter.pos.y + local[1], emitter.pos.z + local[2]);
        this.def = emitter.def;
        this.emitter = emitter;
        this.ctx = ctx;
        this.local = local;
        this.vel = vel;
        this.lifeSeconds = Math.max(0.05, def.maxLifetime.eval(ctx));
        ctx.set(MolangContext.PARTICLE_LIFETIME, lifeSeconds);
        this.lifetime = (int) Math.ceil(lifeSeconds * 20) + 1;
        this.rot = this.prevRot = def.initialRotation.eval(ctx);
        this.rotRate = def.rotationRate.eval(ctx);
        this.hasPhysics = def.collision;
        this.gravity = 0;
        this.friction = 1;
        setSize(def.collisionRadius * 2, def.collisionRadius * 2);
        this.nextTimeline = def.particleTimeline.firstEntry();
        updateDirection();
        evalAppearance();
        prevW = w; prevH = h; prevArgb = argb;
        for (String e : def.particleCreationEvents) BedrockFX.fireEvent(def, e, new Vec3(x, y, z), emitter, ctx);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return FxRenderType.of(def.texture, def.material);
    }

    @Override
    public void tick() {
        xo = x; yo = y; zo = z;
        prevRot = rot;
        prevW = w; prevH = h; prevArgb = argb;
        age++;
        double t = age * FxEmitter.DT;
        ctx.set(MolangContext.PARTICLE_AGE, t);
        if (t >= lifeSeconds || (def.expirationExpr != null && def.expirationExpr.eval(ctx) != 0)) {
            kill();
            return;
        }
        for (FxCurve c : def.curves) c.apply(ctx);

        if (def.parametricPosition != null) {
            double[] p = FxDef.eval3(def.parametricPosition, ctx);
            setPos(emitter.pos.x + p[0], emitter.pos.y + p[1], emitter.pos.z + p[2]);
        } else {
            double[] a = FxDef.eval3(def.acceleration, ctx);
            double drag = def.linearDrag.eval(ctx);
            for (int i = 0; i < 3; i++) vel[i] += (a[i] - drag * vel[i]) * FxEmitter.DT;
            double dx = vel[0] * FxEmitter.DT, dy = vel[1] * FxEmitter.DT, dz = vel[2] * FxEmitter.DT;
            if (def.localPosition) {
                local[0] += dx; local[1] += dy; local[2] += dz;
                setPos(emitter.pos.x + local[0], emitter.pos.y + local[1], emitter.pos.z + local[2]);
            } else if (def.collision && def.collisionEnabled.eval(ctx) != 0) {
                double ex = x + dx, ey = y + dy, ez = z + dz;
                move(dx, dy, dz);
                boolean hitX = Math.abs(x - ex) > 1e-6, hitY = Math.abs(y - ey) > 1e-6, hitZ = Math.abs(z - ez) > 1e-6;
                if (hitX || hitY || hitZ) {
                    if (def.expireOnContact) { kill(); return; }
                    if (hitX) vel[0] *= -def.restitution;
                    if (hitY) vel[1] *= -def.restitution;
                    if (hitZ) vel[2] *= -def.restitution;
                    double keep = Math.max(0, 1 - def.collisionDrag * FxEmitter.DT);
                    vel[0] *= keep; vel[2] *= keep;
                }
            } else {
                setPos(x + dx, y + dy, z + dz);
            }
        }
        double rAcc = def.rotationAcceleration.eval(ctx), rDrag = def.rotationDrag.eval(ctx);
        rotRate += (rAcc - rDrag * rotRate) * FxEmitter.DT;
        rot = def.parametricRotation != null ? def.parametricRotation.eval(ctx) : rot + rotRate * FxEmitter.DT;

        if (!def.expireIfNotIn.isEmpty() || !def.expireIfIn.isEmpty()) {
            String block = BuiltInRegistries.BLOCK.getKey(level.getBlockState(BlockPos.containing(x, y, z)).getBlock()).toString();
            if (!def.expireIfNotIn.isEmpty() && !def.expireIfNotIn.contains(block)) { kill(); return; }
            if (def.expireIfIn.contains(block)) { kill(); return; }
        }
        while (nextTimeline != null && nextTimeline.getKey() <= t) {
            for (String e : nextTimeline.getValue()) BedrockFX.fireEvent(def, e, new Vec3(x, y, z), emitter, ctx);
            nextTimeline = def.particleTimeline.higherEntry(nextTimeline.getKey());
        }
        updateDirection();
        evalAppearance();
    }

    double[] debugPos() {
        return new double[]{x, y, z};
    }

    private void kill() {
        if (BedrockFX.DEBUG && !dead) io.github.xthefox.crb.CRB.LOG.info("fx {} died at age {} size {}x{} argb {}", def.id, age, w, h,
                Integer.toHexString(argb));
        if (dead) return;
        dead = true;
        for (String e : def.particleExpirationEvents) BedrockFX.fireEvent(def, e, new Vec3(x, y, z), emitter, ctx);
        emitter.alive--;
        remove();
    }

    private void updateDirection() {
        if (def.customDirection && def.customDirectionVector != null) {
            double[] d = FxDef.eval3(def.customDirectionVector, ctx);
            dir.set((float) d[0], (float) d[1], (float) d[2]);
        } else {
            double s = Math.sqrt(vel[0] * vel[0] + vel[1] * vel[1] + vel[2] * vel[2]);
            if (s > def.minSpeedThreshold) dir.set((float) vel[0], (float) vel[1], (float) vel[2]);
        }
        if (dir.lengthSquared() < 1e-12f) dir.set(0, 0, 1);
        dir.normalize();
    }

    private void evalAppearance() {
        w = (float) def.size[0].eval(ctx);
        h = (float) def.size[1].eval(ctx);
        float tw = def.textureWidth, th = def.textureHeight;
        if (def.flipbook) {
            double bu = def.flipBase[0].eval(ctx), bv = def.flipBase[1].eval(ctx);
            double su = def.flipSize[0].eval(ctx), sv = def.flipSize[1].eval(ctx);
            int max = Math.max(1, (int) def.maxFrame.eval(ctx));
            double ageS = age * FxEmitter.DT;
            int frame = def.stretchToLifetime ? (int) (ageS / lifeSeconds * max) : (int) (ageS * def.framesPerSecond);
            frame = def.loopFlipbook ? Math.floorMod(frame, max) : Math.min(frame, max - 1);
            double fu = bu + def.flipStep[0] * frame, fv = bv + def.flipStep[1] * frame;
            u0 = (float) (fu / tw); v0 = (float) (fv / th); u1 = (float) ((fu + su) / tw); v1 = (float) ((fv + sv) / th);
        } else if (def.uv != null) {
            double uu = def.uv[0].eval(ctx), vv = def.uv[1].eval(ctx), su = def.uvSize[0].eval(ctx), sv = def.uvSize[1].eval(ctx);
            u0 = (float) (uu / tw); v0 = (float) (vv / th); u1 = (float) ((uu + su) / tw); v1 = (float) ((vv + sv) / th);
        } else {
            u0 = 0; v0 = 0; u1 = 1; v1 = 1;
        }
        if (def.color != null) {
            argb = pack(def.color[0].eval(ctx), def.color[1].eval(ctx), def.color[2].eval(ctx), def.color[3].eval(ctx));
        } else if (def.gradientKeys != null && def.gradientKeys.length > 0) {
            argb = gradient((float) def.gradientInterpolant.eval(ctx));
        }
    }

    private int gradient(float t) {
        float[] k = def.gradientKeys;
        int[] c = def.gradientColors;
        if (t <= k[0]) return c[0];
        for (int i = 1; i < k.length; i++) {
            if (t <= k[i]) {
                float f = (t - k[i - 1]) / Math.max(1e-6f, k[i] - k[i - 1]);
                return lerpColor(c[i - 1], c[i], f);
            }
        }
        return c[c.length - 1];
    }

    static int pack(double r, double g, double b, double a) {
        return ((int) (Mth.clamp(a, 0, 1) * 255) << 24) | ((int) (Mth.clamp(r, 0, 1) * 255) << 16)
                | ((int) (Mth.clamp(g, 0, 1) * 255) << 8) | (int) (Mth.clamp(b, 0, 1) * 255);
    }

    static int lerpColor(int a, int b, float f) {
        int aa = a >>> 24, ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
        int ba = b >>> 24, br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return ((int) Mth.lerp(f, aa, ba) << 24) | ((int) Mth.lerp(f, ar, br) << 16) | ((int) Mth.lerp(f, ag, bg) << 8) | (int) Mth.lerp(f, ab, bb);
    }

    private boolean loggedRender;

    @Override
    public void render(VertexConsumer buffer, Camera camera, float pt) {
        if (BedrockFX.DEBUG && !loggedRender) {
            loggedRender = true;
            io.github.xthefox.crb.CRB.LOG.info("fx {} first render age {} size {}x{} argb {} uv {},{}-{},{}", def.id, age, w, h,
                    Integer.toHexString(argb), u0, v0, u1, v1);
        }
        Vec3 cam = camera.getPosition();
        float px = (float) (Mth.lerp(pt, xo, x) - cam.x);
        float py = (float) (Mth.lerp(pt, yo, y) - cam.y);
        float pz = (float) (Mth.lerp(pt, zo, z) - cam.z);
        float hw = Mth.lerp(pt, prevW, w), hh = Mth.lerp(pt, prevH, h);
        if (hw == 0 && hh == 0) return;
        int c = lerpColor(prevArgb, argb, pt);
        if ((c >>> 24) == 0) return;

        Vector3f right = new Vector3f(), up = new Vector3f();
        basis(camera, px, py, pz, right, up);
        double angle = Mth.lerp(pt, prevRot, rot) * Mth.DEG_TO_RAD;
        if (angle != 0) {
            float cs = (float) Math.cos(angle), sn = (float) Math.sin(angle);
            Vector3f r2 = new Vector3f(right).mul(cs).add(new Vector3f(up).mul(sn));
            Vector3f u2 = new Vector3f(up).mul(cs).sub(new Vector3f(right).mul(sn));
            right.set(r2);
            up.set(u2);
        }
        right.mul(hw);
        up.mul(hh);
        int light = def.lit ? getLightColor(pt) : LightTexture.FULL_BRIGHT;
        int a = c >>> 24, r = c >> 16 & 255, g = c >> 8 & 255, b = c & 255;
        // Bedrock particles are double-sided; Minecraft culls clockwise quads, so wind the quad
        // counter-clockwise as seen from the camera (camera sits at the origin of these coordinates).
        Vector3f normal = new Vector3f(right).cross(up);
        boolean facesCamera = normal.x * -px + normal.y * -py + normal.z * -pz >= 0;
        if (facesCamera) {
            vertex(buffer, px + right.x - up.x, py + right.y - up.y, pz + right.z - up.z, u1, v1, r, g, b, a, light);
            vertex(buffer, px + right.x + up.x, py + right.y + up.y, pz + right.z + up.z, u1, v0, r, g, b, a, light);
            vertex(buffer, px - right.x + up.x, py - right.y + up.y, pz - right.z + up.z, u0, v0, r, g, b, a, light);
            vertex(buffer, px - right.x - up.x, py - right.y - up.y, pz - right.z - up.z, u0, v1, r, g, b, a, light);
        } else {
            vertex(buffer, px - right.x - up.x, py - right.y - up.y, pz - right.z - up.z, u0, v1, r, g, b, a, light);
            vertex(buffer, px - right.x + up.x, py - right.y + up.y, pz - right.z + up.z, u0, v0, r, g, b, a, light);
            vertex(buffer, px + right.x + up.x, py + right.y + up.y, pz + right.z + up.z, u1, v0, r, g, b, a, light);
            vertex(buffer, px + right.x - up.x, py + right.y - up.y, pz + right.z - up.z, u1, v1, r, g, b, a, light);
        }
    }

    private static void vertex(VertexConsumer b, float x, float y, float z, float u, float v, int r, int g, int bl, int a, int light) {
        b.addVertex(x, y, z).setUv(u, v).setColor(r, g, bl, a).setLight(light);
    }

    /** Quad axes for the Bedrock facing_camera_mode. {@code right} is +u, {@code up} is -v. */
    private void basis(Camera camera, float px, float py, float pz, Vector3f right, Vector3f up) {
        Vector3f toCam = new Vector3f(-px, -py, -pz);
        if (toCam.lengthSquared() < 1e-8f) toCam.set(0, 0, 1);
        toCam.normalize();
        Vector3f worldUp = new Vector3f(0, 1, 0);
        switch (def.facing) {
            case ROTATE_XYZ -> {
                camera.rotation().transform(right.set(-1, 0, 0));
                camera.rotation().transform(up.set(0, 1, 0));
            }
            case ROTATE_Y -> {
                camera.rotation().transform(right.set(-1, 0, 0));
                right.y = 0;
                safeNormalize(right, new Vector3f(1, 0, 0));
                up.set(worldUp);
            }
            case LOOKAT_XYZ -> {
                worldUp.cross(toCam, right);
                safeNormalize(right, new Vector3f(1, 0, 0));
                toCam.cross(right, up);
            }
            case LOOKAT_Y -> {
                Vector3f flat = new Vector3f(toCam.x, 0, toCam.z);
                safeNormalize(flat, new Vector3f(0, 0, 1));
                worldUp.cross(flat, right);
                up.set(worldUp);
            }
            case LOOKAT_DIRECTION -> {
                right.set(dir);
                toCam.cross(right, up);
                safeNormalize(up, perpendicular(right));
            }
            case DIRECTION_X -> {
                right.set(dir);
                up.set(perpendicular(right));
            }
            case DIRECTION_Y -> {
                up.set(dir);
                up.cross(worldUp, right);
                safeNormalize(right, new Vector3f(1, 0, 0));
            }
            case DIRECTION_Z -> {
                worldUp.cross(dir, right);
                safeNormalize(right, new Vector3f(1, 0, 0));
                new Vector3f(dir).cross(right, up);
            }
            case EMITTER_TRANSFORM_XY -> { right.set(1, 0, 0); up.set(0, 1, 0); emitterRotate(right, up); }
            case EMITTER_TRANSFORM_XZ -> { right.set(1, 0, 0); up.set(0, 0, 1); emitterRotate(right, up); }
            case EMITTER_TRANSFORM_YZ -> { right.set(0, 0, 1); up.set(0, 1, 0); emitterRotate(right, up); }
        }
    }

    private void emitterRotate(Vector3f right, Vector3f up) {
        if (!def.localRotation || emitter.yaw == 0) return;
        float r = -emitter.yaw * Mth.DEG_TO_RAD;
        right.rotateY(r);
        up.rotateY(r);
    }

    private static Vector3f perpendicular(Vector3f v) {
        Vector3f hint = Math.abs(v.y) < 0.99f ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0);
        Vector3f p = new Vector3f(hint).sub(new Vector3f(v).mul(hint.dot(v)));
        return p.normalize();
    }

    private static void safeNormalize(Vector3f v, Vector3f fallback) {
        if (v.lengthSquared() < 1e-10f) v.set(fallback);
        else v.normalize();
    }
}
