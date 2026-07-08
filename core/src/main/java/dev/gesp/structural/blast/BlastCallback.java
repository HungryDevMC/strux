package dev.gesp.structural.blast;

import dev.gesp.structural.api.CollapseReason;
import dev.gesp.structural.model.NodePos;

/**
 * Optional hooks into an explosion. All methods have no-op defaults, so
 * implement only what you need. Use {@link #NONE} to ignore everything.
 */
public interface BlastCallback {

    /**
     * Fired before any physics. Return false to cancel the explosion entirely
     * (e.g. protected region, peace time). The context is immutable here; to
     * change power/shape, build a different context before calling the engine.
     */
    default boolean onBlast(BlastContext ctx) {
        return true;
    }

    /** A block was shattered directly by the blast (the crater). */
    default void onDirectDestroy(NodePos pos) {}

    /** A surviving block took persistent damage; {@code damage} is its new level. */
    default void onDamaged(NodePos pos, double damage) {}

    /**
     * A block fell in the secondary structural cascade with no single structural
     * reason — it shattered from accumulated blast damage rather than from losing
     * support or from stress. The engine also calls this (via the default of the
     * two-arg overload) for the reason-tagged collapses, so a callback that only
     * overrides this method sees EVERY cascade collapse regardless of cause.
     */
    default void onCollapse(NodePos pos) {}

    /**
     * A block fell in the secondary structural cascade for a specific structural
     * reason: {@link CollapseReason#FLOATING} (lost its path to ground) or
     * {@link CollapseReason#OVERLOADED} (stress exceeded capacity). The default
     * delegates to {@link #onCollapse(NodePos)}, so a callback that only cares
     * about the position keeps working unchanged.
     *
     * <p>Override BOTH methods to tell the three failure modes apart: this one
     * fires for floating/overload collapses, while {@link #onCollapse(NodePos)}
     * fires (directly, not via this default) for damage-shattered blocks that
     * have no {@code CollapseReason}.
     */
    default void onCollapse(NodePos pos, CollapseReason reason) {
        onCollapse(pos);
    }

    /** The whole explosion (blast + cascade) is finished. */
    default void onComplete(BlastResult result) {}

    /** A callback that does nothing. */
    BlastCallback NONE = new BlastCallback() {};
}
