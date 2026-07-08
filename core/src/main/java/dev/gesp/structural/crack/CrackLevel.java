package dev.gesp.structural.crack;

/**
 * How cracked a node looks, in coarse buckets an adapter can map to textures or
 * particles. Bucketing (rather than a raw 0..1) keeps the visuals stable: a node
 * only changes appearance when it crosses a threshold, not every time its stress
 * wobbles by a hair.
 *
 * <pre>
 *   NONE       safe — no visible cracks
 *   HAIRLINE   working hard — faint cracks (a telegraph, not a warning)
 *   CRACKED    distressed — clear cracks
 *   CRUMBLING  about to fail — heavy cracks, last warning before collapse
 * </pre>
 */
public enum CrackLevel {
    NONE(0.0),
    HAIRLINE(0.3),
    CRACKED(0.6),
    CRUMBLING(0.9);

    private final double overlayProgress;

    CrackLevel(double overlayProgress) {
        this.overlayProgress = overlayProgress;
    }

    /**
     * A representative 0..1 progress for this bucket, for adapters that render
     * cracks on a continuous scale (e.g. Minecraft's block-break overlay stages).
     */
    public double overlayProgress() {
        return overlayProgress;
    }

    /** Is there anything to draw at all? */
    public boolean isVisible() {
        return this != NONE;
    }
}
