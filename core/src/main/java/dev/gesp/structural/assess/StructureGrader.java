package dev.gesp.structural.assess;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.Node;

/**
 * Grades a structure from the stress already computed on its nodes.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                       STRUCTURE GRADER                             │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │   solve the graph  ──►  read each node's stress%  ──►  S/A/B/C/F    │
 *   │                                                                     │
 *   │   Peak stress drives the grade (weakest-link), with average and    │
 *   │   overload count reported alongside for context.                   │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>This reads stress as-is; it does not solve. Call the solver first so the
 * node stress is current, then grade. Ground nodes are ignored — they never
 * carry stress and would dilute the average.
 */
public final class StructureGrader {

    /**
     * A fully-damaged node reports infinite stress%. Treat that as 200% for the
     * numeric statistics so peak/average stay finite and comparable.
     */
    private static final double DESTROYED_STRESS = 2.0;

    private StructureGrader() {
        // Utility class
    }

    /**
     * Grade the structure from its current (already-solved) node stress.
     */
    public static StructureReport assess(StructureGraph graph) {
        double peak = 0.0;
        double sum = 0.0;
        int overloaded = 0;
        int count = 0;

        for (Node node : graph.getAllNodes()) {
            if (node.isGrounded()) {
                continue; // ground never carries stress
            }
            double percent = node.stressPercent();
            if (!Double.isFinite(percent)) {
                percent = DESTROYED_STRESS;
            }
            peak = Math.max(peak, percent);
            sum += percent;
            count++;
            if (node.isOverloaded()) {
                overloaded++;
            }
        }

        if (count == 0) {
            return StructureReport.empty();
        }

        double avg = sum / count;
        StructureGrade grade = StructureGrade.of(peak, overloaded);
        return new StructureReport(grade, peak, avg, overloaded, count);
    }
}
