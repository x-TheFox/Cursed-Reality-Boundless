package io.github.xthefox.crb.client.fx;

import io.github.xthefox.crb.molang.MolangContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.SplittableRandom;

/** A running Bedrock emitter: lifetime, spawn rate, shape. Ticks at 20 Hz with the client. */
public final class FxEmitter {
    static final double DT = 0.05;

    final FxDef def;
    final ClientLevel level;
    Vec3 pos;
    float yaw;
    final Entity attached;
    final Vec3 attachOffset;
    final MolangContext ctx;
    final SplittableRandom random;
    double age, activeTime, sleepTime, cycleStart;
    boolean active = true, finishedEmitting, instantDone;
    double spawnAccumulator;
    int alive;
    private boolean expiredEventsFired;

    FxEmitter(FxDef def, ClientLevel level, Vec3 pos, float yaw, Entity attached, long seed) {
        this.def = def;
        this.level = level;
        this.pos = pos;
        this.yaw = yaw;
        this.attached = attached;
        this.attachOffset = attached == null ? Vec3.ZERO : pos.subtract(attached.position());
        this.random = new SplittableRandom(seed);
        this.ctx = new MolangContext(random);
        ctx.set(MolangContext.EMITTER_RANDOM_1, random.nextDouble());
        ctx.set(MolangContext.EMITTER_RANDOM_2, random.nextDouble());
        ctx.set(MolangContext.EMITTER_RANDOM_3, random.nextDouble());
        ctx.set(MolangContext.EMITTER_RANDOM_4, random.nextDouble());
        ctx.set(MolangContext.ENTITY_SCALE, 1);
        ctx.set(MolangContext.EMITTER_AGE, 0);
        def.emitterCreation.eval(ctx);
        startCycle();
        for (String e : def.emitterCreationEvents) BedrockFX.fireEvent(def, e, pos, this, ctx);
    }

    private void startCycle() {
        cycleStart = age;
        activeTime = def.activeTime.eval(ctx);
        sleepTime = def.sleepTime.eval(ctx);
        ctx.set(MolangContext.EMITTER_LIFETIME, activeTime);
        instantDone = false;
        active = true;
    }

    /** @return false when the emitter is finished and has no particles left that depend on it */
    boolean tick() {
        if (attached != null) {
            if (attached.isRemoved()) finishedEmitting = true;
            else {
                pos = attached.position().add(attachOffset);
                yaw = attached.getYRot();
            }
        }
        double local = age - cycleStart;
        ctx.set(MolangContext.EMITTER_AGE, local);
        def.emitterUpdate.eval(ctx);

        if (!finishedEmitting) {
            if (def.activation != null) {
                active = def.activation.eval(ctx) != 0;
                if (def.expiration.eval(ctx) != 0) finishedEmitting = true;
            } else if (local >= activeTime) {
                if (def.looping) {
                    if (local >= activeTime + sleepTime) startCycle();
                    else active = false;
                } else {
                    finishedEmitting = true;
                }
            }
        }
        if (!finishedEmitting && active) emit();
        age += DT;

        if (finishedEmitting && !expiredEventsFired) {
            expiredEventsFired = true;
            for (String e : def.emitterExpirationEvents) BedrockFX.fireEvent(def, e, pos, this, ctx);
        }
        return !(finishedEmitting && alive <= 0);
    }

    private void emit() {
        if (def.rateInstant) {
            if (!instantDone) {
                instantDone = true;
                int n = (int) Math.round(def.numParticles.eval(ctx));
                for (int i = 0; i < n && BedrockFX.canSpawn(); i++) spawnParticle();
            }
        } else {
            spawnAccumulator += def.spawnRate.eval(ctx) * DT;
            int max = (int) def.maxParticles.eval(ctx);
            while (spawnAccumulator >= 1 && alive < max && BedrockFX.canSpawn()) {
                spawnAccumulator -= 1;
                spawnParticle();
            }
            if (spawnAccumulator > 1) spawnAccumulator = 1;
        }
    }

    private void spawnParticle() {
        MolangContext p = new MolangContext(random);
        p.copyFrom(ctx);
        p.set(MolangContext.PARTICLE_RANDOM_1, random.nextDouble());
        p.set(MolangContext.PARTICLE_RANDOM_2, random.nextDouble());
        p.set(MolangContext.PARTICLE_RANDOM_3, random.nextDouble());
        p.set(MolangContext.PARTICLE_RANDOM_4, random.nextDouble());
        p.set(MolangContext.PARTICLE_AGE, 0);
        for (FxCurve c : def.curves) c.apply(p);

        double[] offset = FxDef.eval3(def.shapeOffset, p);
        double[] local = new double[3];
        double[] dir = new double[3];
        sampleShape(p, local, dir);
        local[0] += offset[0];
        local[1] += offset[1];
        local[2] += offset[2];

        double[] vel;
        if (def.initialSpeedVector != null) {
            double[] sv = FxDef.eval3(def.initialSpeedVector, p);
            vel = new double[]{dir[0] * sv[0], dir[1] * sv[1], dir[2] * sv[2]};
        } else {
            double s = def.initialSpeed.eval(p);
            vel = new double[]{dir[0] * s, dir[1] * s, dir[2] * s};
        }
        if (def.localRotation && yaw != 0) {
            rotateY(local, yaw);
            rotateY(vel, yaw);
        }
        FxParticle particle = new FxParticle(level, this, p, local, vel);
        alive++;
        if (BedrockFX.DEBUG) io.github.xthefox.crb.CRB.LOG.info("fx {} particle at {},{},{} vel {},{},{}", def.id,
                particle.debugPos()[0], particle.debugPos()[1], particle.debugPos()[2], vel[0], vel[1], vel[2]);
        net.minecraft.client.Minecraft.getInstance().particleEngine.add(particle);
    }

    /** Fills a local spawn position and unit direction for the emitter shape. */
    private void sampleShape(MolangContext p, double[] out, double[] dir) {
        switch (def.shape) {
            case POINT -> {
                out[0] = out[1] = out[2] = 0;
                randomUnit(dir);
            }
            case SPHERE -> {
                double r = def.radius.eval(p);
                randomUnit(out);
                double d = def.surfaceOnly ? r : r * Math.cbrt(random.nextDouble());
                dir[0] = out[0]; dir[1] = out[1]; dir[2] = out[2];
                out[0] *= d; out[1] *= d; out[2] *= d;
            }
            case BOX -> {
                double[] h = FxDef.eval3(def.halfDimensions, p);
                for (int i = 0; i < 3; i++) out[i] = (random.nextDouble() * 2 - 1) * h[i];
                if (def.surfaceOnly) {
                    int axis = random.nextInt(3);
                    out[axis] = random.nextBoolean() ? h[axis] : -h[axis];
                }
                normalize(out, dir);
            }
            case DISC -> {
                double r = def.radius.eval(p);
                double[] n = FxDef.eval3(def.planeNormal, p);
                double[] nn = new double[3];
                normalize(n, nn);
                double ang = random.nextDouble() * Math.PI * 2;
                double d = def.surfaceOnly ? r : r * Math.sqrt(random.nextDouble());
                // basis perpendicular to the normal
                double[] a = Math.abs(nn[1]) < 0.99 ? new double[]{0, 1, 0} : new double[]{1, 0, 0};
                double[] u = cross(nn, a), uu = new double[3];
                normalize(u, uu);
                double[] v = cross(nn, uu);
                for (int i = 0; i < 3; i++) out[i] = (uu[i] * Math.cos(ang) + v[i] * Math.sin(ang)) * d;
                normalize(out, dir);
            }
        }
        switch (def.direction) {
            case INWARDS -> { dir[0] = -dir[0]; dir[1] = -dir[1]; dir[2] = -dir[2]; }
            case VECTOR -> {
                double[] v = FxDef.eval3(def.directionVector, p);
                normalize(v, dir);
            }
            default -> { }
        }
    }

    private void randomUnit(double[] out) {
        double z = random.nextDouble() * 2 - 1, a = random.nextDouble() * Math.PI * 2, r = Math.sqrt(1 - z * z);
        out[0] = r * Math.cos(a);
        out[1] = z;
        out[2] = r * Math.sin(a);
    }

    static void normalize(double[] v, double[] out) {
        double l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (l < 1e-9) { out[0] = out[1] = out[2] = 0; return; }
        out[0] = v[0] / l; out[1] = v[1] / l; out[2] = v[2] / l;
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    /** Rotates around +Y by a Minecraft yaw (degrees; yaw 0 faces +Z). */
    static void rotateY(double[] v, float yawDeg) {
        double r = -yawDeg * Mth.DEG_TO_RAD, c = Math.cos(r), s = Math.sin(r);
        double x = v[0] * c + v[2] * s, z = -v[0] * s + v[2] * c;
        v[0] = x;
        v[2] = z;
    }
}
