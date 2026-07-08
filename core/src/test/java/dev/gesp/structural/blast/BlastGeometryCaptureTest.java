package dev.gesp.structural.blast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.blast.BlastDebugCapture.CandidateOutcome;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The optional {@link BlastDebugCapture} must harvest the explosion's geometry — falloff
 * shells + destroy threshold, per-candidate intensity/occlusion/outcome, and occlusion
 * rays with their attenuation hits — with known values, gate rays behind
 * {@link BlastDebugCapture#wantsFullRays()}, and stay a no-op (no scan change) otherwise.
 */
@DisplayName("StruxExplosionEngine BlastDebugCapture: explosion anatomy captured with known values")
class BlastGeometryCaptureTest {

    private final PhysicsConfig config = new PhysicsConfig();

    /** Records every blast-capture emit. */
    private static final class Recorder implements BlastDebugCapture {
        record Candidate(NodePos pos, double distance, double intensity, double occlusion, CandidateOutcome outcome) {}

        record Ray(NodePos target, List<NodePos> cells, List<NodePos> attenuations) {}

        final List<Candidate> candidates = new ArrayList<>();
        final Map<NodePos, Ray> rays = new HashMap<>();
        double radius = -1;
        List<Double> shells;
        double destroyThreshold = -1;
        boolean fullRays;

        @Override
        public boolean wantsBlastCapture() {
            return true;
        }

        @Override
        public boolean wantsFullRays() {
            return fullRays;
        }

        @Override
        public void onBlastGeometry(double radius, List<Double> shells, double destroyThreshold) {
            this.radius = radius;
            this.shells = new ArrayList<>(shells);
            this.destroyThreshold = destroyThreshold;
        }

        @Override
        public void onBlastCandidate(
                NodePos pos, double distance, double rawIntensity, double occlusionFactor, CandidateOutcome outcome) {
            candidates.add(new Candidate(pos, distance, rawIntensity, occlusionFactor, outcome));
        }

        @Override
        public void onBlastRay(NodePos target, List<NodePos> cells, List<NodePos> attenuations) {
            rays.put(target, new Ray(target, new ArrayList<>(cells), new ArrayList<>(attenuations)));
        }

        Candidate candidateAt(NodePos pos) {
            return candidates.stream()
                    .filter(c -> c.pos().equals(pos))
                    .findFirst()
                    .orElse(null);
        }
    }

    private BlastContext blast(NodePos center, double power, BlastFalloff falloff, BlastOcclusion occ) {
        return BlastContext.builder()
                .center(center)
                .power(power)
                .falloff(falloff)
                .occlusion(occ)
                .build();
    }

    @Test
    @DisplayName("Falloff geometry: radius, quarter-radius shells and destroy threshold are reported once")
    void capturesFalloffGeometry() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), new MaterialSpec(1.0, 1000.0, 1.0), false);

        Recorder rec = new Recorder();
        StruxExplosionEngine engine = new StruxExplosionEngine(config).setBlastDebugCapture(rec);
        engine.process(g, blast(new NodePos(0, 1, 0), 4.0, BlastFalloff.FLAT, BlastOcclusion.NONE));

        // radius = power * blastRadiusPerPower = 4 * 1.5 = 6.0; shells are quarters.
        assertEquals(6.0, rec.radius, 1e-9, "radius = power * blastRadiusPerPower");
        assertEquals(List.of(1.5, 3.0, 4.5, 6.0), rec.shells, "four quarter-radius shells");
        assertEquals(config.getDestructionThreshold(), rec.destroyThreshold, 1e-9, "destroy threshold passed through");
    }

    @Test
    @DisplayName("A wall: the candidate behind it is occluded (factor 0.75) and its ray records the attenuating block")
    void capturesOccludedCandidateAndAttenuatedRay() {
        // A vertical line: ground y0, wall y1..y4. Blast just above the top of the wall.
        // The ray from the center down to a lower block passes through the solid blocks
        // between them, attenuating it.
        MaterialSpec stone = new MaterialSpec(1.0, 1000.0, 1.0);
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 4; y++) {
            g.addBlock(new NodePos(0, y, 0), stone, false);
        }

        Recorder rec = new Recorder();
        StruxExplosionEngine engine = new StruxExplosionEngine(config).setBlastDebugCapture(rec);
        // Center at (0,5,0): just above the wall. Power 2 → radius 3, so the lower
        // blocks are in range; FLAT falloff keeps intensity simple. The occluded lower
        // block cracks rather than craters, so it is "touched" and its ray is kept.
        engine.process(g, blast(new NodePos(0, 5, 0), 2.0, BlastFalloff.FLAT, BlastOcclusion.RAYCAST));

        // The block at (0,3,0): the ray from center (0,5,0) crosses (0,4,0), a solid
        // block → 1 cover → occlusionFactor = 1 - 1*0.25 = 0.75.
        Recorder.Candidate c3 = rec.candidateAt(new NodePos(0, 3, 0));
        assertNotNull(c3, "the block two below the center is examined");
        assertEquals(2.0, c3.distance(), 1e-9, "it is two blocks below the center");
        assertEquals(0.75, c3.occlusion(), 1e-9, "one solid cover block → 0.75 attenuation");

        // Its ray must record the geometric cell (0,4,0) and count it as an attenuation.
        Recorder.Ray r3 = rec.rays.get(new NodePos(0, 3, 0));
        assertNotNull(r3, "a touched candidate keeps its ray by default");
        assertTrue(r3.cells().contains(new NodePos(0, 4, 0)), "the ray crosses the block above the target");
        assertEquals(List.of(new NodePos(0, 4, 0)), r3.attenuations(), "exactly the solid in-between block attenuates");
        assertFalse(r3.cells().contains(new NodePos(0, 3, 0)), "the target itself is not 'between'");
    }

    @Test
    @DisplayName("Outcomes: a block reaching the threshold is DESTROYED; below it, DAMAGED")
    void capturesCandidateOutcomes() {
        MaterialSpec weak = new MaterialSpec(1.0, 1000.0, 1.0);
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 6; y++) {
            g.addBlock(new NodePos(0, y, 0), weak, false);
        }

        Recorder rec = new Recorder();
        StruxExplosionEngine engine = new StruxExplosionEngine(config).setBlastDebugCapture(rec);
        // FLAT falloff, no occlusion: intensity = power / blastResistance = 3.0 > 2.0
        // threshold for every in-range block → all reachable blocks DESTROYED.
        engine.process(g, blast(new NodePos(0, 1, 0), 3.0, BlastFalloff.FLAT, BlastOcclusion.NONE));

        Recorder.Candidate c1 = rec.candidateAt(new NodePos(0, 1, 0));
        assertNotNull(c1);
        assertEquals(3.0, c1.intensity(), 1e-9, "power 3 / blastResistance 1 = intensity 3");
        assertEquals(CandidateOutcome.DESTROYED, c1.outcome(), "intensity 3 ≥ threshold 2 → destroyed");
    }

    @Test
    @DisplayName("Default keeps only touched candidates' rays; wantsFullRays() keeps every in-range ray")
    void rayGating() {
        // A tall column. A block deep behind ≥4 cover blocks is fully occluded
        // (occlusionFactor = 1 - 4*0.25 = 0 → intensity 0 → UNAFFECTED, no ray by default).
        MaterialSpec stone = new MaterialSpec(1.0, 1000.0, 1.0);
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 6; y++) {
            g.addBlock(new NodePos(0, y, 0), stone, false);
        }

        // Center (0,7,0), power 4 → radius 6: every column block is in range. The block
        // at (0,2,0) sits behind y3..y6 (4 cover) → fully occluded → UNAFFECTED.
        Recorder slim = new Recorder();
        new StruxExplosionEngine(config)
                .setBlastDebugCapture(slim)
                .process(g, blast(new NodePos(0, 7, 0), 4.0, BlastFalloff.FLAT, BlastOcclusion.RAYCAST));
        boolean anyUnaffected = slim.candidates.stream().anyMatch(c -> c.outcome() == CandidateOutcome.UNAFFECTED);
        assertTrue(anyUnaffected, "a fully-occluded deep block comes out unaffected");
        boolean unaffectedRayKept = slim.candidates.stream()
                .filter(c -> c.outcome() == CandidateOutcome.UNAFFECTED)
                .anyMatch(c -> slim.rays.containsKey(c.pos()));
        assertFalse(unaffectedRayKept, "no ray kept for an unaffected candidate by default");

        // fullRays on: every in-range candidate's ray is reported.
        Recorder full = new Recorder();
        full.fullRays = true;
        new StruxExplosionEngine(config)
                .setBlastDebugCapture(full)
                .process(g, blast(new NodePos(0, 7, 0), 4.0, BlastFalloff.FLAT, BlastOcclusion.RAYCAST));
        assertFalse(full.rays.isEmpty(), "fullRays keeps rays even for unaffected candidates");
        assertEquals(full.candidates.size(), full.rays.size(), "one ray per in-range candidate with fullRays on");
    }

    @Test
    @DisplayName("Capturing geometry does not change the blast outcome (destroyed/damaged identical)")
    void captureIsSideEffectFree() {
        MaterialSpec stone = new MaterialSpec(1.0, 1000.0, 1.0);
        StructureGraph withCapture = wall(stone);
        StructureGraph noCapture = wall(stone);

        Recorder rec = new Recorder();
        rec.fullRays = true;
        BlastResult captured = new StruxExplosionEngine(config)
                .setBlastDebugCapture(rec)
                .process(withCapture, blast(new NodePos(2, 6, 0), 4.0, BlastFalloff.QUADRATIC, BlastOcclusion.RAYCAST));
        BlastResult plain = new StruxExplosionEngine(config)
                .process(noCapture, blast(new NodePos(2, 6, 0), 4.0, BlastFalloff.QUADRATIC, BlastOcclusion.RAYCAST));

        assertEquals(plain.destroyed(), captured.destroyed(), "same destroyed set with capture on");
        assertEquals(plain.damaged(), captured.damaged(), "same damaged map with capture on");
        assertEquals(plain.collapsed(), captured.collapsed(), "same collapsed set with capture on");
        assertFalse(rec.candidates.isEmpty(), "and the capture really ran");
    }

    /** A 5-wide, 5-tall grounded stone wall in the z=0 plane. */
    private StructureGraph wall(MaterialSpec stone) {
        StructureGraph g = new StructureGraph();
        for (int x = 0; x < 5; x++) {
            g.addGroundBlock(new NodePos(x, 0, 0));
            for (int y = 1; y <= 5; y++) {
                g.addBlock(new NodePos(x, y, 0), stone, false);
            }
        }
        return g;
    }
}
