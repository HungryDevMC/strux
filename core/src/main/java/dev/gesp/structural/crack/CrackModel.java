package dev.gesp.structural.crack;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.model.Node;

/**
 * Maps a node's live state to a {@link CrackLevel} for purely visual cracking —
 * no physics, no solver changes. A node looks cracked for either of two honest
 * reasons, and we take the worse of the two:
 *
 * <pre>
 *   distress = max( stressPercent , damage )
 *
 *     stressPercent  how close the node is to failing under its CURRENT load
 *                    (a heavily-loaded wall is visibly working). Clamped to 1.
 *     damage         accumulated micro-fracture from blasts and impacts, which
 *                    persists even when the node is unloaded — it really IS
 *                    cracked, so it should look it.
 * </pre>
 *
 * <p>Crack <em>propagation</em> needs no algorithm: during a cascade the solver
 * re-runs and load redistributes onto neighbours, so their {@code stressPercent}
 * climbs toward the failure and an adapter re-rendering each tick sees the cracks
 * spread on their own. This is the physically correct origin of crack spreading —
 * load redistribution — visualised frame by frame.
 *
 * <p>Thresholds are tunable on {@link PhysicsConfig}; the model is otherwise
 * stateless and cheap (a couple of getter reads per node).
 */
public final class CrackModel {

    private final PhysicsConfig config;

    public CrackModel(PhysicsConfig config) {
        this.config = config;
    }

    /**
     * The 0..1 distress scalar: the worse of current stress and accumulated
     * damage. Ground is never distressed. Delegates to {@link Node#distress()},
     * the single canonical definition, so cracking can never drift from the rest
     * of the engine's idea of "in trouble".
     */
    public static double distress(Node node) {
        return node.distress();
    }

    /** Bucket a node's distress into a {@link CrackLevel} using the configured thresholds. */
    public CrackLevel crackLevel(Node node) {
        double d = node.distress();
        if (d >= config.getCrackCrumblingThreshold()) {
            return CrackLevel.CRUMBLING;
        }
        if (d >= config.getCrackCrackedThreshold()) {
            return CrackLevel.CRACKED;
        }
        if (d >= config.getCrackHairlineThreshold()) {
            return CrackLevel.HAIRLINE;
        }
        return CrackLevel.NONE;
    }
}
