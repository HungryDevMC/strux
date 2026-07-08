package dev.gesp.structural.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.api.CollapseReason;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.blast.BlastCallback;
import dev.gesp.structural.blast.BlastContext;
import dev.gesp.structural.blast.BlastResult;
import dev.gesp.structural.blast.StruxExplosionEngine;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.persistence.StructureConverter;
import dev.gesp.structural.persistence.StructureData;
import dev.gesp.structural.recording.ReplayEngine.ReplayResult;
import dev.gesp.structural.solver.CascadeEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The frame-capture seam on {@link ReplayEngine}: while it re-applies a recorded
 * session it can feed every per-block outcome to capture sinks — direct destroys
 * and cracks through the {@link BlastCallback}, reason-tagged cascade collapses
 * (including the whole-world floater sweep) through a
 * {@link dev.gesp.structural.api.SolverCallback}. This is the hook the replay
 * module turns into a frame-by-frame timeline. Without {@code withCapture} the
 * verify path is byte-for-byte unchanged, which the first test pins.
 */
@DisplayName("ReplayEngine: optional frame capture")
class ReplayCaptureTest {

    private static final MaterialSpec STRONG = new MaterialSpec(1.0, 1000.0);

    private final PhysicsConfig config = new PhysicsConfig();

    /** Sink that records what the blast re-simulation reported, split by kind. */
    private static final class Capture implements BlastCallback, SolverCallback {
        final List<NodePos> destroyed = new ArrayList<>();
        final Map<NodePos, Double> cracked = new HashMap<>();
        final List<NodePos> collapsed = new ArrayList<>();
        final List<CollapseReason> collapseReasons = new ArrayList<>();
        final List<NodePos> sweptByCascade = new ArrayList<>();

        @Override
        public void onDirectDestroy(NodePos pos) {
            destroyed.add(pos);
        }

        @Override
        public void onDamaged(NodePos pos, double damage) {
            cracked.put(pos, damage);
        }

        @Override
        public void onCollapse(NodePos pos) {
            collapsed.add(pos);
        }

        @Override
        public void onCollapse(NodePos pos, CollapseReason reason) {
            collapsed.add(pos);
            collapseReasons.add(reason);
        }

        // SolverCallback side — the whole-world floater sweep routes through here.
        @Override
        public void onStressUpdated(Map<NodePos, Double> stressMap) {}

        @Override
        public void onCascadeStep(CollapsedNode node, int stepNumber, CollapseReason reason) {
            sweptByCascade.add(node.pos());
        }

        // Attribution-carrying cascade steps land here when the replay engine knows
        // the triggering event's actor.
        final Map<NodePos, String> cascadeActors = new HashMap<>();

        @Override
        public void onCascadeStep(CollapsedNode node, int stepNumber, CollapseReason reason, String actorId) {
            sweptByCascade.add(node.pos());
            cascadeActors.put(node.pos(), actorId);
        }

        @Override
        public void onCascadeComplete(List<CollapsedNode> all) {}
    }

    @Test
    @DisplayName("a break's cascade steps carry the breaking actor through to the capture sink")
    void cascadeStepsCarryTheTriggeringActor() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 6; y++) {
            g.addBlock(new NodePos(0, y, 0), STRONG, false);
        }
        for (int x = 1; x <= 5; x++) {
            g.addBlock(new NodePos(x, 6, 0), STRONG, false);
        }
        StructureData initial = StructureConverter.toData(g, "w");
        // Player "bob" breaks the pillar base; the arm floats and cascades.
        g.removeBlock(new NodePos(0, 1, 0));
        List<NodePos> collapsed = new ArrayList<>();
        for (CollapsedNode cn : new CascadeEngine(config).settle(g, SolverCallback.NONE)) {
            collapsed.add(cn.pos());
        }
        BlockBreakEvent event = new BlockBreakEvent(0L, 1L, new NodePos(0, 1, 0), "STONE", collapsed, "bob");

        RecordingSession session = sessionOf(initial, event);
        Capture cap = new Capture();
        ReplayResult result =
                new ReplayEngine(config).withCapture(cap, cap).replay(session, ReplayEngine.ReplayListener.NONE);

        assertTrue(result.isDeterministic(), "real engine output must replay deterministically");
        assertFalse(cap.cascadeActors.isEmpty(), "the cascade fired steps");
        for (Map.Entry<NodePos, String> e : cap.cascadeActors.entrySet()) {
            assertEquals("bob", e.getValue(), "every cascade step must carry the breaker: " + e.getKey());
        }
    }

    @Test
    @DisplayName("an unattributed break leaves cascade steps without an actor")
    void unattributedBreakLeavesCascadeStepsActorless() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 6; y++) {
            g.addBlock(new NodePos(0, y, 0), STRONG, false);
        }
        for (int x = 1; x <= 5; x++) {
            g.addBlock(new NodePos(x, 6, 0), STRONG, false);
        }
        StructureData initial = StructureConverter.toData(g, "w");
        g.removeBlock(new NodePos(0, 1, 0));
        List<NodePos> collapsed = new ArrayList<>();
        for (CollapsedNode cn : new CascadeEngine(config).settle(g, SolverCallback.NONE)) {
            collapsed.add(cn.pos());
        }
        // No actor: the 3-arg onCascadeStep path (no wrapper) is exercised.
        BlockBreakEvent event = new BlockBreakEvent(0L, 1L, new NodePos(0, 1, 0), "STONE", collapsed);

        RecordingSession session = sessionOf(initial, event);
        Capture cap = new Capture();
        new ReplayEngine(config).withCapture(cap, cap).replay(session, ReplayEngine.ReplayListener.NONE);

        assertFalse(cap.sweptByCascade.isEmpty(), "the cascade still fired steps");
        assertTrue(cap.cascadeActors.isEmpty(), "without an actor, the 4-arg attribution hook is never used");
    }

    @Test
    @DisplayName("a blast replay feeds destroyed + collapsed blocks to the capture sink")
    void captureSinkSeesBlastOutcomes() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 6; y++) {
            g.addBlock(new NodePos(0, y, 0), STRONG, false);
        }
        for (int x = 1; x <= 5; x++) {
            g.addBlock(new NodePos(x, 6, 0), STRONG, false);
        }
        StructureData initial = StructureConverter.toData(g, "w");
        BlastEvent event = recordBlast(g, new NodePos(0, 1, 0), 3.0);

        RecordingSession session = sessionOf(initial, event);
        Capture cap = new Capture();
        ReplayResult result =
                new ReplayEngine(config).withCapture(cap, cap).replay(session, ReplayEngine.ReplayListener.NONE);

        assertTrue(result.isDeterministic(), "the recording is real engine output — replay must reproduce it");
        assertFalse(cap.destroyed.isEmpty(), "the crater blocks must reach the capture sink");
        assertEquals(event.destroyed().size(), cap.destroyed.size(), "every directly-destroyed block is captured once");
        // The arm floated: those collapses surface either through the blast cascade
        // hook or the whole-world sweep — together they cover the recorded set.
        Set<NodePos> capturedCollapses = new java.util.HashSet<>(cap.collapsed);
        capturedCollapses.addAll(cap.sweptByCascade);
        assertTrue(capturedCollapses.containsAll(event.collapsed()), "every collapsed block is captured");
    }

    @Test
    @DisplayName("without withCapture, replay is unchanged and still verifies deterministic")
    void noCaptureLeavesVerifyUnchanged() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 6; y++) {
            g.addBlock(new NodePos(0, y, 0), STRONG, false);
        }
        for (int x = 1; x <= 5; x++) {
            g.addBlock(new NodePos(x, 6, 0), STRONG, false);
        }
        StructureData initial = StructureConverter.toData(g, "w");
        BlastEvent event = recordBlast(g, new NodePos(0, 1, 0), 3.0);

        RecordingSession session = sessionOf(initial, event);
        ReplayResult result = new ReplayEngine(config).replay(session, ReplayEngine.ReplayListener.NONE);

        assertTrue(result.isFullyValid(), "the default (no-capture) verify path is unaffected");
    }

    @Test
    @DisplayName("null capture sinks fall back to NONE (no crash)")
    void nullCaptureFallsBackToNone() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), STRONG, false);
        StructureData initial = StructureConverter.toData(g, "w");
        BlastEvent event = recordBlast(g, new NodePos(0, 1, 0), 3.0);

        RecordingSession session = sessionOf(initial, event);
        ReplayResult result =
                new ReplayEngine(config).withCapture(null, null).replay(session, ReplayEngine.ReplayListener.NONE);

        assertTrue(result.isDeterministic(), "null sinks must behave like NONE");
    }

    private RecordingSession sessionOf(StructureData initial, StruxEvent... events) {
        RecordingSession session = new RecordingSession("test", 0L, "w", initial);
        for (StruxEvent e : events) {
            session.addEvent(e);
        }
        return session;
    }

    /** Record a blast the way the live ExplosionListener does (engine + whole-world sweep, disjoint sets). */
    private BlastEvent recordBlast(StructureGraph graph, NodePos center, double power) {
        StruxExplosionEngine engine = new StruxExplosionEngine(config);
        BlastResult result = engine.process(
                graph, BlastContext.builder().center(center).power(power).build());

        List<NodePos> allCollapsed = new ArrayList<>(result.collapsed());
        allCollapsed.addAll(sweepFloaters(graph));

        Map<NodePos, Double> damaged = new HashMap<>(result.damaged());
        allCollapsed.forEach(damaged::remove);
        return new BlastEvent(
                0L, 1L, center, power, "SPHERE", new ArrayList<>(result.destroyed()), allCollapsed, damaged);
    }

    private List<NodePos> sweepFloaters(StructureGraph graph) {
        List<NodePos> swept = new ArrayList<>();
        Set<NodePos> floating;
        while (!(floating = graph.getFloatingBlocks()).isEmpty()) {
            for (NodePos pos : floating) {
                graph.removeBlock(pos);
                swept.add(pos);
            }
        }
        return swept;
    }
}
