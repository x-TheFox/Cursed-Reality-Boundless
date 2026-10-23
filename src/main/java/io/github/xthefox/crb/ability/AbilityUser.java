package io.github.xthefox.crb.ability;

/**
 * Anything that can cast abilities: players and NPC sorcerers share one implementation of every
 * move, as JJA's mobs run the same mcfunctions as players.
 */
public interface AbilityUser {
    int energy();

    /** Ticks left on a cooldown bucket; 0 when ready. */
    int cooldown(String key);

    /** Technique mastery (JJA {@code forms}). */
    int forms();

    /** Whether the user has a trait or unlock tag (six_eyes, rct, heian_era ...). */
    boolean has(String tag);

    boolean canAct();
}
