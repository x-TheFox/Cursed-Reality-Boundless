package io.github.xthefox.crb.projectile;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * JJA's {@code ds:projectile}: an invisible, named carrier that a technique's tick function runs on
 * ({@code execute as @e[name=Lapse2,type=ds:projectile] at @s run function limitless/lapseblue2}).
 *
 * <p>Its {@code Deleter} score counts down one per tick and removes it at 1; the per-name runner
 * script ({@link ProjectileData}) is keyed on that remaining count. The server runs the simulation
 * lines ({@link ProjectileSim}) and any Java logic for the name ({@link ProjectileLogics}); clients
 * play the presentation lines (particles, sounds) from the synced name and count, so there is no
 * per-effect network traffic.
 */
public class TechniqueProjectile extends Entity implements TraceableEntity {
    /** No Deleter score yet. Ungated runner lines still run, like Bedrock. */
    public static final int UNSET = -1_000_000;
    /** JJA's shootprojectile script stops moving a projectile after this many ticks. */
    public static final int SHOT_FLIGHT_TICKS = 200;
    /** Safety net for projectiles nothing ever gives a lifetime. */
    public static final int MAX_UNSET_AGE = 1200;
    /** Bedrock hurt cooldown: within it, only damage above the last hit lands. */
    static final int HURT_COOLDOWN = 10;

    private static final EntityDataAccessor<String> NAME = SynchedEntityData.defineId(TechniqueProjectile.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> REMAINING = SynchedEntityData.defineId(TechniqueProjectile.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> OWNER_ID = SynchedEntityData.defineId(TechniqueProjectile.class, EntityDataSerializers.INT);

    /** Set by the client bootstrap: plays the runner's presentation lines. */
    public static Consumer<TechniqueProjectile> clientTick = p -> { };

    private UUID ownerUuid;
    private LivingEntity owner;
    private Vec3 velocity = Vec3.ZERO;
    private int flightTicks;
    private int age;
    /** Per-target last hit for {@link #HURT_COOLDOWN}. */
    final Map<UUID, LastHit> lastHits = new HashMap<>();

    static final class LastHit {
        long tick;
        float amount;

        LastHit(long tick, float amount) {
            this.tick = tick;
            this.amount = amount;
        }
    }

    public TechniqueProjectile(EntityType<? extends TechniqueProjectile> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public static TechniqueProjectile spawn(ServerLevel level, String name, LivingEntity owner, Vec3 pos, float yaw, float pitch) {
        TechniqueProjectile p = new TechniqueProjectile(ProjectileTypes.TECHNIQUE_PROJECTILE.get(), level);
        p.setName(name);
        p.setOwner(owner);
        p.moveTo(pos.x, pos.y, pos.z, yaw, pitch);
        level.addFreshEntity(p);
        return p;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(NAME, "");
        b.define(REMAINING, UNSET);
        b.define(OWNER_ID, -1);
    }

    public String name() { return entityData.get(NAME); }
    public void setName(String n) { entityData.set(NAME, n); }
    public int remaining() { return entityData.get(REMAINING); }
    public void setRemaining(int r) { entityData.set(REMAINING, r); }
    public int age() { return age; }
    public Vec3 velocity() { return velocity; }

    /** Gives the projectile JJA's shootprojectile flight: {@code v} blocks per tick, for 200 ticks. */
    public void launch(Vec3 v) {
        this.velocity = v;
        this.flightTicks = SHOT_FLIGHT_TICKS;
    }

    public void stop() {
        this.velocity = Vec3.ZERO;
        this.flightTicks = 0;
    }

    public void setOwner(LivingEntity e) {
        this.owner = e;
        this.ownerUuid = e == null ? null : e.getUUID();
        entityData.set(OWNER_ID, e == null ? -1 : e.getId());
    }

    @Override
    public LivingEntity getOwner() {
        if (owner != null && !owner.isRemoved()) return owner;
        if (level().isClientSide) {
            return level().getEntity(entityData.get(OWNER_ID)) instanceof LivingEntity l ? l : null;
        }
        if (ownerUuid != null && level() instanceof ServerLevel sl && sl.getEntity(ownerUuid) instanceof LivingEntity l) {
            owner = l;
            return l;
        }
        return null;
    }

    public boolean ownedBy(LivingEntity e) {
        return e != null && e.getUUID().equals(ownerUuid);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            clientTick.accept(this);
            return;
        }
        age++;
        int d = remaining();
        if (d != UNSET && d <= 1 || d == UNSET && age > MAX_UNSET_AGE) {
            discard();
            return;
        }
        LivingEntity o = getOwner();
        ProjectileSim.run(this, d, o);
        ProjectileLogics.get(name()).tick(this, d, o);
        if (isRemoved()) return;
        if (flightTicks > 0) {
            flightTicks--;
            Vec3 next = position().add(velocity);
            setPos(next.x, next.y, next.z);
        }
        int now = remaining();
        if (now != UNSET) setRemaining(now - 1);
    }

    @Override
    public boolean isPickable() { return false; }

    @Override
    public boolean isPushable() { return false; }

    @Override
    public boolean shouldBeSaved() { return false; }

    @Override
    public boolean ignoreExplosion(net.minecraft.world.level.Explosion explosion) { return true; }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) { return false; }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) { }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) { }
}
