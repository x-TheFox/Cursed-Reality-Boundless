package io.github.xthefox.crb.gametest;

import io.github.xthefox.crb.CRB;
import io.github.xthefox.crb.core.CRBRegistries;
import io.github.xthefox.crb.data.CRBAttachments;
import io.github.xthefox.crb.data.SorcererData;
import io.github.xthefox.crb.progression.TechniqueOwners;
import io.github.xthefox.crb.progression.Traits;
import io.github.xthefox.crb.progression.kit.Kit;
import io.github.xthefox.crb.progression.kit.KitApplier;
import io.github.xthefox.crb.progression.kit.KitManager;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Server-side checks for kits and technique ownership with the default
 * {@code uniqueTechniques = CANON}. One sequential test, because ownership is world-wide state.
 */
@GameTestHolder(CRB.MOD_ID)
@PrefixGameTestTemplate(false)
public final class KitGameTests {
    private KitGameTests() {}

    private static Kit kit(GameTestHelper h, String id) {
        return KitManager.INSTANCE.get(CRB.id(id)).orElseThrow(() -> new net.minecraft.gametest.framework.GameTestAssertException("kit " + id + " not loaded"));
    }

    private static void releaseAll(GameTestHelper h) {
        TechniqueOwners owners = TechniqueOwners.get(h.getLevel().getServer());
        owners.book().all().keySet().forEach(owners::release);
    }

    @GameTest(template = "empty5")
    public static void kitsAndUniqueness(GameTestHelper h) {
        releaseAll(h);
        ServerPlayer first = h.makeMockServerPlayerInLevel();
        ServerPlayer second = h.makeMockServerPlayerInLevel();
        TechniqueOwners owners = TechniqueOwners.get(h.getLevel().getServer());

        // Gojo gets Limitless at max mastery and Six Eyes, and claims both.
        h.assertTrue(KitApplier.apply(first, kit(h, "gojo"), false).success(), "gojo kit should apply");
        SorcererData gojo = first.getData(CRBAttachments.SORCERER);
        h.assertTrue(gojo.technique().equals(CRBRegistries.LIMITLESS.getId()), "gojo has limitless");
        h.assertTrue(gojo.forms() == CRBRegistries.LIMITLESS.get().maxForms(), "gojo is fully mastered");
        h.assertTrue(gojo.has(Traits.SIX_EYES), "gojo has six eyes");
        h.assertTrue(owners.owner(Traits.SIX_EYES).filter(first.getUUID()::equals).isPresent(), "six eyes claimed");

        // A second Gojo is refused; Six Eyes alone is refused too.
        h.assertFalse(KitApplier.apply(second, kit(h, "gojo"), false).success(), "only one gojo per server");
        h.assertFalse(KitApplier.apply(second, kit(h, "gojo_student"), false).success(), "student gojo shares limitless");

        // Forcing transfers ownership and strips the previous holder.
        h.assertTrue(KitApplier.apply(second, kit(h, "gojo"), true).success(), "forced kit transfers");
        h.assertTrue(owners.owner(CRBRegistries.LIMITLESS.getId()).filter(second.getUUID()::equals).isPresent(), "limitless moved");
        h.assertFalse(first.getData(CRBAttachments.SORCERER).has(Traits.SIX_EYES), "previous gojo lost six eyes");

        // Yuji starts as Sukuna's vessel with one finger; that takes the Sukuna slot.
        h.assertTrue(KitApplier.apply(first, kit(h, "yuji"), false).success(), "yuji kit should apply");
        SorcererData yuji = first.getData(CRBAttachments.SORCERER);
        h.assertTrue(yuji.isVessel() && yuji.fingers() == 1, "yuji is a vessel with 1 finger");
        h.assertTrue(yuji.has(Traits.PERFECT_VESSEL), "yuji is the perfect vessel");

        // Only one Sukuna: the Sukuna kit is refused while Yuji holds the vessel.
        ServerPlayer third = h.makeMockServerPlayerInLevel();
        h.assertFalse(KitApplier.apply(third, kit(h, "sukuna"), false).success(), "sukuna is already inside yuji");

        // Re-kitting Yuji releases the vessel; then Sukuna gets Shrine and (post-Meguna) Ten Shadows.
        h.assertTrue(KitApplier.apply(first, kit(h, "todo"), false).success(), "todo kit should apply");
        h.assertTrue(KitApplier.apply(third, kit(h, "sukuna"), false).success(), "sukuna kit applies once the vessel is free");
        ResourceLocation tenShadows = CRBRegistries.TEN_SHADOWS.getId();
        h.assertTrue(owners.owner(tenShadows).filter(third.getUUID()::equals).isPresent(), "sukuna claims ten shadows");
        h.assertFalse(KitApplier.apply(first, kit(h, "megumi"), false).success(), "ten shadows is taken by sukuna");

        releaseAll(h);
        h.succeed();
    }
}
