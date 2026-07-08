package dev.gesp.structural.api;

import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.NodePos;
import java.util.List;
import java.util.Map;

/**
 * Callback for cascade events. Implement this to react to structural changes.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                      CASCADE EVENT FLOW                            │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  1. Player breaks a block                                          │
 *   │     ↓                                                              │
 *   │  2. onStressUpdated() ← stress recalculated for affected blocks    │
 *   │     ↓                                                              │
 *   │  3. If any block is overloaded:                                    │
 *   │     ↓                                                              │
 *   │  4. onCascadeStep() ← that block collapses (with material info)    │
 *   │     ↓                                                              │
 *   │  5. Repeat from step 2 until no more overloaded blocks             │
 *   │     ↓                                                              │
 *   │  6. onCascadeComplete() ← all done, here's what fell               │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public interface SolverCallback {

    /**
     * Called when stress values have been recalculated.
     *
     * @param stressMap position → stress percent (0.0 to 1.0+)
     */
    void onStressUpdated(Map<NodePos, Double> stressMap);

    /**
     * Called when a block collapses during a cascade.
     *
     * @param collapsed the node that just collapsed (with position and material info)
     * @param stepNumber which step of the cascade (1, 2, 3...)
     * @param reason why the block is collapsing (floating or overloaded)
     */
    void onCascadeStep(CollapsedNode collapsed, int stepNumber, CollapseReason reason);

    /**
     * Called when a block collapses during a cascade that a known actor triggered.
     *
     * <p>This is the attribution-carrying form of {@link #onCascadeStep(CollapsedNode, int,
     * CollapseReason)}. When a player's action (a break, a place, a blast) sets off a cascade, the
     * blocks that fall as a consequence are <em>that player's</em> doing — so analytics like
     * "blocks collapsed via cascades, per actor" need the trigger's actor on every cascade frame,
     * not just on the first block. The driver that knows which recorded event is being applied
     * (e.g. the replay engine) passes its {@code actorId} here.
     *
     * <p>{@code actorId} is an opaque {@link String} (a player UUID, say) — never a game type, so
     * the core stays unit-agnostic. It is {@code null} when the cascade has no known actor (an
     * unattributed event, or a live FX call that never bothers with it).
     *
     * <p>The default delegates to the three-arg form, so existing callbacks that don't care about
     * attribution keep working unchanged; only listeners that build per-actor analytics need to
     * override this.
     *
     * @param collapsed the node that just collapsed (with position and material info)
     * @param stepNumber which step of the cascade (1, 2, 3...)
     * @param reason why the block is collapsing (floating or overloaded)
     * @param actorId opaque id of who triggered the cascade, or {@code null} if unattributed
     */
    default void onCascadeStep(CollapsedNode collapsed, int stepNumber, CollapseReason reason, String actorId) {
        onCascadeStep(collapsed, stepNumber, reason);
    }

    /**
     * Called when the cascade is complete.
     *
     * @param allCollapsed all nodes that collapsed with their material info (in order)
     */
    void onCascadeComplete(List<CollapsedNode> allCollapsed);

    /**
     * Whether this callback actually consumes {@link #onStressUpdated}. Building
     * the per-step stress map is an allocation the engine should only pay for
     * when someone is listening — default {@code false} so the common case (FX
     * callbacks that only care about collapse events, and {@link #NONE}) costs
     * nothing. Override to return {@code true} if you read the stress map.
     */
    default boolean wantsStressUpdates() {
        return false;
    }

    /**
     * Whether this callback wants {@link #onStressDelta} fired. Mirrors
     * {@link #wantsStressUpdates()}: building the load-ratio map is only paid for
     * when a listener (the recorder, when {@code recording.captureStress} is on)
     * asks for it. Default {@code false} so it costs nothing when off.
     */
    default boolean wantsStressDeltas() {
        return false;
    }

    /**
     * Called during a settle pass with the current load ratio (0.0 to 1.0+) of every
     * block the engine just re-solved. This is the same data {@link #onStressUpdated}
     * carries, but gated by {@link #wantsStressDeltas()} so a recorder can collect the
     * blocks that crossed a stress bucket boundary <em>without</em> turning on the FX
     * stress stream (which is for live shake/crack visuals).
     *
     * <p>The default is a no-op, so existing callbacks are unaffected; only a listener
     * that builds a {@code StressDelta} payload overrides it.
     *
     * @param loadRatios position → load ratio for the just-resolved blocks
     */
    default void onStressDelta(Map<NodePos, Double> loadRatios) {
        // Intentionally empty - zero cost unless a recorder opts in.
    }

    /**
     * A no-op callback that does nothing. Useful for testing.
     */
    SolverCallback NONE = new SolverCallback() {
        @Override
        public void onStressUpdated(Map<NodePos, Double> stressMap) {}

        @Override
        public void onCascadeStep(CollapsedNode collapsed, int stepNumber, CollapseReason reason) {}

        @Override
        public void onCascadeComplete(List<CollapsedNode> allCollapsed) {}
    };
}
