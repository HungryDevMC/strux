package dev.gesp.structural.scenario;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.blast.BlastOcclusion;
import dev.gesp.structural.model.NodePos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end behavioural regression net.
 *
 * <p>Each test builds a recognisable structure, does one thing to it, then locks
 * the outcome two ways:
 * <ol>
 *   <li><b>Golden snapshot</b> — the full, sorted set of destroyed/collapsed/
 *       surviving blocks is diffed against a committed file. Catches <em>any</em>
 *       silent change in what the physics does.</li>
 *   <li><b>Invariants</b> — a few hand-written assertions that document the
 *       guarantee in plain terms (the ground holds, the arch survives, the
 *       cantilever falls). Survive intentional re-tuning that snapshots flag.</li>
 * </ol>
 *
 * <p>Run {@code ./gradlew :core:test -Pupdate-snapshots} to (re)generate the
 * snapshot files after an intentional physics change, then review the git diff.
 */
@DisplayName("E2E regression: realistic structures, locked outcomes")
class ScenarioRegressionTest {

    @Test
    @DisplayName("Break a tall column at its base → everything above falls")
    void columnBreakBase() {
        ScenarioOutcome out =
                Scenario.on(Structures.column(8)).named("column-break-base").breakAt(new NodePos(0, 1, 0));

        Snapshots.assertMatches("column-break-base", out);

        // Invariants: the ground holds, nothing above the break survives.
        assertTrue(out.survives(new NodePos(0, 0, 0)), "ground must hold");
        assertEquals(7, out.collapsed().size(), "all 7 blocks above the break fall");
        assertFalse(out.survives(new NodePos(0, 8, 0)), "the top cannot float");
        assertEquals(1, out.survivorCount(), "only the ground remains");
    }

    @Test
    @DisplayName("Break a tall column mid-height → only the part above falls")
    void columnBreakMiddle() {
        ScenarioOutcome out =
                Scenario.on(Structures.column(8)).named("column-break-middle").breakAt(new NodePos(0, 4, 0));

        Snapshots.assertMatches("column-break-middle", out);

        assertEquals(4, out.collapsed().size(), "blocks y=5..8 fall");
        assertTrue(out.survives(new NodePos(0, 3, 0)), "the part below the break stands");
        assertFalse(out.survives(new NodePos(0, 5, 0)), "the part above falls");
    }

    @Test
    @DisplayName("Knock out one base block of a wall → the arch carries the load")
    void wallBreakBaseHoldsAsArch() {
        ScenarioOutcome out =
                Scenario.on(Structures.wall(7, 5)).named("wall-break-base-arch").breakAt(new NodePos(3, 1, 0));

        Snapshots.assertMatches("wall-break-base-arch", out);

        // A wall is robust: removing one base block does not bring it down.
        assertEquals(0, out.collapsed().size(), "the wall must not cascade");
        assertTrue(out.survives(new NodePos(3, 2, 0)), "the block over the gap hangs from its neighbours");
    }

    @Test
    @DisplayName("Break one pillar of a bridge → the deck cantilevers and trims back")
    void bridgeBreakPillar() {
        ScenarioOutcome out = Scenario.on(Structures.bridge(9, 3))
                .named("bridge-break-pillar")
                .breakAt(new NodePos(0, 1, 0));

        Snapshots.assertMatches("bridge-break-pillar", out);

        // The surviving pillar's footing holds; the over-reached deck collapses.
        assertTrue(out.survives(new NodePos(8, 0, 0)), "the far pillar's ground footing holds");
        assertTrue(out.collapsed().size() > 0, "losing a pillar must cause a cascade");
    }

    @Test
    @DisplayName("Blast a corner of a solid tower → crater plus whatever it undermines")
    void towerBlastCorner() {
        ScenarioOutcome out = Scenario.on(Structures.tower(3, 3, 6))
                .named("tower-blast-corner")
                .blast(new NodePos(0, 6, 0), 4.0);

        Snapshots.assertMatches("tower-blast-corner", out);

        assertTrue(out.removedCount() > 0, "the blast must remove something");
        assertTrue(out.survives(new NodePos(1, 1, 1)), "the core base stands");
    }

    @Test
    @DisplayName("Blast the roof of a house → crater plus the roof it can no longer support")
    void houseBlastRoof() {
        ScenarioOutcome out =
                Scenario.on(Structures.house(5, 4)).named("house-blast-roof").blast(new NodePos(2, 5, 2), 4.0);

        Snapshots.assertMatches("house-blast-roof", out);

        assertTrue(out.removedCount() > 0, "the blast must remove something");
        assertTrue(out.survives(new NodePos(0, 0, 0)), "the floor pad holds");
    }

    @Test
    @DisplayName("Blast above a solid tower with RAYCAST occlusion → line-of-sight shields the lower blocks")
    void towerBlastOccluded() {
        // Centre the blast ABOVE a solid tower so the rays down to the lower blocks
        // must cross the upper blocks: real line-of-sight cover. This is the scenario
        // that exercises the DDA occlusion traversal and pins its outcome.
        ScenarioOutcome out = Scenario.on(Structures.tower(5, 5, 9))
                .named("tower-blast-occluded")
                .blast(new NodePos(2, 12, 2), 8.0, BlastOcclusion.RAYCAST);

        Snapshots.assertMatches("tower-blast-occluded", out);

        assertTrue(out.removedCount() > 0, "the blast must crater the exposed top of the tower");
        assertTrue(out.survives(new NodePos(2, 1, 2)), "the sheltered core base survives the shielded blast");
    }

    @Test
    @DisplayName("Ram the base of a column → it punches through and the stack above falls")
    void columnImpactUndermines() {
        ScenarioOutcome out = Scenario.on(Structures.column(8))
                .named("column-impact-undermine")
                .impact(new NodePos(0, 1, 0), 1, 0, 0, 8.0);

        Snapshots.assertMatches("column-impact-undermine", out);

        // The hit shatters the base block; everything it was holding loses support.
        assertFalse(out.survives(new NodePos(0, 1, 0)), "the base block is punched out");
        assertEquals(7, out.collapsed().size(), "the seven blocks above fall");
        assertTrue(out.survives(new NodePos(0, 0, 0)), "the ground footing holds");
    }

    @Test
    @DisplayName("Outcomes are deterministic: the same scenario twice gives the same result")
    void outcomesAreDeterministic() {
        String first = Scenario.on(Structures.bridge(9, 3))
                .breakAt(new NodePos(0, 1, 0))
                .toSnapshotText();
        String second = Scenario.on(Structures.bridge(9, 3))
                .breakAt(new NodePos(0, 1, 0))
                .toSnapshotText();

        assertEquals(first, second, "physics must be deterministic, or snapshots would be flaky");
    }
}
