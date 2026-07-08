package dev.gesp.structural.assess;

/**
 * A letter grade for how comfortably a structure carries its own load.
 *
 * <pre>
 *   S  ── effortless. Nothing is working hard; huge margin before failure.
 *   A  ── strong. Comfortable margin everywhere.
 *   B  ── sound. Working, but well within limits.
 *   C  ── strained. Something is close to its limit — one more storey may fail.
 *   F  ── failing. At least one node is overloaded (or about to be).
 * </pre>
 *
 * <p>The grade is driven by the single most-stressed node (the peak), because a
 * structure is only as safe as its weakest member — a building can be 2% stressed
 * on average and still collapse if one column is at 99%.
 */
public enum StructureGrade {
    S,
    A,
    B,
    C,
    F;

    /**
     * Map a peak stress fraction (0.0 = idle, 1.0 = at capacity) and overload
     * count to a grade. Any overloaded node forces an F. Public so callers can
     * grade an arbitrary subset of nodes (e.g. just the region they scanned)
     * without going through {@link StructureGrader#assess}.
     */
    public static StructureGrade of(double peakStress, int overloadedCount) {
        if (overloadedCount > 0) {
            return F;
        }
        if (peakStress < 0.40) {
            return S;
        }
        if (peakStress < 0.60) {
            return A;
        }
        if (peakStress < 0.75) {
            return B;
        }
        if (peakStress < 0.90) {
            return C;
        }
        return F;
    }
}
