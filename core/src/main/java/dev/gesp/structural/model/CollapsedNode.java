package dev.gesp.structural.model;

/**
 * Represents a node that has collapsed, preserving its position and material properties.
 *
 * <p>When a node collapses, the core engine captures its state before removal so that
 * adapters can:
 * <ul>
 *   <li>Return the correct items to players</li>
 *   <li>Spawn appropriate rubble/falling blocks</li>
 *   <li>Log what materials were destroyed</li>
 * </ul>
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                      COLLAPSED NODE                                │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  When [STONE] at (10, 65, 20) collapses:                           │
 *   │                                                                     │
 *   │    CollapsedNode {                                                 │
 *   │      pos  = (10, 65, 20)                                           │
 *   │      spec = MaterialSpec(mass=2.0, maxLoad=30.0, blastRes=1.5)     │
 *   │    }                                                               │
 *   │                                                                     │
 *   │  Adapter can then:                                                 │
 *   │    • Spawn FallingBlock at y=65                                    │
 *   │    • Give player 1x STONE item                                     │
 *   │    • Log "STONE collapsed at 10,65,20"                             │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * @param pos              the position where the node was before collapsing
 * @param spec             the material properties of the collapsed node
 * @param stressAtCollapse how loaded the node was when it failed, as a fraction of its
 *                         capacity ({@code 1.0} = exactly at capacity, {@code 1.5} = 150%
 *                         loaded). Meaningful only for an {@code OVERLOADED} collapse; a
 *                         {@code FLOATING} collapse carries {@code 0.0} because it failed for
 *                         lack of support, not load. Adapters use this to spot a "near miss" —
 *                         a block that was barely holding (just over {@code 1.0}) when it went.
 */
public record CollapsedNode(NodePos pos, MaterialSpec spec, double stressAtCollapse) {

    /**
     * Create a CollapsedNode with no recorded collapse stress (e.g. a floating collapse, or a
     * synthetic node an adapter builds when the real node is already gone). Keeps existing
     * call sites compiling.
     */
    public CollapsedNode(NodePos pos, MaterialSpec spec) {
        this(pos, spec, 0.0);
    }

    /**
     * Create a CollapsedNode from an existing Node, with no recorded collapse stress. Use
     * {@link #fromOverloaded(Node)} on the overloaded-collapse path instead to capture how
     * loaded the node was when it failed.
     */
    public static CollapsedNode from(Node node) {
        return new CollapsedNode(node.pos(), node.spec(), 0.0);
    }

    /**
     * Create a CollapsedNode from a node that failed under load, capturing its
     * {@link Node#stressPercent()} at the moment of collapse so adapters can tell a
     * barely-over-capacity "near miss" from a block that was nowhere near holding.
     */
    public static CollapsedNode fromOverloaded(Node node) {
        return new CollapsedNode(node.pos(), node.spec(), node.stressPercent());
    }

    /**
     * The Y coordinate where this node was (useful for fall height calculations).
     */
    public int y() {
        return pos.y();
    }

    /**
     * The blast resistance of the material (higher = more likely to survive as rubble).
     */
    public double blastResistance() {
        return spec.blastResistance();
    }

    /**
     * The mass of the collapsed node.
     */
    public double mass() {
        return spec.mass();
    }
}
