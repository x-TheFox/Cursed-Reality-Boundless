package io.github.xthefox.crb.combat;

/**
 * Cursed-energy gain rules, taken from JJA:
 * <ul>
 *   <li>{@code index.js handleTimerCycle}: passive regen every 20 ticks of {@code ceil(cap / 90)}, so an
 *       idle sorcerer refills in 90 s. Paused during a move, a domain, a domain clash, and for
 *       Heavenly Restriction users.</li>
 *   <li>{@code energyrefill}: holding sneak for 5+ ticks while idle charges every tick:
 *       +1, +1 at cap ≥ 150, +1 at ≥ 250, +2 at ≥ 500, +3 at ≥ 1000, +2 with Faster Energy Charge.</li>
 *   <li>{@code attributes/sixeyes}: Six Eyes adds +5 every 2 ticks while idle, or +7 while charging.</li>
 * </ul>
 */
public final class EnergyRules {
    public static final int PASSIVE_INTERVAL = 20;
    public static final int FULL_REGEN_SECONDS = 90;
    public static final int CHARGE_DELAY_TICKS = 5;

    private EnergyRules() {}

    public record Context(int energyCap, boolean busy, boolean inDomain, boolean clashing,
                          boolean heavenlyRestriction, boolean sixEyes, boolean fasterCharge,
                          int sneakTicks, long gameTime) {}

    /** Energy to add this tick (before clamping to the cap). */
    public static int gainThisTick(Context c) {
        if (c.heavenlyRestriction() || c.energyCap() <= 0) return 0;
        boolean charging = !c.busy() && c.sneakTicks() >= CHARGE_DELAY_TICKS && !c.inDomain();
        int gain = 0;

        if (charging) {
            gain += 1;
            if (c.energyCap() >= 150) gain += 1;
            if (c.energyCap() >= 250) gain += 1;
            if (c.energyCap() >= 500) gain += 2;
            if (c.energyCap() >= 1000) gain += 3;
            if (c.fasterCharge()) gain += 2;
        }

        boolean passiveAllowed = !c.busy() && !c.inDomain() && !c.clashing();
        if (passiveAllowed && c.gameTime() % PASSIVE_INTERVAL == 0) {
            gain += (c.energyCap() + FULL_REGEN_SECONDS - 1) / FULL_REGEN_SECONDS;
        }

        if (c.sixEyes() && !c.inDomain() && !c.busy() && c.gameTime() % 2 == 0) {
            gain += charging ? 7 : 5;
        }
        return gain;
    }
}
