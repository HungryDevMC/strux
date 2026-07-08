package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.model.ThermalClass;
import dev.gesp.structural.solver.StressSolver;
import dev.gesp.structural.thermal.ThermalStrength;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tier-2 physics validation: pins the solver against closed-form textbook
 * mechanics instead of against itself.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │              WHY THIS SUITE EXISTS (the "north star")               │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Snapshots and replay verification prove the engine agrees with    │
 *   │  ITSELF. This suite proves it agrees with the textbook where a     │
 *   │  closed-form answer exists:                                        │
 *   │                                                                     │
 *   │    column   — block at height y carries exactly the mass above it  │
 *   │    cantilever — root moment grows with the SQUARE of arm length    │
 *   │                 (the Euler–Bernoulli w·L²/2 shape)                  │
 *   │    beam     — an arm grounded at BOTH ends carries no moment       │
 *   │    bridge   — a symmetric deck splits exactly in half              │
 *   │    symmetry — a mirrored structure has mirrored stress             │
 *   │    conservation — no weight may appear or vanish between levels    │
 *   │                                                                     │
 *   │  None of these depend on tuning. If a future change bends one,     │
 *   │  the physics changed shape — not just numbers.                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
@DisplayName("Physics validation: textbook north-star checks")
class PhysicsValidationTest {

    /** FP tolerance for sums that may associate differently than the solver. */
    private static final double EPS = 1e-9;

    // ─────────────────────────────────────────────────────────────────────
    //  COLUMN: the simplest exact answer in all of statics
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Column: block at height y carries exactly the column above it (plus itself)")
    void columnLoadIsExact() {
        int height = 12;
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= height; y++) {
            g.addBlock(new NodePos(0, y, 0), TestMaterials.LIGHT, false);
        }

        new StressSolver().solveAll(g);

        for (int y = 1; y <= height; y++) {
            Node node = g.getNode(new NodePos(0, y, 0));
            // Own mass + everything above: (height - y + 1) blocks of mass 1.
            assertEquals(height - y + 1.0, node.verticalStress(), 0.0, "vertical stress at height " + y);
            assertEquals(0.0, node.momentStress(), 0.0, "a plain column has no moment at height " + y);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  CANTILEVER: root moment must follow the L² law
    // ─────────────────────────────────────────────────────────────────────

    /** Tower of HEAVY at x=0 with a LIGHT arm of {@code armLength} off its top. */
    private static StructureGraph towerWithArm(int armLength) {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        int towerHeight = 4;
        for (int y = 1; y <= towerHeight; y++) {
            g.addBlock(new NodePos(0, y, 0), TestMaterials.HEAVY, false);
        }
        for (int x = 1; x <= armLength; x++) {
            g.addBlock(new NodePos(x, towerHeight, 0), TestMaterials.LIGHT, false);
        }
        return g;
    }

    /** The moment carried by the tower-top block that anchors the arm. */
    private static double rootMoment(int armLength) {
        StructureGraph g = towerWithArm(armLength);
        new StressSolver().solveAll(g);
        return g.getNode(new NodePos(0, 4, 0)).momentStress();
    }

    @Test
    @DisplayName("Cantilever: root moment is exactly mass×reach = m·L² (the textbook L² shape)")
    void cantileverRootMomentIsQuadratic() {
        // moment = armMass × reach = (L·m)·L. With m=1 that is exactly L².
        for (int armLength : new int[] {3, 6, 12}) {
            assertEquals(
                    (double) armLength * armLength,
                    rootMoment(armLength),
                    0.0,
                    "root moment for arm length " + armLength);
        }
        // Doubling the arm exactly quadruples the root moment — the L² law a
        // real uniform cantilever (w·L²/2) obeys. Tuning can scale the
        // magnitude; nothing should ever bend the exponent.
        assertEquals(4.0, rootMoment(12) / rootMoment(6), 0.0, "doubling the arm must quadruple the moment");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SECTION MODULUS: bending capacity grows with the SQUARE of beam depth
    // ─────────────────────────────────────────────────────────────────────

    /**
     * A cantilever {@code depth} nodes thick (vertically) with a {@code armLength}
     * arm at every layer, anchored to a grounded column of the same thickness.
     * The arm runs in +x; "depth" is the vertical thickness of the beam.
     *
     * <pre>
     *   depth = 2, armLength = 3:
     *
     *     [A][a][a][a]   ← top    layer (y = base+1)
     *     [A][a][a][a]   ← bottom layer (y = base)
     *      │
     *    [GND]           ← grounds the anchor column
     * </pre>
     */
    private static StructureGraph thickCantilever(int depth, int armLength) {
        StructureGraph g = new StructureGraph();
        int base = 1; // first beam layer sits at y=1, just above ground
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int k = 0; k < depth; k++) {
            int y = base + k;
            g.addBlock(new NodePos(0, y, 0), TestMaterials.LIGHT, false); // anchor column
            for (int x = 1; x <= armLength; x++) {
                g.addBlock(new NodePos(x, y, 0), TestMaterials.LIGHT, false); // arm layer
            }
        }
        return g;
    }

    /** A solver with the section-depth (d²) bending physics turned on. */
    private static StressSolver bendingDepthSolver() {
        PhysicsConfig cfg = new PhysicsConfig();
        cfg.setBendingDepthEnabled(true);
        return new StressSolver(cfg);
    }

    /** Moment carried by the bottom anchor node of a {@code depth}-thick cantilever. */
    private static double thickAnchorMoment(int depth, int armLength) {
        StructureGraph g = thickCantilever(depth, armLength);
        bendingDepthSolver().solveAll(g);
        return g.getNode(new NodePos(0, 1, 0)).momentStress();
    }

    @Test
    @DisplayName("Section modulus: a deeper beam carries the SAME moment at 1/depth² the stress (S = b·d²/6)")
    void deeperBeamIsStrongerByDepthSquared() {
        int armLength = 4;

        // The applied moment per layer is identical (same arm mass × reach), so
        // the ONLY thing that changes the anchor's moment stress is the section
        // depth. Capacity ∝ d² ⇒ stress ∝ 1/d².
        double m1 = thickAnchorMoment(1, armLength);
        double m2 = thickAnchorMoment(2, armLength);
        double m3 = thickAnchorMoment(3, armLength);

        assertTrue(m1 > 0, "a one-thick cantilever must carry moment");
        // depth 1 is the unchanged baseline; deeper sections divide by d².
        assertEquals(m1 / 4.0, m2, EPS, "doubling beam depth must quarter the moment stress (d²=4)");
        assertEquals(m1 / 9.0, m3, EPS, "tripling beam depth must ninth the moment stress (d²=9)");
    }

    @Test
    @DisplayName("Section modulus: a depth-2 beam survives an arm a one-thick beam fails — the d² capacity")
    void depth2BeamSurvivesWhereDepth1Fails() {
        // Capacity arm: pick an arm length where a one-thick cantilever is
        // OVERLOADED but a two-thick one (4× the capacity) is not. With LIGHT
        // (mass 1, maxLoad 20): moment = (L·1)·L = L². L=5 ⇒ 25 > 20 (fails);
        // depth-2 ⇒ 25/4 ≈ 6 (holds easily). The deeper beam survives the SAME arm.
        int armLength = 5;

        StructureGraph thin = thickCantilever(1, armLength);
        StructureGraph thick = thickCantilever(2, armLength);
        bendingDepthSolver().solveAll(thin);
        bendingDepthSolver().solveAll(thick);

        assertTrue(
                thin.getNode(new NodePos(0, 1, 0)).isOverloaded(),
                "a one-thick cantilever this long must overload under its own bending moment");
        assertFalse(
                thick.getNode(new NodePos(0, 1, 0)).isOverloaded(),
                "the same arm on a two-thick beam (4× capacity) must survive");
    }

    @Test
    @DisplayName("Section modulus: the cascade finders honour depth too (deeper beam reports no overload)")
    void cascadeFindersHonourSectionDepth() {
        // The progressive + batch overload finders run their OWN moment pass
        // (computeLevelStress), distinct from the full solve. Pin that they apply
        // the same d² law: a one-thick arm long enough to overload is reported,
        // the same arm on a two-thick beam (4× capacity) is not.
        int armLength = 5;
        StressSolver solver = bendingDepthSolver();

        StructureGraph thin = thickCantilever(1, armLength);
        StructureGraph thick = thickCantilever(2, armLength);

        assertNotNull(
                solver.solveProgressively(thin, thin.getAllPositions()),
                "a one-thick cantilever this long must be reported overloaded by the progressive finder");
        assertNull(
                solver.solveProgressively(thick, thick.getAllPositions()),
                "the same arm on a two-thick beam must NOT be reported overloaded");
        assertFalse(
                solver.findOverloadedBatch(thin, thin.getAllPositions()).isEmpty(),
                "the batch finder must also report the one-thick cantilever");
        assertTrue(
                solver.findOverloadedBatch(thick, thick.getAllPositions()).isEmpty(),
                "the batch finder must clear the two-thick beam");
    }

    @Test
    @DisplayName("Beam: the same arm grounded at BOTH ends carries no moment")
    void beamCarriesNoMoment() {
        int armLength = 6;
        StructureGraph g = towerWithArm(armLength);
        // Ground the far end with a second pillar: cantilever -> beam.
        g.addGroundBlock(new NodePos(armLength, 0, 0));
        for (int y = 1; y <= 3; y++) {
            g.addBlock(new NodePos(armLength, y, 0), TestMaterials.HEAVY, false);
        }

        new StressSolver().solveAll(g);

        for (NodePos pos : g.getAllPositions()) {
            assertEquals(
                    0.0,
                    g.getNode(pos).momentStress(),
                    0.0,
                    "a two-ended span is a beam, not a cantilever; no moment at " + pos);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  BRIDGE: symmetry means an exactly even split
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Two pillars (x=0 and x=6, height 3) carrying a 5-block deck at y=4.
     * The odd span keeps every adjacent deck pair at different distances
     * from ground, so the load split is clean (see the conservation note).
     */
    private static StructureGraph symmetricBridge() {
        StructureGraph g = new StructureGraph();
        for (int x : new int[] {0, 6}) {
            g.addGroundBlock(new NodePos(x, 0, 0));
            for (int y = 1; y <= 3; y++) {
                g.addBlock(new NodePos(x, y, 0), TestMaterials.HEAVY, false);
            }
            g.addBlock(new NodePos(x, 4, 0), TestMaterials.HEAVY, false); // pier top
        }
        for (int x = 1; x <= 5; x++) {
            g.addBlock(new NodePos(x, 4, 0), TestMaterials.LIGHT, false); // deck
        }
        return g;
    }

    @Test
    @DisplayName("Bridge: mirrored blocks carry identical stress; each pier base carries half the deck")
    void bridgeSplitsLoadSymmetrically() {
        StructureGraph g = symmetricBridge();
        new StressSolver().solveAll(g);

        // Mirror symmetry: x <-> 6-x.
        for (NodePos pos : g.getAllPositions()) {
            NodePos mirror = new NodePos(6 - pos.x(), pos.y(), pos.z());
            assertEquals(
                    g.getNode(pos).verticalStress(),
                    g.getNode(mirror).verticalStress(),
                    EPS,
                    "vertical stress must mirror at " + pos);
            assertEquals(
                    g.getNode(pos).momentStress(),
                    g.getNode(mirror).momentStress(),
                    EPS,
                    "moment stress must mirror at " + pos);
        }

        // Even split: each pier base (y=1) carries its own pillar (4 HEAVY
        // above-and-including... 3 above + pier top) plus exactly half the deck.
        double pillarAbove = 3 * TestMaterials.HEAVY.mass(); // y=2,3 + pier top y=4
        double own = TestMaterials.HEAVY.mass();
        double halfDeck = 5 * TestMaterials.LIGHT.mass() / 2.0;
        assertEquals(
                own + pillarAbove + halfDeck,
                g.getNode(new NodePos(0, 1, 0)).verticalStress(),
                EPS,
                "pier base must carry its pillar plus exactly half the deck");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  CONSERVATION: weight may not appear or vanish on its way to ground
    // ─────────────────────────────────────────────────────────────────────

    /** Textbook BFS distance-from-ground — an independent oracle, not the solver's. */
    private static Map<NodePos, Integer> distances(StructureGraph g) {
        Map<NodePos, Integer> dist = new HashMap<>();
        Queue<NodePos> queue = new ArrayDeque<>();
        for (NodePos pos : g.getAllPositions()) {
            if (g.getNode(pos).isGrounded()) {
                dist.put(pos, 0);
                queue.add(pos);
            }
        }
        while (!queue.isEmpty()) {
            NodePos current = queue.poll();
            for (NodePos n : g.getNeighbors(current)) {
                if (g.hasBlock(n) && !dist.containsKey(n)) {
                    dist.put(n, dist.get(current) + 1);
                    queue.add(n);
                }
            }
        }
        return dist;
    }

    /**
     * On ANY connected structure, the sum of vertical stress across one
     * distance level must equal the total mass at that level and above —
     * every unit of weight is accounted for on its way down. Load flows only
     * to strictly-closer neighbours (exactly one level down per step), so
     * each level is a complete cross-section of everything above it.
     */
    private static void assertLevelConservation(StructureGraph g) {
        new StressSolver().solveAll(g);
        Map<NodePos, Integer> dist = distances(g);

        int maxDist = dist.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        for (int level = 1; level <= maxDist; level++) {
            double stressAtLevel = 0;
            double massAtOrAbove = 0;
            for (NodePos pos : g.getAllPositions()) {
                int d = dist.get(pos);
                if (d == level) {
                    stressAtLevel += g.getNode(pos).verticalStress();
                }
                if (d >= level) {
                    massAtOrAbove += g.getNode(pos).mass();
                }
            }
            assertEquals(massAtOrAbove, stressAtLevel, EPS, "conservation broken at distance level " + level);
        }
    }

    @Test
    @DisplayName("Conservation: every unit of weight is accounted for at every distance level")
    void loadIsConserved() {
        assertLevelConservation(towerWithArm(8)); // tower + cantilever
        assertLevelConservation(symmetricBridge()); // two piers + deck

        // An irregular tree: trunk with two branches of different lengths.
        StructureGraph tree = new StructureGraph();
        tree.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 6; y++) {
            tree.addBlock(new NodePos(0, y, 0), TestMaterials.MEDIUM, false);
        }
        for (int x = 1; x <= 3; x++) {
            tree.addBlock(new NodePos(x, 4, 0), TestMaterials.LIGHT, false);
        }
        for (int x = -1; x >= -5; x--) {
            tree.addBlock(new NodePos(x, 6, 0), TestMaterials.LIGHT, false);
        }
        assertLevelConservation(tree);

        // Laterally-coupled shapes — the ones the old share rule leaked on
        // (same-distance neighbours were offered load nobody delivered).

        // Twin towers touching all the way up.
        StructureGraph twins = new StructureGraph();
        twins.addGroundBlock(new NodePos(0, 0, 0));
        twins.addGroundBlock(new NodePos(1, 0, 0));
        for (int y = 1; y <= 6; y++) {
            twins.addBlock(new NodePos(0, y, 0), TestMaterials.LIGHT, false);
            twins.addBlock(new NodePos(1, y, 0), TestMaterials.LIGHT, false);
        }
        assertLevelConservation(twins);

        // A 3×3×4 solid wall — equal-distance edges everywhere.
        StructureGraph wall = new StructureGraph();
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                wall.addGroundBlock(new NodePos(x, 0, z));
                for (int y = 1; y <= 4; y++) {
                    wall.addBlock(new NodePos(x, y, z), TestMaterials.MEDIUM, false);
                }
            }
        }
        assertLevelConservation(wall);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  TEMPERATURE: a hot block's effective capacity is provably lower
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Temperature: setting a hot block's capacity factor lowers effectiveMaxLoad by exactly that factor")
    void hotBlockHasLowerEffectiveCapacity() {
        // Steel-class block at 600°C. Eurocode-3 says k_y,θ = 0.47 there.
        MaterialSpec steel = new MaterialSpec(2.0, 100.0, 1.0, 1.0, ThermalClass.STEEL);
        Node node = new Node(new NodePos(0, 1, 0), steel, false);

        double cool = node.effectiveMaxLoad();
        assertEquals(100.0, cool, EPS, "an unheated block is at full capacity");

        double factor = ThermalStrength.capacityFactor(steel, 600.0);
        node.setTemperatureCapacityFactor(factor);

        double hot = node.effectiveMaxLoad();
        assertEquals(100.0 * 0.47, hot, EPS, "at 600°C steel carries 47% of its cool capacity");
        assertTrue(hot < cool, "the hot block is provably weaker: " + hot + " < " + cool);
    }

    @Test
    @DisplayName("Temperature: the comfort band leaves effectiveMaxLoad untouched (factor 1.0)")
    void comfortBandIsNeutral() {
        MaterialSpec steel = new MaterialSpec(2.0, 100.0, 1.0, 1.0, ThermalClass.STEEL);
        Node node = new Node(new NodePos(0, 1, 0), steel, false);

        double factor = ThermalStrength.capacityFactor(steel, 20.0);
        node.setTemperatureCapacityFactor(factor);

        assertEquals(100.0, node.effectiveMaxLoad(), EPS, "20°C is in the comfort band → full capacity");
    }

    @Test
    @DisplayName("Temperature: thermal softening composes multiplicatively with damage")
    void temperatureComposesWithDamage() {
        MaterialSpec masonry = new MaterialSpec(2.0, 100.0, 1.0, 1.0, ThermalClass.MASONRY);
        Node node = new Node(new NodePos(0, 1, 0), masonry, false);

        node.addDamage(0.5); // half-cracked
        double factor = ThermalStrength.capacityFactor(masonry, 500.0); // k_c,θ = 0.60
        node.setTemperatureCapacityFactor(factor);

        // 100 × 0.60 (hot) × 0.5 (damage) = 30
        assertEquals(100.0 * 0.60 * 0.5, node.effectiveMaxLoad(), EPS);
    }

    @Test
    @DisplayName("Temperature: the factor is clamped to (0,1]; NaN is ignored; a zero curve floors positive")
    void temperatureFactorIsClampedAndSafe() {
        MaterialSpec steel = new MaterialSpec(2.0, 100.0, 1.0, 1.0, ThermalClass.STEEL);
        Node node = new Node(new NodePos(0, 1, 0), steel, false);

        node.setTemperatureCapacityFactor(2.0); // above 1 → clamped to 1.0
        assertEquals(1.0, node.temperatureCapacityFactor(), EPS);

        node.setTemperatureCapacityFactor(Double.NaN); // ignored — keeps the last value
        assertEquals(1.0, node.temperatureCapacityFactor(), EPS);

        // The curve at the failure temperature is ~0; the node floors it to a tiny
        // positive so effectiveMaxLoad stays finite (and stressPercent stays defined).
        node.setTemperatureCapacityFactor(ThermalStrength.capacityFactor(steel, 1200.0));
        assertTrue(node.effectiveMaxLoad() > 0.0, "a failure-temp block still has finite positive capacity");
        assertTrue(node.effectiveMaxLoad() < 1e-3, "but it is vanishingly small");
    }

    @Test
    @DisplayName("MaterialSpec: a null thermal class is rejected; withThermalClass swaps only that axis")
    void materialSpecThermalAxis() {
        assertThrows(
                IllegalArgumentException.class, () -> new MaterialSpec(2.0, 100.0, 1.0, 1.0, null), "null is rejected");

        MaterialSpec base = new MaterialSpec(3.0, 120.0, 2.0, 7.0);
        assertEquals(ThermalClass.INERT, base.thermalClass(), "legacy 4-arg defaults to INERT");

        MaterialSpec tagged = base.withThermalClass(ThermalClass.MASONRY);
        assertEquals(ThermalClass.MASONRY, tagged.thermalClass());
        assertEquals(3.0, tagged.mass(), EPS, "mass unchanged");
        assertEquals(120.0, tagged.maxLoad(), EPS, "maxLoad unchanged");
        assertEquals(2.0, tagged.blastResistance(), EPS, "blast unchanged");
        assertEquals(7.0, tagged.fireResistance(), EPS, "fire unchanged");
    }
}
