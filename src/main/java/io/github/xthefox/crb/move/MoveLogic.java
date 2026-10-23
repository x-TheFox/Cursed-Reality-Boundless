package io.github.xthefox.crb.move;

/**
 * Server-side simulation for a move (damage, projectiles, binds, block destruction...). The
 * presentation (animations, sounds, particles, camera) is data in the move script and plays on
 * clients by itself; a move without registered logic still times and syncs correctly.
 */
public interface MoveLogic {
    MoveLogic NONE = (move, t) -> {};

    /** Called once per server tick with the elapsed tick (0 on the start tick). */
    void tick(MoveInstance move, int t);

    /** Called once when the move ends for any reason. */
    default void end(MoveInstance move, EndReason reason) {}

    /** JJA cancels most moves when the caster is stunned ({@code cancel_attack}). */
    default boolean cancelledByStun() {
        return true;
    }

    enum EndReason { FINISHED, STUNNED, INTERRUPTED, CANCELLED, DIED }
}
