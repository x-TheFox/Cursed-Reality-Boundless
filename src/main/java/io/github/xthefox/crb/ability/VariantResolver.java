package io.github.xthefox.crb.ability;

import java.util.Optional;

/** Server-side variant selection, the Java equivalent of JJA's {@code *_click} functions. */
public final class VariantResolver {
    private VariantResolver() {}

    public enum Failure { LOCKED, NO_VARIANT, ON_COOLDOWN, NOT_ENOUGH_ENERGY, BUSY }

    public record Result(Optional<Variant> variant, Failure failure) {
        static Result ok(Variant v) { return new Result(Optional.of(v), null); }
        static Result fail(Failure f) { return new Result(Optional.empty(), f); }
        static Result fail(Variant v, Failure f) { return new Result(Optional.of(v), f); }

        public boolean success() { return failure == null; }
    }

    /**
     * Picks the variant for the given input. A variant whose input matches but whose
     * requirements (traits, mastery) are missing is skipped, so the next listed variant can
     * apply, matching how JJA guards Six Eyes variants with {@code tag=sixeyes}.
     */
    public static Result resolve(Ability ability, Modifiers input, AbilityUser user, boolean cooldownsEnabled) {
        if (!user.canAct()) return Result.fail(Failure.BUSY);
        if (user.forms() < ability.unlockForm()) return Result.fail(Failure.LOCKED);
        for (Variant v : ability.variants()) {
            if (!v.matches(input)) continue;
            if (user.forms() < v.minForms()) continue;
            if (!v.requirements().stream().allMatch(user::has)) continue;
            if (cooldownsEnabled && user.cooldown(v.cooldownKey()) > 0) return Result.fail(v, Failure.ON_COOLDOWN);
            if (user.energy() < v.energyCost()) return Result.fail(v, Failure.NOT_ENOUGH_ENERGY);
            return Result.ok(v);
        }
        return Result.fail(Failure.NO_VARIANT);
    }
}
