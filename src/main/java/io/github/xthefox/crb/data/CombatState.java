package io.github.xthefox.crb.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.common.util.INBTSerializable;

/**
 * Transient combat state for any living entity (players and NPCs). Replaces JJA's
 * {@code Stun}, {@code Frames} (i-frames), {@code Move}/{@code Camera} (input locks) and
 * {@code thezone} objectives. Synced to the owner and to players tracking the entity, because
 * other clients need it for animation and HUD.
 */
public final class CombatState implements INBTSerializable<CompoundTag> {
    private int stun;
    private int iframes;
    private int moveLock;
    private int cameraLock;
    private int zone;
    /** JJA {@code infinity}: ticks of Gojo's Infinity left (active from 3, like JJA's {@code infinity=3..}). */
    private int infinity;
    /** The user switched passive Infinity off (pressing Infinity while it is up). */
    private boolean infinitySuppressed;
    private boolean dirty;

    public int stun() { return stun; }
    public int iframes() { return iframes; }
    public int moveLock() { return moveLock; }
    public int cameraLock() { return cameraLock; }
    public int zone() { return zone; }
    public int infinity() { return infinity; }

    public boolean stunned() { return stun > 0; }
    public boolean invulnerable() { return iframes > 0; }
    public boolean movementLocked() { return moveLock > 0; }
    public boolean cameraLocked() { return cameraLock > 0; }
    public boolean inTheZone() { return zone > 0; }
    public boolean infinityActive() { return infinity >= 3; }
    public boolean infinitySuppressed() { return infinitySuppressed; }
    public void suppressInfinity(boolean b) { if (b != infinitySuppressed) { infinitySuppressed = b; dirty = true; } }

    /** Applies a stun, keeping the longer of the current and new duration (JJA overwrites with the max). */
    public void stun(int ticks) { if (ticks > stun) { stun = ticks; dirty = true; } }
    public void iframes(int ticks) { if (ticks > iframes) { iframes = ticks; dirty = true; } }
    /** Only the start of a lock is sent (its end is too, from {@link #tick()}); refreshing it each tick is silent. */
    public void lockMovement(int ticks) { if (ticks > moveLock) { if (moveLock == 0) dirty = true; moveLock = ticks; } }
    public void lockCamera(int ticks) { if (ticks > cameraLock) { if (cameraLock == 0) dirty = true; cameraLock = ticks; } }
    public void zone(int ticks) { if (ticks > zone) { zone = ticks; dirty = true; } }

    public void clearStun() { if (stun != 0) { stun = 0; dirty = true; } }
    /** Sets Infinity's remaining ticks (JJA overwrites the score). 0 turns it off. */
    public void infinity(int ticks) { if (ticks != infinity) { infinity = Math.max(0, ticks); dirty = true; } }

    /**
     * Counts every timer down one tick. A timer reaching zero marks the state dirty so clients
     * learn the exact end (start is always sent explicitly).
     */
    public void tick() {
        if (stun > 0 && --stun == 0) dirty = true;
        if (iframes > 0 && --iframes == 0) dirty = true;
        if (moveLock > 0 && --moveLock == 0) dirty = true;
        if (cameraLock > 0 && --cameraLock == 0) dirty = true;
        if (zone > 0 && --zone == 0) dirty = true;
        if (infinity > 0 && --infinity == 2) dirty = true; // inactive from here
    }

    public boolean isDirty() { return dirty; }
    public void clearDirty() { dirty = false; }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag t = new CompoundTag();
        t.putInt("stun", stun);
        t.putInt("iframes", iframes);
        t.putInt("moveLock", moveLock);
        t.putInt("cameraLock", cameraLock);
        t.putInt("zone", zone);
        t.putInt("infinity", infinity);
        t.putBoolean("infinityOff", infinitySuppressed);
        return t;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag t) {
        stun = t.getInt("stun");
        iframes = t.getInt("iframes");
        moveLock = t.getInt("moveLock");
        cameraLock = t.getInt("cameraLock");
        zone = t.getInt("zone");
        infinity = t.getInt("infinity");
        infinitySuppressed = t.getBoolean("infinityOff");
    }
}
