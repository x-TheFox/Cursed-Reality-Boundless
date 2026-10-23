package io.github.xthefox.crb.ability;

import java.util.List;
import java.util.Optional;

/**
 * An innate technique (Limitless, Ten Shadows, ...). Abilities are laid out in pages of five for
 * the Combat Mode keys {@code 1}-{@code 5}; the domain sits on key {@code 6}.
 *
 * <p>Abilities are filled in by each character's feature branch; the registry entry, rarity and
 * uniqueness exist from the start so kits, rolls and ownership can reference every technique.
 */
public final class Technique {
    public static final int KEYS_PER_PAGE = 5;

    /** Roll tier from JJA's creation menu. */
    public enum Rarity { COMMON, UNCOMMON, RARE, EXOTIC, HONORED, UNROLLABLE }

    private final Rarity rarity;
    private final boolean canonUnique;
    private final int maxForms;
    private List<Ability> abilities = List.of();
    private Ability domain;

    /**
     * @param rarity      roll tier
     * @param canonUnique a named character's technique, unique under {@code uniqueTechniques = CANON}
     * @param maxForms    highest mastery level (JJA {@code forms})
     */
    public Technique(Rarity rarity, boolean canonUnique, int maxForms) {
        this.rarity = rarity;
        this.canonUnique = canonUnique;
        this.maxForms = maxForms;
    }

    /** Called once by the technique's own setup class. */
    public Technique define(List<Ability> abilities, Ability domain) {
        this.abilities = List.copyOf(abilities);
        this.domain = domain;
        return this;
    }

    public Rarity rarity() { return rarity; }

    public boolean canonUnique() { return canonUnique; }

    public int maxForms() { return maxForms; }

    public List<Ability> abilities() { return abilities; }

    public Optional<Ability> domain() { return Optional.ofNullable(domain); }

    public int pages() {
        return Math.max(1, (abilities.size() + KEYS_PER_PAGE - 1) / KEYS_PER_PAGE);
    }

    /** Ability bound to {@code key} (0-based within the page) on {@code page}, if any. */
    public Optional<Ability> abilityAt(int page, int key) {
        int i = page * KEYS_PER_PAGE + key;
        return key >= 0 && key < KEYS_PER_PAGE && i >= 0 && i < abilities.size()
                ? Optional.of(abilities.get(i)) : Optional.empty();
    }
}
