package io.github.xthefox.crb.progression;

import io.github.xthefox.crb.core.CRBConfig.Uniqueness;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OwnershipTest {
    static final ResourceLocation LIMITLESS = ResourceLocation.fromNamespaceAndPath("crb", "limitless");
    static final ResourceLocation SHRINE = ResourceLocation.fromNamespaceAndPath("crb", "shrine");
    static final ResourceLocation WIND_UP = ResourceLocation.fromNamespaceAndPath("crb", "wind_up");
    static final Set<ResourceLocation> CANON = Set.of(LIMITLESS, SHRINE);

    @Test
    void uniquenessModes() {
        assertFalse(Ownership.isUnique(LIMITLESS, Uniqueness.OFF, false, CANON::contains, SHRINE));
        assertTrue(Ownership.isUnique(SHRINE, Uniqueness.OFF, true, CANON::contains, SHRINE), "JJA's one-Sukuna rule applies even when OFF");
        assertTrue(Ownership.isUnique(LIMITLESS, Uniqueness.CANON, false, CANON::contains, SHRINE));
        assertFalse(Ownership.isUnique(WIND_UP, Uniqueness.CANON, false, CANON::contains, SHRINE));
        assertTrue(Ownership.isUnique(WIND_UP, Uniqueness.ALL, false, CANON::contains, SHRINE));
    }

    @Test
    void firstClaimWinsAndIsPermanent() {
        Ownership o = new Ownership();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertEquals(Ownership.ClaimResult.CLAIMED, o.claim(LIMITLESS, a, true));
        assertEquals(Ownership.ClaimResult.ALREADY_YOURS, o.claim(LIMITLESS, a, true));
        assertEquals(Ownership.ClaimResult.TAKEN, o.claim(LIMITLESS, b, true));
        assertFalse(o.available(LIMITLESS, b));
        assertTrue(o.available(LIMITLESS, a));
        assertEquals(Ownership.ClaimResult.NOT_UNIQUE, o.claim(WIND_UP, b, false));
        assertTrue(o.owner(WIND_UP).isEmpty(), "non-unique ids are never recorded");
    }

    @Test
    void operatorTransferAndRelease() {
        Ownership o = new Ownership();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        o.claim(LIMITLESS, a, true);
        assertEquals(a, o.transfer(LIMITLESS, b).orElseThrow());
        assertEquals(b, o.owner(LIMITLESS).orElseThrow());
        assertEquals(b, o.release(LIMITLESS).orElseThrow());
        assertTrue(o.available(LIMITLESS, a));
    }
}
