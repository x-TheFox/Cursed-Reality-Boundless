package io.github.xthefox.crb.ability;

import java.util.Set;

/**
 * One concrete move an ability key can start, e.g. Lapse Blue's sneak variant ("form2b").
 *
 * @param form          JJA form tag, kept for traceability to the research tables
 * @param nameKey       translation key shown on the HUD and in cooldown messages
 * @param sneak         required sneak state
 * @param airborne      required airborne state
 * @param lookUp        required look-up state
 * @param energyCost    cursed energy removed on start (also the minimum required)
 * @param cooldownKey   shared cooldown bucket (JJA {@code ability1..12}, {@code domainCD})
 * @param cooldownTicks cooldown applied on start
 * @param moveId        move script / logic id the variant runs
 * @param durationTicks length of the move timeline
 * @param requirements  tags the user must have (e.g. {@code six_eyes}, {@code rct}); see {@link AbilityUser#has}
 * @param minForms      technique mastery level needed (JJA {@code forms})
 */
public record Variant(
        String form,
        String nameKey,
        Tri sneak,
        Tri airborne,
        Tri lookUp,
        int energyCost,
        String cooldownKey,
        int cooldownTicks,
        String moveId,
        int durationTicks,
        Set<String> requirements,
        int minForms
) {
    public boolean matches(Modifiers m) {
        return sneak.test(m.sneak()) && airborne.test(m.airborne()) && lookUp.test(m.lookUp());
    }

    public static Builder builder(String form, String moveId) {
        return new Builder(form, moveId);
    }

    public static final class Builder {
        private final String form;
        private final String moveId;
        private String nameKey;
        private Tri sneak = Tri.NO, airborne = Tri.NO, lookUp = Tri.ANY;
        private int energy, cooldown, duration, minForms;
        private String cooldownKey;
        private Set<String> requirements = Set.of();

        private Builder(String form, String moveId) {
            this.form = form;
            this.moveId = moveId;
            this.nameKey = "move.crb." + moveId.replace('/', '.');
            this.cooldownKey = form;
        }

        public Builder name(String key) { this.nameKey = key; return this; }
        public Builder sneak(Tri t) { this.sneak = t; return this; }
        public Builder airborne(Tri t) { this.airborne = t; return this; }
        public Builder lookUp(Tri t) { this.lookUp = t; return this; }
        public Builder energy(int e) { this.energy = e; return this; }
        public Builder cooldown(String key, int ticks) { this.cooldownKey = key; this.cooldown = ticks; return this; }
        public Builder duration(int ticks) { this.duration = ticks; return this; }
        public Builder requires(String... tags) { this.requirements = Set.of(tags); return this; }
        public Builder minForms(int forms) { this.minForms = forms; return this; }

        public Variant build() {
            return new Variant(form, nameKey, sneak, airborne, lookUp, energy, cooldownKey, cooldown, moveId,
                    duration, requirements, minForms);
        }
    }
}
