package dev.gesp.structural.solver;

import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.NodePos;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The result of a cascade collapse.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                        CASCADE RESULT                              │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Example: Player breaks block B                                    │
 *   │                                                                     │
 *   │       BEFORE           AFTER                                       │
 *   │                                                                     │
 *   │        [D]              💥 D collapsed (step 2)                     │
 *   │         │                                                          │
 *   │        [C]              💥 C collapsed (step 1)                     │
 *   │         │                                                          │
 *   │        [B]  ← broken    💥 (trigger - not in cascade)              │
 *   │         │                                                          │
 *   │        [A]              [A] still standing                         │
 *   │         │                │                                         │
 *   │       [GND]            [GND]                                       │
 *   │                                                                     │
 *   │                                                                     │
 *   │  CascadeResult:                                                    │
 *   │    collapsedNodes = [C, D] with their MaterialSpecs                │
 *   │    steps = 2              (how many collapse iterations)           │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public final class CascadeResult {

    private final List<CollapsedNode> collapsedNodes;
    private final int steps;
    private final boolean truncated;
    private final Set<NodePos> remainingScope;

    public CascadeResult(List<CollapsedNode> collapsedNodes, int steps) {
        this(collapsedNodes, steps, false);
    }

    public CascadeResult(List<CollapsedNode> collapsedNodes, int steps, boolean truncated) {
        this(collapsedNodes, steps, truncated, Set.of());
    }

    public CascadeResult(
            List<CollapsedNode> collapsedNodes, int steps, boolean truncated, Set<NodePos> remainingScope) {
        this.collapsedNodes = Collections.unmodifiableList(collapsedNodes);
        this.steps = steps;
        this.truncated = truncated;
        this.remainingScope = Set.copyOf(remainingScope);
    }

    /**
     * The live disturbed region left to settle when {@link #truncated()} — the
     * seed a resume should settle next instead of the whole graph (see
     * {@link CascadeEngine.SettleOutcome#remainingScope()}). Empty when the
     * cascade finished cleanly.
     */
    public Set<NodePos> remainingScope() {
        return remainingScope;
    }

    /**
     * All nodes that collapsed, with their material properties preserved (in order of collapse).
     * Does NOT include the trigger block (the one the player broke).
     */
    public List<CollapsedNode> collapsedNodes() {
        return collapsedNodes;
    }

    /**
     * All block positions that collapsed (in order of collapse).
     * Does NOT include the trigger block (the one the player broke).
     *
     * <p>This is a convenience method for code that only needs positions.
     * For full material info, use {@link #collapsedNodes()}.
     */
    public List<NodePos> collapsed() {
        return collapsedNodes.stream().map(CollapsedNode::pos).toList();
    }

    /**
     * How many cascade iterations occurred.
     */
    public int steps() {
        return steps;
    }

    /**
     * Did anything collapse beyond the trigger block?
     */
    public boolean hadCascade() {
        return !collapsedNodes.isEmpty();
    }

    /**
     * Total blocks that fell (collapsed count).
     */
    public int totalCollapsed() {
        return collapsedNodes.size();
    }

    /**
     * Whether the cascade was cut short by the step cap
     * ({@link dev.gesp.structural.config.PhysicsConfig#getMaxCascadeSteps()})
     * while overloaded blocks still remained — as opposed to settling on its own.
     *
     * <p>When this is {@code true} the graph is left mid-collapse: there may be
     * blocks that are still overloaded or now floating with no path to ground.
     * An adapter should resume the collapse on a later tick (call
     * {@link CascadeEngine#settleResult}) until it reports no work and no
     * truncation, so a giant cascade finishes over several ticks instead of
     * stranding chunks. Pure floating collapse is exempt from the cap and never
     * sets this flag on its own.
     */
    public boolean truncated() {
        return truncated;
    }

    @Override
    public String toString() {
        if (collapsedNodes.isEmpty()) {
            return "CascadeResult[no collapse]";
        }
        return "CascadeResult[" + collapsedNodes.size() + " blocks collapsed in " + steps + " steps]";
    }
}
