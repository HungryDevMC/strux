package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.assess.StructureGrade;
import dev.gesp.structural.assess.StructureGrader;
import dev.gesp.structural.assess.StructureReport;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.StressSolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Structural grading: a letter grade derived from peak/average stress after a
 * solve. Peak stress drives the grade (weakest-link), and any overloaded node
 * forces an F.
 */
@DisplayName("Grader: S/A/B/C/F from solved stress")
class StructureGraderTest {

    private final StressSolver solver = new StressSolver();

    @Test
    @DisplayName("Empty / ground-only structure grades S")
    void emptyGradesS() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        solver.solveAll(g);

        StructureReport report = StructureGrader.assess(g);
        assertEquals(StructureGrade.S, report.grade());
        assertEquals(0, report.assessedNodes(), "ground nodes are not assessed");
    }

    @Test
    @DisplayName("A lightly-loaded short column grades well (S/A)")
    void lightColumnGradesHigh() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), TestMaterials.HEAVY, false);
        g.addBlock(new NodePos(0, 2, 0), TestMaterials.HEAVY, false);
        solver.solveAll(g);

        StructureReport report = StructureGrader.assess(g);
        assertTrue(report.peakStress() < 0.6, "two heavy blocks barely stress each other");
        assertTrue(
                report.grade() == StructureGrade.S || report.grade() == StructureGrade.A,
                "expected a high grade, got " + report.grade());
    }

    @Test
    @DisplayName("An overloaded structure grades F")
    void overloadedGradesF() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        NodePos base = new NodePos(0, 1, 0);
        g.addBlock(base, TestMaterials.LIGHT, false);
        for (int y = 2; y <= 25; y++) {
            g.addBlock(new NodePos(0, y, 0), TestMaterials.LIGHT, false);
        }
        solver.solveAll(g);

        StructureReport report = StructureGrader.assess(g);
        assertTrue(report.overloadedCount() > 0, "tall light column should overload");
        assertEquals(StructureGrade.F, report.grade());
    }
}
