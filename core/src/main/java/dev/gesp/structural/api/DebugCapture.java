package dev.gesp.structural.api;

import dev.gesp.structural.model.NodePos;
import java.util.List;
import java.util.Map;

/**
 * Optional sink for the rich intermediates the solver computes and then throws
 * away. The engine wires this into {@link dev.gesp.structural.solver.StressSolver}
 * so a re-simulation consumer (the replay module's {@code DebugTrace} builder) can
 * watch <em>why</em> the physics did what it did — load routing, the
 * vertical-vs-moment split, the distance-from-ground field — without re-running the
 * solver or changing a single physics outcome.
 *
 * <p><b>Zero cost when unused.</b> Every method is a default no-op and the engine
 * skips building any of this data unless {@link #wantsDebugCapture()} returns
 * {@code true}. The production path attaches {@link #NONE} (or nothing at all), so
 * the live server never pays for capture — exactly the {@code wantsStressUpdates()}
 * pattern on {@link SolverCallback}.
 *
 * <p><b>Final-pass gating.</b> The load-flow edge stream is large (one edge per
 * supporter per node), so it is gated twice: by {@link #wantsLoadFlow()} and by the
 * solver telling the sink, per pass, whether this is the <em>settle</em> pass — the
 * last {@code solve()} of an event, the one whose numbers are the final answer. A
 * consumer keeps only edges from a pass that ran between {@link #beginPass(boolean)}
 * with {@code finalPass == true} and the matching {@link #endPass()}.
 *
 * <p>Core stays game-type-free: positions are {@link NodePos}, everything else is a
 * primitive.
 */
public interface DebugCapture {

    /**
     * Whether the solver should drive this sink at all. Default {@code false} so an
     * absent or {@link #NONE} sink costs nothing. The replay builder overrides it to
     * {@code true} when {@code withDebugTrace(true)} is set.
     */
    default boolean wantsDebugCapture() {
        return false;
    }

    /**
     * Whether the (large) load-flow edge stream should be emitted. Separate from
     * {@link #wantsDebugCapture()} so a consumer can take the cheap fields (stress
     * split, ground distances) without the per-edge firehose. Default {@code false}.
     */
    default boolean wantsLoadFlow() {
        return false;
    }

    /**
     * Whether the moment-arm geometry should be emitted (the cantilever view: each
     * detected arm's members, mass, reach, beam flag, section depth and anchor).
     * Separate from {@link #wantsDebugCapture()} so a consumer can take the cheap
     * fields without the arm enumeration. Default {@code false}. Like load-flow, arm
     * capture is gated to the settle (final) pass, so the bound is one arm set per
     * event, not per iteration.
     */
    default boolean wantsMomentArms() {
        return false;
    }

    /**
     * Marks the start of one {@code solve()} pass. {@code finalPass} is {@code true}
     * only for the settle pass whose results become the event's final stress field —
     * the pass whose load-flow edges are worth keeping.
     */
    default void beginPass(boolean finalPass) {}

    /** Marks the end of the current {@code solve()} pass. */
    default void endPass() {}

    /**
     * The distance-from-ground BFS result for this pass: {@code pos → distance}, with
     * {@link Integer#MAX_VALUE} marking a floater (no path to ground).
     */
    default void onGroundDistances(Map<NodePos, Integer> distances) {}

    /**
     * The two-pass stress split for one settled node. {@code total = vertical +
     * moment}; {@code percent} is {@code total / effectiveMaxLoad}. Fired once per
     * non-ground node at the end of a pass.
     */
    default void onStressComponents(
            NodePos pos, double verticalStress, double momentStress, double effectiveMaxLoad, double percent) {}

    /**
     * One supporter edge in the load-flow graph: {@code from} (higher node) sends
     * {@code shareFraction} of its vertical load down to its strictly-closer
     * supporter {@code to}; {@code absoluteLoad} is that share in load units. Only
     * fired during a {@code finalPass} when {@link #wantsLoadFlow()} is on.
     */
    default void onLoadFlowEdge(NodePos from, NodePos to, double shareFraction, double absoluteLoad) {}

    /**
     * One detected moment arm (a true cantilever the moment pass charged to its
     * anchor). {@code members} are the arm's node positions (the connected component
     * above the anchor's contour); {@code totalMass} is the mass the index charged
     * (already reduced when {@code isBeam}); {@code reach} is the member count;
     * {@code isBeam} is {@code true} when the arm has a second support beyond the
     * anchor; {@code sectionDepth} is the vertical thickness the moment was divided
     * by ({@code d²}); {@code anchor} is the node the arm hangs off. Only fired during
     * a {@code finalPass} when {@link #wantsMomentArms()} is on — read-only reporting
     * of already-computed arm state, never a physics change.
     */
    default void onMomentArm(
            List<NodePos> members, double totalMass, int reach, boolean isBeam, int sectionDepth, NodePos anchor) {}

    /** A no-op sink. The production default — capture is entirely off. */
    DebugCapture NONE = new DebugCapture() {};
}
