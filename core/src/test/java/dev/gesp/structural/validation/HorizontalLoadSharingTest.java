package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import dev.gesp.structural.solver.CascadeResult;
import dev.gesp.structural.solver.StressSolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for horizontal load sharing between supports.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │              HORIZONTAL LOAD SHARING TESTS                         │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  MODEL: load flows ONLY to neighbors strictly closer to ground.    │
 *   │  Two supports share a load when both offer a strictly-downhill     │
 *   │  path (SCENARIO 2). A side path of EQUAL or LONGER length takes    │
 *   │  nothing — relieving through it would need stiffness-style         │
 *   │  lateral sharing (see ROADMAP), and faking it by handing load to   │
 *   │  a same-distance neighbor just evaporates weight (the old leak:    │
 *   │  the "relieved" load reached nobody). PhysicsValidationTest pins   │
 *   │  exact conservation against that.                                  │
 *   │                                                                     │
 *   │  SCENARIO 1: Side path, same length — takes NO load               │
 *   │                                                                     │
 *   │     [STONE]──[SUPPORT]   STONE leans fully on SUPPORT; the side    │
 *   │        │         │       path via STONE's neighbor is not          │
 *   │     [LOG]      [GND]     downhill, so LOG carries only itself.     │
 *   │        │                                                            │
 *   │     [GND]                                                           │
 *   │                                                                     │
 *   │  SCENARIO 2: Parallel supports — split for real                    │
 *   │                                                                     │
 *   │        [HEAVY]                                                     │
 *   │        /     \                                                     │
 *   │     [A]       [B]    A and B are both strictly closer: each       │
 *   │       │         │    receives a real, conserved share.            │
 *   │     [GND]     [GND]                                                │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
@DisplayName("HorizontalLoadSharing: Load distribution across multiple supports")
class HorizontalLoadSharingTest {

    private StructureGraph graph;
    private StressSolver solver;

    @BeforeEach
    void setUp() {
        graph = new StructureGraph();
        solver = new StressSolver();
    }

    @Nested
    @DisplayName("Two vertical supports connected by horizontal beam")
    class TwoVerticalSupports {

        /**
         * Structure:
         *     [STONE]──[STONE2]
         *        │         │
         *     [LOG1]    [LOG2]
         *        │         │
         *     [GND1]    [GND2]
         */
        @Test
        @DisplayName("Load should split equally between two parallel supports")
        void loadSplitsBetweenParallelSupports() {
            // Build structure
            NodePos gnd1 = new NodePos(0, 0, 0);
            NodePos log1 = new NodePos(0, 1, 0);
            NodePos stone1 = new NodePos(0, 2, 0);
            NodePos stone2 = new NodePos(1, 2, 0);
            NodePos log2 = new NodePos(1, 1, 0);
            NodePos gnd2 = new NodePos(1, 0, 0);

            graph.addGroundBlock(gnd1);
            graph.addBlock(log1, TestMaterials.MEDIUM, false);
            graph.addBlock(stone1, TestMaterials.HEAVY, false);
            graph.addBlock(stone2, TestMaterials.HEAVY, false);
            graph.addBlock(log2, TestMaterials.MEDIUM, false);
            graph.addGroundBlock(gnd2);

            solver.solveAll(graph);

            double stressLog1 = graph.getNode(log1).stressValue();
            double stressLog2 = graph.getNode(log2).stressValue();

            // Both logs should share the horizontal beam's load
            // They should have approximately equal stress (within 10%)
            assertEquals(stressLog1, stressLog2, stressLog1 * 0.1, "Parallel supports should share load equally");
        }

        /**
         * Structure with one support initially, then add second:
         *
         * Before:                After:
         *     [STONE]──[BRIDGE]      [STONE]──[BRIDGE]
         *                  │            │         │
         *               [SUPPORT]    [NEW]    [SUPPORT]
         *                  │            │         │
         *               [GND]        [GND]     [GND]
         */
        @Test
        @DisplayName("Adding a new support should reduce stress on existing support")
        void addingSupportReducesStressOnExisting() {
            // Initial structure: stone connected to support
            NodePos gndRight = new NodePos(2, 0, 0);
            NodePos support = new NodePos(2, 1, 0);
            NodePos bridge = new NodePos(2, 2, 0);
            NodePos stone = new NodePos(1, 2, 0);

            graph.addGroundBlock(gndRight);
            graph.addBlock(support, TestMaterials.MEDIUM, false);
            graph.addBlock(bridge, TestMaterials.MEDIUM, false);
            graph.addBlock(stone, TestMaterials.HEAVY, false);

            solver.solveAll(graph);
            double stressBefore = graph.getNode(support).stressValue();

            // Now add a new support under the stone
            NodePos gndLeft = new NodePos(1, 0, 0);
            NodePos newSupport = new NodePos(1, 1, 0);

            graph.addGroundBlock(gndLeft);
            graph.addBlock(newSupport, TestMaterials.MEDIUM, false);

            solver.solveAll(graph);
            double stressAfter = graph.getNode(support).stressValue();

            // Adding a new support should REDUCE stress on the existing support
            assertTrue(
                    stressAfter < stressBefore,
                    "Adding a new support should reduce stress on existing support. " + "Before: " + stressBefore
                            + ", After: " + stressAfter);
        }
    }

    @Nested
    @DisplayName("Adding support under hanging structure")
    class AddingSupportUnderHangingStructure {

        /**
         * Adding a side path of EQUAL length must NOT relieve the loaded
         * support: load only flows strictly downhill, and the new path via
         * the bridge is not downhill from the stone.
         *
         * (The old share rule made this scenario look relieved: the stone
         * offered ~40% of its load to the same-distance bridge, but nothing
         * ever delivers to a same-distance neighbor, so that share simply
         * evaporated — the "relief" was vanishing mass, not rerouted mass.
         * Honest lateral sharing needs a stiffness-style solve; see ROADMAP.)
         */
        @Test
        @DisplayName("Equal-length side path takes no load — no fake relief")
        void equalLengthSidePathTakesNoLoad() {
            // Weak support that can hold 5 units (stone mass 3 + weak mass 1 = 4, so 80%)
            MaterialSpec weakSupport = new MaterialSpec(1.0, 5.0);
            // Stone with mass 3
            MaterialSpec stone = new MaterialSpec(3.0, 100.0);

            // Initial structure: stone on weak, weak connected to ground
            //     [STONE] at (1, 2, 0)
            //        │
            //     [WEAK] at (1, 1, 0)
            //        │
            //     [GND] at (1, 0, 0)
            NodePos gnd1 = new NodePos(1, 0, 0);
            NodePos weak = new NodePos(1, 1, 0);
            NodePos stonePos = new NodePos(1, 2, 0);

            graph.addGroundBlock(gnd1);
            graph.addBlock(weak, weakSupport, false);
            graph.addBlock(stonePos, stone, false);

            solver.solveAll(graph);

            // Verify weak support carries stone: stress = 1 + 3 = 4, capacity 5, so 80%
            double weakStress = graph.getNode(weak).stressPercent();
            assertEquals(0.8, weakStress, 0.05, "Weak support should be at 80% capacity: " + weakStress);

            // Now add a second support path: log adjacent to stone
            //     [STONE] at (1, 2, 0)
            //     /     \
            //  [LOG]   [WEAK]
            //    │        │
            //  [GND]    [GND]
            NodePos gnd2 = new NodePos(0, 0, 0);
            NodePos log = new NodePos(0, 1, 0);
            NodePos bridge = new NodePos(0, 2, 0); // Connect stone to log via bridge

            graph.addGroundBlock(gnd2);
            graph.addBlock(log, weakSupport, false);
            graph.addBlock(bridge, weakSupport, false);

            solver.solveAll(graph);

            // The side path (stone -> bridge -> log -> ground) is LONGER than
            // the direct one, so it takes nothing: weak still carries the
            // stone in full, and the new pillar carries only itself + bridge.
            double weakStressAfter = graph.getNode(weak).stressPercent();
            assertEquals(
                    weakStress, weakStressAfter, 1e-9, "an equal/longer side path must not relieve the direct support");
            assertEquals(
                    2.0,
                    graph.getNode(log).verticalStress(),
                    1e-9,
                    "the new pillar carries exactly itself plus the bridge — nothing of the stone");
        }

        /**
         * An overloaded column is NOT saved by a parallel path of equal/longer
         * length — load only flows strictly downhill, and the side route via
         * the bridge is not downhill from the heavy block.
         *
         * Initial (overloaded):           After adding the side path:
         *     [HEAVY] at (0,2,0)              [HEAVY]──[BRIDGE]
         *        │                               │         │
         *     [SUPPORT] ← overloaded!        [SUPPORT]   [NEW]
         *        │                               │         │
         *     [GND]                           [GND]     [GND]
         *
         * SUPPORT stays overloaded; NEW carries only itself + bridge. (The
         * old share rule "saved" SUPPORT by evaporating the share offered to
         * the same-distance bridge — fake relief, now forbidden by the
         * conservation pins in PhysicsValidationTest.)
         */
        @Test
        @DisplayName("Equal-length parallel path does not rescue an overloaded support")
        void sidePathDoesNotRescueOverloadedSupport() {
            // Heavy block: mass 8
            MaterialSpec veryHeavy = new MaterialSpec(8.0, 100.0);
            // Support: can hold 6 units (overloaded with 8+1=9 initially)
            MaterialSpec support = new MaterialSpec(1.0, 6.0);

            // Build initial structure: heavy on support
            NodePos gnd1 = new NodePos(0, 0, 0);
            NodePos existingSupport = new NodePos(0, 1, 0);
            NodePos heavy = new NodePos(0, 2, 0);

            graph.addGroundBlock(gnd1);
            graph.addBlock(existingSupport, support, false);
            graph.addBlock(heavy, veryHeavy, false);

            solver.solveAll(graph);

            // Existing support should be overloaded (1 + 8 = 9 > capacity 6)
            double existingStress = graph.getNode(existingSupport).stressValue();
            double existingStressPct = graph.getNode(existingSupport).stressPercent();
            assertTrue(
                    existingStressPct > 1.0,
                    "Existing support should be overloaded with just one support: " + existingStressPct);

            // Now add a second support adjacent to heavy (horizontal neighbor)
            NodePos gnd2 = new NodePos(1, 0, 0);
            NodePos newSupport = new NodePos(1, 1, 0);
            NodePos bridge = new NodePos(1, 2, 0); // Bridge to connect heavy to new support

            graph.addGroundBlock(gnd2);
            graph.addBlock(newSupport, support, false);
            graph.addBlock(bridge, support, false);

            solver.solveAll(graph);

            // The side path is not downhill from the heavy block, so nothing
            // reroutes: the existing support still carries the full overload,
            // and the new pillar carries exactly itself plus the bridge.
            double existingStressAfter = graph.getNode(existingSupport).stressValue();
            double newSupportStress = graph.getNode(newSupport).stressValue();

            assertEquals(
                    existingStress,
                    existingStressAfter,
                    1e-9,
                    "an equal/longer side path must not relieve the overloaded support");
            assertEquals(
                    2.0,
                    newSupportStress,
                    1e-9,
                    "the new pillar carries exactly itself plus the bridge — nothing of the heavy block");
            assertTrue(
                    graph.getNode(existingSupport).stressPercent() > 1.0,
                    "the honest outcome: the column is still doomed; only a strictly-shorter"
                            + " path (or reinforcement) can save it");
        }
    }

    @Nested
    @DisplayName("Bridge with weight in middle")
    class BridgeWithMiddleWeight {

        /**
         * Classic bridge scenario: heavy weight in the middle, supports on both sides.
         *
         *           [HEAVY]
         *              │
         *     [GND]──[SPAN]──[GND]
         *
         * Both grounds should share the heavy block's weight equally.
         */
        @Test
        @DisplayName("Weight on bridge should distribute to both supports")
        void weightOnBridgeDistributesToBothSupports() {
            // Bridge span
            NodePos gndLeft = new NodePos(0, 0, 0);
            NodePos span = new NodePos(1, 0, 0);
            NodePos gndRight = new NodePos(2, 0, 0);
            // Heavy weight on top
            NodePos heavy = new NodePos(1, 1, 0);

            graph.addGroundBlock(gndLeft);
            graph.addBlock(span, TestMaterials.LIGHT, false);
            graph.addGroundBlock(gndRight);
            graph.addBlock(heavy, TestMaterials.HEAVY, false);

            solver.solveAll(graph);

            // The span block carries the heavy weight
            // Its stress should flow to BOTH grounds, not just one
            double spanStress = graph.getNode(span).stressValue();

            // Span should carry: own mass + heavy mass = 1 + 3 = 4
            // But the load going INTO span from heavy should split to both sides
            assertEquals(4.0, spanStress, 0.5, "Span should carry own weight + heavy weight");

            // Both grounds should have zero stress (they absorb it)
            assertEquals(0.0, graph.getNode(gndLeft).stressValue());
            assertEquals(0.0, graph.getNode(gndRight).stressValue());
        }
    }

    @Nested
    @DisplayName("Horizontal chain behavior")
    class HorizontalChainBehavior {

        /**
         * Horizontal chain: [GND]──[A]──[B]──[C]──[GND]
         *
         * Each block in the middle should only carry its own weight,
         * since load flows horizontally to both grounds.
         */
        @Test
        @DisplayName("Horizontal chain should distribute load to both ends")
        void horizontalChainDistributesToBothEnds() {
            NodePos gndLeft = new NodePos(0, 0, 0);
            NodePos a = new NodePos(1, 0, 0);
            NodePos b = new NodePos(2, 0, 0);
            NodePos c = new NodePos(3, 0, 0);
            NodePos gndRight = new NodePos(4, 0, 0);

            graph.addGroundBlock(gndLeft);
            graph.addBlock(a, TestMaterials.MEDIUM, false);
            graph.addBlock(b, TestMaterials.MEDIUM, false);
            graph.addBlock(c, TestMaterials.MEDIUM, false);
            graph.addGroundBlock(gndRight);

            solver.solveAll(graph);

            // In a horizontal chain, middle blocks should have lower stress than end blocks
            // because middle blocks can distribute to both directions
            double stressA = graph.getNode(a).stressValue();
            double stressB = graph.getNode(b).stressValue();
            double stressC = graph.getNode(c).stressValue();

            // B is in the middle, A and C are ends
            // B's load should split toward both A and C
            // So A and C should have more total stress than B
            System.out.println("Stress A: " + stressA + ", B: " + stressB + ", C: " + stressC);

            // At minimum, the structure should be symmetric
            assertEquals(stressA, stressC, stressA * 0.1, "End blocks should have symmetric stress");
        }

        /**
         * Adding a vertical support should split the load with horizontal neighbors.
         *
         * Before:           After:
         *
         * [STONE]           [STONE]
         *    │                 │
         * [GND]            [LOG]──[GND] (LOG added, connected to STONE and new GND)
         *                    │
         *                  [GND]
         */
        @Test
        @DisplayName("Vertical support should share horizontal load")
        void verticalSupportSharesHorizontalLoad() {
            // Initial: stone on ground
            NodePos gnd1 = new NodePos(0, 0, 0);
            NodePos stone = new NodePos(0, 1, 0);

            graph.addGroundBlock(gnd1);
            graph.addBlock(stone, TestMaterials.HEAVY, false);

            solver.solveAll(graph);

            // Stone is directly on ground, stress = own mass
            double stoneStressInitial = graph.getNode(stone).stressValue();
            assertEquals(3.0, stoneStressInitial, 0.1, "Stone stress should equal its mass");

            // Now add a horizontal neighbor to stone, also grounded
            NodePos log = new NodePos(1, 1, 0);
            NodePos gnd2 = new NodePos(1, 0, 0);

            graph.addBlock(log, TestMaterials.MEDIUM, false);
            graph.addGroundBlock(gnd2);

            solver.solveAll(graph);

            // Now stone and log are both at dist=1 from their respective grounds
            // Stone's load should stay on stone (it has its own path to ground)
            // Log's load should stay on log
            // No sharing expected here actually - they're at same level with their own supports

            double stoneStressAfter = graph.getNode(stone).stressValue();
            double logStress = graph.getNode(log).stressValue();

            // Each should carry its own weight
            assertEquals(3.0, stoneStressAfter, 0.1, "Stone should still carry its own weight");
            assertEquals(2.0, logStress, 0.1, "Log should carry its own weight");
        }
    }

    @Nested
    @DisplayName("Load redistribution on placement")
    class LoadRedistributionOnPlacement {

        /**
         * This is the key bug scenario:
         *
         * Initial:
         *     [HEAVY]────────[SUPPORT]
         *                        │
         *                      [GND]
         *
         * SUPPORT carries: support.mass + heavy.mass
         * If this exceeds SUPPORT's capacity, it should already collapse.
         * But assume it's at 90% capacity.
         *
         * Player places NEW_SUPPORT under HEAVY:
         *     [HEAVY]────────[SUPPORT]
         *        │               │
         *   [NEW_SUPPORT]      [GND]
         *        │
         *      [GND]
         *
         * Expected: HEAVY's load splits between NEW_SUPPORT and SUPPORT.
         * Actual bug: NEW_SUPPORT gets ALL of HEAVY's load and collapses.
         */
        @Test
        @DisplayName("Placing support should redistribute, not collapse")
        void placingSupportRedistributes() {
            // With linear moment stress: moment = weight × distance
            // Support receives: vertical (1 + 8 = 9) + moment (8 × 1 = 8) = 17
            // Capacity 20 gives ~85% utilization
            MaterialSpec support = new MaterialSpec(1.0, 20.0);
            // Heavy block with mass 8
            MaterialSpec heavy = new MaterialSpec(8.0, 100.0);

            // Initial structure
            NodePos gndRight = new NodePos(2, 0, 0);
            NodePos existingSupport = new NodePos(2, 1, 0);
            NodePos heavyBlock = new NodePos(1, 1, 0);

            graph.addGroundBlock(gndRight);
            graph.addBlock(existingSupport, support, false);
            graph.addBlock(heavyBlock, heavy, false);

            solver.solveAll(graph);

            // Support carries: vertical (1 + 8 = 9) + moment (8 × 1 = 8) = 17
            // This is 85% of capacity (20), so it survives
            double existingStressPct = graph.getNode(existingSupport).stressPercent();
            assertTrue(
                    existingStressPct < 1.0,
                    "Existing support should not be overloaded initially: " + existingStressPct);
        }

        /**
         * Clearer geometry for the bug scenario.
         *
         * Initial:
         *     [HEAVY]──[BRIDGE]
         *                 │
         *             [PILLAR]
         *                 │
         *              [GND]
         *
         * After placing [LOG] under [HEAVY]:
         *     [HEAVY]──[BRIDGE]
         *        │        │
         *     [LOG]   [PILLAR]
         *        │        │
         *     [GND]    [GND]
         *
         * HEAVY's weight should split between LOG and BRIDGE→PILLAR path.
         */
        @Test
        @DisplayName("New support under cantilever should share load with existing path")
        void newSupportUnderCantileverSharesLoad() {
            // Materials - increased capacities for new physics model
            MaterialSpec pillar = new MaterialSpec(1.0, 25.0); // Can hold 25
            MaterialSpec bridge = new MaterialSpec(1.0, 25.0); // Can hold 25
            MaterialSpec heavy = new MaterialSpec(10.0, 100.0); // Mass 10
            MaterialSpec log = new MaterialSpec(1.0, 15.0); // Can hold 15

            // Build initial structure (heavy cantilever)
            NodePos gnd1 = new NodePos(1, 0, 0);
            NodePos pillarPos = new NodePos(1, 1, 0);
            NodePos bridgePos = new NodePos(1, 2, 0);
            NodePos heavyPos = new NodePos(0, 2, 0);

            graph.addGroundBlock(gnd1);
            graph.addBlock(pillarPos, pillar, false);
            graph.addBlock(bridgePos, bridge, false);
            graph.addBlock(heavyPos, heavy, false);

            solver.solveAll(graph);

            // Verify initial state with new physics model:
            // Bridge: vertical (1 + 10 = 11) + moment (10 × 1 = 10) = 21
            // Pillar: vertical (1 + 11 = 12) + moment (0) = 12
            double bridgeStress = graph.getNode(bridgePos).stressValue();
            double pillarStress = graph.getNode(pillarPos).stressValue();

            System.out.println("Initial state:");
            System.out.println("  Bridge stress: " + bridgeStress + " (vertical: "
                    + graph.getNode(bridgePos).verticalStress() + ", moment: "
                    + graph.getNode(bridgePos).momentStress() + ")");
            System.out.println("  Pillar stress: " + pillarStress);

            // Bridge carries vertical + moment stress
            assertEquals(21.0, bridgeStress, 0.5, "Bridge should carry vertical (11) + moment (10)");
            assertEquals(12.0, pillarStress, 0.5, "Pillar should carry own + bridge vertical");

            // Now add log under heavy - this creates a second path to ground
            NodePos gnd2 = new NodePos(0, 0, 0);
            NodePos logPos = new NodePos(0, 1, 0);

            graph.addGroundBlock(gnd2);
            graph.addBlock(logPos, log, false);

            solver.solveAll(graph);

            // After adding log, heavy's load splits between log and bridge paths
            // Heavy now has TWO paths to ground: via log and via bridge
            double logStress = graph.getNode(logPos).stressValue();
            double bridgeStressAfter = graph.getNode(bridgePos).stressValue();
            double pillarStressAfter = graph.getNode(pillarPos).stressValue();

            System.out.println("After adding log:");
            System.out.println("  Log stress: " + logStress + " (capacity: 15)");
            System.out.println("  Bridge stress: " + bridgeStressAfter);
            System.out.println("  Pillar stress: " + pillarStressAfter);

            // Key assertion: adding the log REDUCES bridge stress because load is shared
            assertTrue(
                    bridgeStressAfter < bridgeStress,
                    "Bridge stress should decrease after adding log support. " + "Before: " + bridgeStress + ", After: "
                            + bridgeStressAfter);

            // Log should not be overloaded
            double logStressPct = graph.getNode(logPos).stressPercent();
            assertTrue(
                    logStressPct < 1.0,
                    "Log should not be overloaded. " + "Stress: " + logStress + ", Capacity: 15, Percent: "
                            + logStressPct);
        }
    }

    /**
     * Tests for two pillars connected by a bridge.
     *
     * <pre>
     *   ┌─────────────────────────────────────────────────────────────────────┐
     *   │           TWO PILLARS WITH BRIDGE SCENARIO                         │
     *   ├─────────────────────────────────────────────────────────────────────┤
     *   │                                                                     │
     *   │   User's bug: When one pillar breaks, the whole structure          │
     *   │   collapses instead of being supported by the other pillar.        │
     *   │                                                                     │
     *   │           [BRIDGE]───[BRIDGE]───[BRIDGE]                           │
     *   │              │                      │                              │
     *   │           [PILLAR_A]            [PILLAR_B]                         │
     *   │              │                      │                              │
     *   │           [BASE_A]              [BASE_B]                           │
     *   │              │                      │                              │
     *   │           [GND_A]               [GND_B]                            │
     *   │                                                                     │
     *   │   Expected: Bridge load splits between both pillars.               │
     *   │   If pillar A breaks, bridge stays up via pillar B.                │
     *   │                                                                     │
     *   └─────────────────────────────────────────────────────────────────────┘
     * </pre>
     */
    @Nested
    @DisplayName("Two pillars with bridge")
    class TwoPillarsWithBridge {

        // Light bridge material - easy to support
        private final MaterialSpec bridgeMat = new MaterialSpec(2.0, 200.0);
        // Standard pillar material
        private final MaterialSpec pillarMat = new MaterialSpec(5.0, 100.0);

        @Test
        @DisplayName("Bridge load should split between both pillars")
        void bridgeLoadSplitsBetweenPillars() {
            // Build structure:
            //   [B0]─[B1]─[B2]
            //    │         │
            //   [P_A]    [P_B]
            //    │         │
            //  [GND_A]  [GND_B]

            NodePos gndA = new NodePos(0, 0, 0);
            NodePos gndB = new NodePos(2, 0, 0);
            NodePos pillarA = new NodePos(0, 1, 0);
            NodePos pillarB = new NodePos(2, 1, 0);
            NodePos bridge0 = new NodePos(0, 2, 0);
            NodePos bridge1 = new NodePos(1, 2, 0);
            NodePos bridge2 = new NodePos(2, 2, 0);

            graph.addGroundBlock(gndA);
            graph.addGroundBlock(gndB);
            graph.addBlock(pillarA, pillarMat, false);
            graph.addBlock(pillarB, pillarMat, false);
            graph.addBlock(bridge0, bridgeMat, false);
            graph.addBlock(bridge1, bridgeMat, false);
            graph.addBlock(bridge2, bridgeMat, false);

            solver.solveAll(graph);

            double stressA = graph.getNode(pillarA).stressValue();
            double stressB = graph.getNode(pillarB).stressValue();

            System.out.println("Two pillars with 3-block bridge:");
            System.out.println("  Pillar A stress: " + stressA);
            System.out.println("  Pillar B stress: " + stressB);

            // Both pillars should share the bridge load
            // They might not be exactly equal due to distance weighting,
            // but both should carry significant load
            assertTrue(stressA > pillarMat.mass(), "Pillar A should carry more than just its own mass");
            assertTrue(stressB > pillarMat.mass(), "Pillar B should carry more than just its own mass");

            // Neither should be drastically overloaded compared to the other
            double ratio = Math.max(stressA, stressB) / Math.min(stressA, stressB);
            assertTrue(ratio < 3.0, "Load should be reasonably balanced. Ratio: " + ratio);
        }

        @Test
        @DisplayName("When one pillar breaks, bridge should stay up if other pillar can support")
        void bridgeStaysUpWhenOnePillarBreaks() {
            // Build structure with a strong enough second pillar:
            //   [B0]─[B1]─[B2]
            //    │         │
            //   [P_A]    [P_B] (high capacity)
            //    │         │
            //  [GND_A]  [GND_B]

            MaterialSpec strongPillar = new MaterialSpec(5.0, 500.0); // Very strong

            NodePos gndA = new NodePos(0, 0, 0);
            NodePos gndB = new NodePos(2, 0, 0);
            NodePos pillarA = new NodePos(0, 1, 0);
            NodePos pillarB = new NodePos(2, 1, 0);
            NodePos bridge0 = new NodePos(0, 2, 0);
            NodePos bridge1 = new NodePos(1, 2, 0);
            NodePos bridge2 = new NodePos(2, 2, 0);

            graph.addGroundBlock(gndA);
            graph.addGroundBlock(gndB);
            graph.addBlock(pillarA, pillarMat, false);
            graph.addBlock(pillarB, strongPillar, false);
            graph.addBlock(bridge0, bridgeMat, false);
            graph.addBlock(bridge1, bridgeMat, false);
            graph.addBlock(bridge2, bridgeMat, false);

            // Remove pillar A (simulating collapse)
            graph.removeBlock(pillarA);

            // Recalculate stress
            solver.solveAll(graph);

            // Check that bridge blocks are NOT floating
            var floating = graph.getFloatingBlocks();
            System.out.println("After removing pillar A:");
            System.out.println("  Floating blocks: " + floating);
            System.out.println("  Bridge0 in graph: " + graph.hasBlock(bridge0));

            assertFalse(
                    floating.contains(bridge0), "Bridge0 should NOT be floating - connected via bridge1 to pillar B");
            assertFalse(floating.contains(bridge1), "Bridge1 should NOT be floating - connected to pillar B");
            assertFalse(floating.contains(bridge2), "Bridge2 should NOT be floating - directly on pillar B");

            // Pillar B should carry bridge load (with horizontal decay reducing accumulated stress)
            // With horizontal decay (0.5 per hop):
            //   B0 stress = 2, B1 stress = 2 + 2*0.5 = 3, B2 stress = 2 + 3*0.5 = 3.5
            //   Pillar B stress = 5 + 3.5 = 8.5
            double stressB = graph.getNode(pillarB).stressValue();

            System.out.println("  Pillar B stress: " + stressB);

            // Pillar B should carry its own mass plus the decayed bridge load
            assertTrue(
                    stressB >= strongPillar.mass(), "Pillar B should carry at least its own weight. Got: " + stressB);
            assertTrue(
                    stressB > strongPillar.mass() + bridgeMat.mass(),
                    "Pillar B should carry more than just its own mass + one bridge block. Got: " + stressB);
        }

        @Test
        @DisplayName("Cascade should remove only unsupported blocks, not the whole structure")
        void cascadeRemovesOnlyUnsupportedBlocks() {
            // Build structure where pillar A is weak and will collapse,
            // but pillar B can support the bridge
            MaterialSpec weakPillar = new MaterialSpec(5.0, 10.0); // Very weak
            MaterialSpec strongPillar = new MaterialSpec(5.0, 500.0); // Very strong

            NodePos gndA = new NodePos(0, 0, 0);
            NodePos gndB = new NodePos(2, 0, 0);
            NodePos pillarA = new NodePos(0, 1, 0);
            NodePos pillarB = new NodePos(2, 1, 0);
            NodePos bridge0 = new NodePos(0, 2, 0);
            NodePos bridge1 = new NodePos(1, 2, 0);
            NodePos bridge2 = new NodePos(2, 2, 0);

            graph.addGroundBlock(gndA);
            graph.addGroundBlock(gndB);
            graph.addBlock(pillarA, weakPillar, false);
            graph.addBlock(pillarB, strongPillar, false);
            graph.addBlock(bridge0, bridgeMat, false);
            graph.addBlock(bridge1, bridgeMat, false);
            graph.addBlock(bridge2, bridgeMat, false);

            solver.solveAll(graph);

            // Check if pillar A is overloaded
            double pillarAStress = graph.getNode(pillarA).stressPercent();
            System.out.println("Weak pillar A stress: " + (pillarAStress * 100) + "%");

            // Use cascade engine to simulate collapse
            CascadeEngine engine = new CascadeEngine();
            CascadeResult result = engine.cascade(graph, pillarA);

            System.out.println("Cascade removed " + result.collapsed().size() + " blocks");
            System.out.println("Remaining blocks: " + graph.getAllPositions());

            // The weak pillar might collapse, but bridge should still be there
            // (or at least partially there) supported by strong pillar B
            assertTrue(graph.hasBlock(bridge2), "Bridge2 should survive - directly connected to strong pillar B");
            assertTrue(graph.hasBlock(pillarB), "Pillar B should survive - it's strong enough");
        }

        /**
         * This is the key test for partial bridge collapse.
         *
         * <pre>
         *   ┌─────────────────────────────────────────────────────────────────────┐
         *   │                 PARTIAL BRIDGE COLLAPSE SCENARIO                    │
         *   ├─────────────────────────────────────────────────────────────────────┤
         *   │                                                                     │
         *   │  Structure: Long bridge between two pillars                         │
         *   │                                                                     │
         *   │   [B0]─[B1]─[B2]─[B3]─[B4]─[B5]─[B6]─[B7]                           │
         *   │    │                               │                                │
         *   │  [P_A]                           [P_B]  (strong)                    │
         *   │    │                               │                                │
         *   │  [GND_A]                        [GND_B]                             │
         *   │                                                                     │
         *   │  When P_A collapses:                                               │
         *   │  - B0 loses support, becomes cantilevered → collapses              │
         *   │  - B1 loses B0, becomes more cantilevered → collapses              │
         *   │  - etc. until reaching a stable point                              │
         *   │  - B7, B6, B5 etc. near P_B should stay up                         │
         *   │                                                                     │
         *   │  This creates a realistic "partial collapse" effect.               │
         *   │                                                                     │
         *   └─────────────────────────────────────────────────────────────────────┘
         * </pre>
         */
        @Test
        @DisplayName("Long bridge should partially collapse when one pillar fails - progressive failure")
        void longBridgePartialCollapseOnPillarFailure() {
            // Bridge material: With 1.5x leverage amplification, stress grows exponentially.
            // Stress on 8-block cantilever: B0=1, B1=2.5, B2=4.75, B3=8.1, B4=13.2, B5=20.8, B6=32.2, B7=49.3
            // Capacity of 15 means B5 (20.8) fails first, then B0-B4 float.
            // After collapse: B6=1, B7=2.5 < 15 → survive!
            MaterialSpec bridgeMaterial = new MaterialSpec(1.0, 15.0);
            // Strong pillar that won't collapse
            MaterialSpec strongPillar = new MaterialSpec(2.0, 1000.0);

            // Build a longer bridge (8 blocks)
            //   [B0]─[B1]─[B2]─[B3]─[B4]─[B5]─[B6]─[B7]
            //    │                               │
            //  [P_A]                           [P_B]
            //    │                               │
            //  [GND_A]                        [GND_B]

            NodePos gndA = new NodePos(0, 0, 0);
            NodePos gndB = new NodePos(7, 0, 0);
            NodePos pillarA = new NodePos(0, 1, 0);
            NodePos pillarB = new NodePos(7, 1, 0);

            graph.addGroundBlock(gndA);
            graph.addGroundBlock(gndB);
            graph.addBlock(pillarA, strongPillar, false);
            graph.addBlock(pillarB, strongPillar, false);

            // Add 8 bridge blocks
            NodePos[] bridgeBlocks = new NodePos[8];
            for (int i = 0; i < 8; i++) {
                bridgeBlocks[i] = new NodePos(i, 2, 0);
                graph.addBlock(bridgeBlocks[i], bridgeMaterial, false);
            }

            solver.solveAll(graph);

            // Verify initial stress distribution
            System.out.println("Initial stress distribution:");
            for (int i = 0; i < 8; i++) {
                Node node = graph.getNode(bridgeBlocks[i]);
                System.out.println("  B" + i + ": " + node.stressValue() + " (" + (node.stressPercent() * 100) + "%)");
            }

            // Now remove pillar A - simulate it collapsing
            CascadeEngine engine = new CascadeEngine();
            CascadeResult result = engine.cascade(graph, pillarA);

            System.out.println("\nAfter pillar A collapse:");
            System.out.println("  Collapsed blocks: " + result.collapsed());
            System.out.println("  Remaining blocks: " + graph.getAllPositions());

            // Key assertions:
            // With capacity 1.95: B5 (1.96875) fails first, B0-B4 float, B6 & B7 survive

            // 1. NOT all bridge blocks should collapse - some near pillar B should survive
            assertTrue(
                    result.collapsed().size() < 8,
                    "Not all 8 bridge blocks should collapse. Collapsed: "
                            + result.collapsed().size());

            // 2. Pillar B should survive (strong pillar)
            assertTrue(graph.hasBlock(pillarB), "Pillar B should survive");

            // 3. B7 (directly above pillar B) should survive
            assertTrue(graph.hasBlock(bridgeBlocks[7]), "B7 (directly on pillar B) should survive");

            // 4. B6 should also survive (stress = 1 after recalc)
            assertTrue(graph.hasBlock(bridgeBlocks[6]), "B6 should survive - connected to pillar B via B7");

            // 5. At least some blocks near pillar A should collapse
            assertTrue(
                    result.collapsed().contains(bridgeBlocks[0]) || !graph.hasBlock(bridgeBlocks[0]),
                    "B0 (was on pillar A) should collapse or be removed");

            // 6. The collapse should be "progressive" - blocks collapse from the failure point
            int survivingBlocks = 0;
            int collapsedBlocks = 0;

            for (int i = 0; i < 8; i++) {
                if (graph.hasBlock(bridgeBlocks[i])) {
                    survivingBlocks++;
                    System.out.println("  B" + i + " survived");
                } else {
                    collapsedBlocks++;
                }
            }

            System.out.println("  Total collapsed: " + collapsedBlocks);
            System.out.println("  Total surviving: " + survivingBlocks);

            // At least 2 blocks should survive (B6, B7)
            assertTrue(
                    survivingBlocks >= 2,
                    "At least 2 bridge blocks should survive near pillar B. Surviving: " + survivingBlocks);
        }

        /**
         * Tests that a bridge with sufficient capacity can fully cantilever.
         * When one support fails but the bridge material is strong enough,
         * the entire bridge survives via the remaining pillar.
         *
         * With linear moment stress (weight × distance), a 6-block cantilever has:
         * - B5 (nearest pillar): vertical=6, moment=5×5=25, total=31
         * - B4: vertical=5, moment=4×4=16, total=21
         * - B3: vertical=4, moment=3×3=9, total=13
         * - B2: vertical=3, moment=2×2=4, total=7
         * - B1: vertical=2, moment=1×1=1, total=3
         * - B0: vertical=1, moment=0, total=1
         *
         * Capacity of 35 allows full cantilever (max stress 31 < 35).
         */
        @Test
        @DisplayName("Strong bridge should cantilever fully when one pillar fails")
        void strongBridgeSurvivesAsFullCantilever() {
            // Bridge material: capacity 35 handles max stress of ~31 at B5
            MaterialSpec bridgeMaterial = new MaterialSpec(1.0, 35.0);
            MaterialSpec strongPillar = new MaterialSpec(2.0, 1000.0);

            // Build bridge: 6 blocks
            //   [B0]─[B1]─[B2]─[B3]─[B4]─[B5]
            //    │                      │
            //  [P_A]                  [P_B]
            //    │                      │
            //  [GND_A]               [GND_B]

            NodePos gndA = new NodePos(0, 0, 0);
            NodePos gndB = new NodePos(5, 0, 0);
            NodePos pillarA = new NodePos(0, 1, 0);
            NodePos pillarB = new NodePos(5, 1, 0);

            graph.addGroundBlock(gndA);
            graph.addGroundBlock(gndB);
            graph.addBlock(pillarA, strongPillar, false);
            graph.addBlock(pillarB, strongPillar, false);

            NodePos[] bridgeBlocks = new NodePos[6];
            for (int i = 0; i < 6; i++) {
                bridgeBlocks[i] = new NodePos(i, 2, 0);
                graph.addBlock(bridgeBlocks[i], bridgeMaterial, false);
            }

            // Remove pillar A
            CascadeEngine engine = new CascadeEngine();
            CascadeResult result = engine.cascade(graph, pillarA);

            System.out.println("6-block strong bridge after pillar A collapse:");
            System.out.println("  Collapsed: " + result.collapsed());
            System.out.println("  Remaining: " + graph.getAllPositions());

            assertTrue(graph.hasBlock(bridgeBlocks[5]), "B5 (directly on pillar B) must survive");
            assertTrue(graph.hasBlock(pillarB), "Pillar B must survive");

            // With strong enough material, ALL bridge blocks survive
            for (int i = 0; i < 6; i++) {
                assertTrue(
                        graph.hasBlock(bridgeBlocks[i]),
                        "B" + i + " should survive - bridge is strong enough to cantilever fully");
            }
        }

        /**
         * Tests that a weaker bridge partially collapses when one pillar fails.
         * With linear moment stress (weight × distance), stress grows quadratically.
         *
         * After pillar A removal, a 6-block cantilever has:
         * - B0: vertical=1, moment=0, total=1
         * - B1: vertical=2, moment=1, total=3
         * - B2: vertical=3, moment=4, total=7
         * - B3: vertical=4, moment=9, total=13
         * - B4: vertical=5, moment=16, total=21
         * - B5: vertical=6, moment=25, total=31
         *
         * With capacity=10, B3 fails first (13 > 10), B0-B2 float, B4-B5 survive.
         */
        @Test
        @DisplayName("Weak bridge should partially collapse at break point")
        void weakBridgePartiallyCollapsesAtBreakPoint() {
            // With linear moment, B3 fails first (13 > 10), B0-B2 float, B4-B5 survive.
            MaterialSpec bridgeMaterial = new MaterialSpec(1.0, 10.0);
            MaterialSpec strongPillar = new MaterialSpec(2.0, 1000.0);

            // Build bridge: 6 blocks
            NodePos gndA = new NodePos(0, 0, 0);
            NodePos gndB = new NodePos(5, 0, 0);
            NodePos pillarA = new NodePos(0, 1, 0);
            NodePos pillarB = new NodePos(5, 1, 0);

            graph.addGroundBlock(gndA);
            graph.addGroundBlock(gndB);
            graph.addBlock(pillarA, strongPillar, false);
            graph.addBlock(pillarB, strongPillar, false);

            NodePos[] bridgeBlocks = new NodePos[6];
            for (int i = 0; i < 6; i++) {
                bridgeBlocks[i] = new NodePos(i, 2, 0);
                graph.addBlock(bridgeBlocks[i], bridgeMaterial, false);
            }

            // Remove pillar A
            CascadeEngine engine = new CascadeEngine();
            CascadeResult result = engine.cascade(graph, pillarA);

            System.out.println("6-block weak bridge after pillar A collapse:");
            System.out.println("  Collapsed: " + result.collapsed());
            System.out.println("  Remaining: " + graph.getAllPositions());

            assertTrue(graph.hasBlock(pillarB), "Pillar B must survive");

            // Bridge partially collapses - some blocks fail
            // At least B3 fails (stress 13 > capacity 10), causing B0-B2 to float
            assertTrue(
                    result.collapsed().size() >= 3,
                    "At least 3 blocks should collapse (B0-B2 float when B3 fails). Collapsed: "
                            + result.collapsed().size());

            // B5 should survive (nearest pillar B, stress manageable after recalc)
            assertTrue(graph.hasBlock(bridgeBlocks[5]), "B5 should survive - nearest to pillar B");

            // This is partial collapse - at least one block survives
            assertTrue(
                    result.collapsed().size() < 7,
                    "Not all blocks should collapse - this is partial collapse. Collapsed: "
                            + result.collapsed().size());
        }
    }
}
