package dev.gesp.structural.impact;

import dev.gesp.structural.model.NodePos;

/**
 * Optional hooks into an impact. All methods have no-op defaults, so implement
 * only what you need. Use {@link #NONE} to ignore everything.
 */
public interface ImpactCallback {

    /**
     * Fired before any physics. Return false to cancel the impact entirely
     * (e.g. protected region, peace time).
     */
    default boolean onImpact(ImpactContext ctx) {
        return true;
    }

    /** A surviving block took persistent crack damage; {@code damage} is its new level. */
    default void onDamaged(NodePos pos, double damage) {}

    /** The projectile punched clean through this block (it shattered on impact). */
    default void onPenetrate(NodePos pos) {}

    /** A block fell in the secondary structural cascade. */
    default void onCollapse(NodePos pos) {}

    /** The whole impact (penetration + cascade) is finished. */
    default void onComplete(ImpactResult result) {}

    /** A callback that does nothing. */
    ImpactCallback NONE = new ImpactCallback() {};
}
