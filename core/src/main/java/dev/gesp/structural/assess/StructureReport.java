package dev.gesp.structural.assess;

/**
 * The result of grading a structure: a letter {@link StructureGrade} plus the
 * stress statistics it was derived from, so callers can show the underlying
 * numbers (peak/average stress, how many nodes are overloaded).
 *
 * @param grade          overall letter grade
 * @param peakStress     highest stress fraction across assessed nodes (1.0 = at capacity)
 * @param avgStress      mean stress fraction across assessed nodes
 * @param overloadedCount how many assessed nodes are over 100% capacity
 * @param assessedNodes  how many non-ground nodes were measured
 */
public record StructureReport(
        StructureGrade grade, double peakStress, double avgStress, int overloadedCount, int assessedNodes) {

    /** A report for a structure with no load-bearing nodes (trivially perfect). */
    public static StructureReport empty() {
        return new StructureReport(StructureGrade.S, 0.0, 0.0, 0, 0);
    }

    /** Peak stress as a whole-number percentage (e.g. 87 for 0.87). */
    public int peakPercent() {
        return (int) Math.round(peakStress * 100);
    }

    /** Average stress as a whole-number percentage. */
    public int avgPercent() {
        return (int) Math.round(avgStress * 100);
    }
}
