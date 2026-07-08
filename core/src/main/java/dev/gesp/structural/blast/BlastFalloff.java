package dev.gesp.structural.blast;

/**
 * How blast intensity drops from the center (ratio 0) to the edge (ratio 1).
 */
public enum BlastFalloff {

    /** Straight line: full at center, zero at the edge. */
    LINEAR,

    /** Stays strong near the center, drops off sharply toward the edge. */
    QUADRATIC,

    /** No falloff — equal intensity everywhere in range. */
    FLAT;

    /**
     * Intensity multiplier in [0, 1] for a node whose distance is {@code ratio}
     * of the blast radius (0 = center, 1 = edge).
     */
    public double factor(double ratio) {
        double r = Math.max(0.0, Math.min(1.0, ratio));
        return switch (this) {
            case LINEAR -> 1.0 - r;
            case QUADRATIC -> 1.0 - r * r;
            case FLAT -> 1.0;
        };
    }
}
