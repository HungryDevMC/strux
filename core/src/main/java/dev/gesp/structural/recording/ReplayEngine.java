package dev.gesp.structural.recording;

import dev.gesp.structural.api.CollapseReason;
import dev.gesp.structural.api.DebugCapture;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.blast.BlastCallback;
import dev.gesp.structural.blast.BlastContext;
import dev.gesp.structural.blast.BlastDebugCapture;
import dev.gesp.structural.blast.BlastResult;
import dev.gesp.structural.blast.StruxExplosionEngine;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.persistence.StructureConverter;
import dev.gesp.structural.solver.CascadeEngine;
import dev.gesp.structural.solver.StressSolver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic replay engine for recorded sessions.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                       REPLAY ENGINE                                │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Replay process:                                                   │
 *   │                                                                     │
 *   │  1. Load RecordingSession from JSON                                │
 *   │  2. Restore StructureGraph from initialState                       │
 *   │  3. Sort events by sequenceId (canonical ordering)                 │
 *   │  4. Apply each event to the graph                                  │
 *   │  5. Compare outcomes with recorded outcomes                        │
 *   │  6. Report any divergences (indicates physics bug)                 │
 *   │                                                                     │
 *   │  Use cases:                                                        │
 *   │    • Bug reproduction: replay recorded session to reproduce issue  │
 *   │    • Determinism verification: ensure physics is deterministic     │
 *   │    • Visual playback: step-through with particle visualization     │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class ReplayEngine {

    private final PhysicsConfig config;
    private CascadeEngine cascadeEngine;
    private StruxExplosionEngine blastEngine;

    /**
     * Optional fine-grained capture sinks. While the engine re-applies each
     * recorded event, these receive every per-block outcome — direct destroys and
     * cracks (via {@link #captureBlast}), and reason-tagged cascade collapses (via
     * {@link #captureSolver}, including the scoped floater sweep the live blast
     * path runs). They default to {@code NONE}, so the verify path
     * (and its behaviour) is unchanged unless a caller opts in via
     * {@link #withCapture}. This is the seam the {@code replay} module uses to
     * build a frame-by-frame timeline without re-implementing the apply logic.
     */
    private SolverCallback captureSolver = SolverCallback.NONE;

    private BlastCallback captureBlast = BlastCallback.NONE;

    /**
     * Optional engine-debug capture sink. While re-simulating, after each event has
     * fully settled, the engine runs one extra full {@link StressSolver#solve} over
     * the post-event graph with this sink attached, harvesting the final settled
     * stress split, ground-distance field and load-flow edges the live path discards.
     * This is a re-sim-only cost (the live server never does it) and never changes a
     * collapse decision — the settle has already finished. Defaults to
     * {@link DebugCapture#NONE}; the replay module opts in via {@link #withDebugCapture}.
     */
    private DebugCapture debugCapture = DebugCapture.NONE;

    /**
     * Optional blast-geometry capture sink. Attached to the blast engine while a
     * recorded {@link BlastEvent} is re-applied, so the scanner reports its candidate
     * intensities, occlusion rays and falloff shells. Like {@link #debugCapture} this
     * is a re-sim-only cost that never changes a blast outcome (the scan is a pure
     * per-node function; capture only reads its values). Defaults to
     * {@link BlastDebugCapture#NONE}; the replay module opts in via
     * {@link #withBlastDebugCapture}.
     */
    private BlastDebugCapture blastDebugCapture = BlastDebugCapture.NONE;

    public ReplayEngine() {
        this(new PhysicsConfig());
    }

    public ReplayEngine(PhysicsConfig config) {
        this.config = config;
        this.cascadeEngine = new CascadeEngine(config);
        this.blastEngine = new StruxExplosionEngine(config);
    }

    /**
     * Install fine-grained capture sinks for the next {@link #replay} call. Pass
     * {@link SolverCallback#NONE}/{@link BlastCallback#NONE} to disable.
     *
     * @return {@code this}, for chaining before {@link #replay}
     */
    public ReplayEngine withCapture(SolverCallback solver, BlastCallback blast) {
        this.captureSolver = solver == null ? SolverCallback.NONE : solver;
        this.captureBlast = blast == null ? BlastCallback.NONE : blast;
        return this;
    }

    /**
     * Install an engine-debug capture sink for the next {@link #replay} call. When the
     * sink {@link DebugCapture#wantsDebugCapture() wants capture}, the engine runs one
     * extra full settle-pass solve per event (re-sim only) to feed it. Pass
     * {@link DebugCapture#NONE} (or {@code null}) to disable. Returns {@code this}.
     */
    public ReplayEngine withDebugCapture(DebugCapture capture) {
        this.debugCapture = capture == null ? DebugCapture.NONE : capture;
        return this;
    }

    /**
     * Install a blast-geometry capture sink for the next {@link #replay} call. When the
     * sink {@link BlastDebugCapture#wantsBlastCapture() wants capture}, each re-applied
     * blast reports its falloff shells, per-candidate intensities/outcomes and
     * occlusion rays. Pass {@link BlastDebugCapture#NONE} (or {@code null}) to disable.
     * Returns {@code this}.
     */
    public ReplayEngine withBlastDebugCapture(BlastDebugCapture capture) {
        this.blastDebugCapture = capture == null ? BlastDebugCapture.NONE : capture;
        return this;
    }

    /**
     * Replay a recorded session, verifying determinism.
     *
     * @param session  the recorded session to replay
     * @param listener callback for replay events (visualization, progress)
     * @return the replay result with any divergences
     */
    public ReplayResult replay(RecordingSession session, ReplayListener listener) {
        // Re-simulate under the SAME physics the session was recorded with — that is
        // the whole point of recording a PhysicsConfig (schema v2). A session recorded
        // with, say, a different moment multiplier or cascade cap must replay with that
        // config, or every config-sensitive collapse reads as a (false) divergence.
        // Pre-v2 sessions carry no config; fall back to the engine's own.
        PhysicsConfig effective = session.getPhysicsConfig() != null ? session.getPhysicsConfig() : config;
        this.cascadeEngine = new CascadeEngine(effective);
        this.blastEngine = new StruxExplosionEngine(effective).setBlastDebugCapture(blastDebugCapture);

        // A dedicated solver for the (re-sim-only) debug capture pass, built under the
        // same physics. Kept separate from the cascade engine's solver so capture never
        // touches a collapse decision — this solve only reads/writes stress values that
        // the next event would recompute anyway. Null unless a sink actually wants it.
        StressSolver debugSolver =
                debugCapture.wantsDebugCapture() ? new StressSolver(effective).setDebugCapture(debugCapture) : null;

        StructureGraph graph = StructureConverter.toGraph(session.getInitialState());
        List<Divergence> divergences = new ArrayList<>();
        List<InvariantViolation> violations = new ArrayList<>();

        List<StruxEvent> events = new ArrayList<>(session.getEvents());
        events.sort(Comparator.comparingLong(StruxEvent::sequenceId));

        listener.onReplayStart(session, graph);

        for (int i = 0; i < events.size(); i++) {
            StruxEvent event = events.get(i);
            listener.onEventStart(event, i, events.size());

            for (Divergence divergence : applyEvent(graph, event, listener)) {
                divergences.add(divergence);
                listener.onDivergence(divergence);
            }

            // Check physics invariants after each event
            List<InvariantViolation> eventViolations = checkInvariants(graph, event);
            for (InvariantViolation v : eventViolations) {
                violations.add(v);
                listener.onInvariantViolation(v);
            }

            // Re-sim-only engine-debug capture: re-solve the now-settled graph with the
            // debug sink attached, marked as the event's final pass so load-flow edges
            // are kept exactly once. The settle already finished above, so this changes
            // nothing — it only harvests the final stress split / ground field / edges.
            if (debugSolver != null) {
                debugSolver.setFinalPass(true).solveAll(graph);
            }

            listener.onEventComplete(event, i, events.size());
        }

        listener.onReplayComplete(divergences, violations);
        return new ReplayResult(session.getSessionId(), events.size(), divergences, violations, graph);
    }

    /**
     * Apply a single event to the graph and collect ALL of its divergences.
     *
     * <p>An event can diverge in more than one way at once (a blast's destroyed
     * set AND its collapsed set AND its damaged map). We collect every divergence
     * for the event rather than short-circuiting on the first, so one verify pass
     * surfaces the whole picture instead of hiding later mismatches behind an
     * early one.
     */
    private List<Divergence> applyEvent(StructureGraph graph, StruxEvent event, ReplayListener listener) {
        return switch (event) {
            case BlockBreakEvent e -> applyBlockBreak(graph, e, listener);
            case BlockPlaceEvent e -> applyBlockPlace(graph, e, listener);
            case BlastEvent e -> applyBlast(graph, e, listener);
            case ImpactEvent e -> applyImpact(graph, e, listener);
            case FireDamageEvent e -> applyFireDamage(graph, e, listener);
            case CascadeEvent e -> List.of(); // Cascade events are informational, not actions
            case MarkerEvent e -> List.of(); // Markers are labels, not physics actions
        };
    }

    private List<Divergence> applyBlockBreak(StructureGraph graph, BlockBreakEvent event, ReplayListener listener) {
        NodePos pos = event.pos();
        if (!graph.hasBlock(pos)) {
            return List.of(new Divergence(
                    event.sequenceId(), "BLOCK_BREAK", "Block not found at " + pos + " (already removed?)"));
        }

        graph.removeBlock(pos);

        // Settle the graph and collect collapsed blocks. The break path's settle
        // (the canonical cascade) already drains floaters and overloads; no extra
        // floating sweep is mirrored, matching the live break path.
        List<NodePos> actualCollapsed = settle(graph, event.actorId());

        return asList(checkCollapsed(event.sequenceId(), "BLOCK_BREAK", event.collapsed(), actualCollapsed));
    }

    private List<Divergence> applyBlockPlace(StructureGraph graph, BlockPlaceEvent event, ReplayListener listener) {
        NodePos pos = event.pos();
        if (graph.hasBlock(pos)) {
            return List.of(new Divergence(
                    event.sequenceId(), "BLOCK_PLACE", "Block already exists at " + pos + " (duplicate place?)"));
        }

        // Rebuild the full recorded spec (not just mass/maxLoad) so a later blast/
        // fire/temperature event on this block sees its real resistances and thermal
        // class. Anchor it exactly as the live place path did — replay the recorded
        // `grounded` decision, never a y==0 guess (a recorded ground/foundation anchor
        // at altitude must not fall, and an ordinary block at world y=0 must not become
        // an infinite anchor).
        MaterialSpec spec = new MaterialSpec(
                event.mass(), event.maxLoad(), event.blastResistance(), event.fireResistance(), event.thermalClass());
        graph.addBlock(pos, spec, event.grounded());

        // Settle and collect any overload collapses (no whole-world sweep — the
        // place path doesn't run one).
        List<NodePos> actualCollapsed = settle(graph, event.actorId());

        return asList(checkCollapsed(event.sequenceId(), "BLOCK_PLACE", event.collapsed(), actualCollapsed));
    }

    private List<Divergence> applyBlast(StructureGraph graph, BlastEvent event, ReplayListener listener) {
        BlastContext ctx = BlastContext.builder()
                .center(event.center())
                .power(event.power())
                .build();
        BlastResult result = blastEngine.process(graph, ctx, captureBlast);

        // The blast engine's settle already drains every floater in its affected
        // region (its FLOATING phase) before returning, so result.collapsed() is the
        // complete in-scope collapse. The live BlastProcessor follows it with a SCOPED
        // ground-refresh over that same region, which therefore finds nothing to add —
        // recorded `collapsed` equals result.collapsed(). So replay needs no follow-up
        // sweep. The old code ran a WHOLE-WORLD sweep here, which wrongly force-dropped
        // far, pre-existing floaters the blast never touched (a phantom collapse the
        // recording never had) — the exact false divergence this removes.
        List<NodePos> actualCollapsed = new ArrayList<>(result.collapsed());

        // The blast engine's raw `damaged` map is NOT disjoint from `collapsed`: a
        // block can be cracked AND then collapse in the same blast. The live
        // ExplosionListener makes the recorded sets disjoint by subtracting every
        // collapsed (incl. swept) block from damaged. Mirror that here so the
        // replayed damaged map is the same shape as the recorded one.
        Map<NodePos, Double> actualDamaged = new HashMap<>(result.damaged());
        actualCollapsed.forEach(actualDamaged::remove);

        // Collect ALL divergences for this one event: destroyed, collapsed, damaged.
        List<Divergence> divergences = new ArrayList<>();
        addIfPresent(
                divergences,
                checkCollapsed(event.sequenceId(), "BLAST_DESTROYED", event.destroyed(), result.destroyed()));
        addIfPresent(
                divergences, checkCollapsed(event.sequenceId(), "BLAST_COLLAPSED", event.collapsed(), actualCollapsed));
        addIfPresent(
                divergences,
                checkDamaged(event.sequenceId(), "BLAST_DAMAGED", event.damaged(), event.collapsed(), actualDamaged));
        return divergences;
    }

    private List<Divergence> applyImpact(StructureGraph graph, ImpactEvent event, ReplayListener listener) {
        NodePos pos = event.pos();
        if (!graph.hasBlock(pos)) {
            // Block may have been destroyed already - not necessarily a divergence
            return List.of();
        }

        // A penetrating impact removes more than the origin and cracks NON-origin
        // blocks along its path. Replaying the origin alone would drift the graph
        // and poison every later comparison, so reproduce the recorded path effects
        // when the recording carries them (a NEW recording). Old recordings have
        // empty path fields, so we fall back to the legacy origin-only behaviour.
        boolean hasPathInfo =
                !event.penetrated().isEmpty() || !event.pathDamage().isEmpty();
        if (hasPathInfo) {
            // Survivors: drive each to its recorded ABSOLUTE post-hit damage level.
            for (Map.Entry<NodePos, Double> entry : event.pathDamage().entrySet()) {
                setDamageAbsolute(graph, entry.getKey(), entry.getValue());
            }
            // Penetrated: drive to full damage, then remove (mirror ImpactEngine).
            for (NodePos p : event.penetrated()) {
                setDamageAbsolute(graph, p, 1.0);
                graph.removeBlock(p);
            }
        } else {
            Node node = graph.getNode(pos);
            if (node == null) {
                return List.of();
            }
            node.addDamage(event.damageDealt());
            if (event.destroyed()) {
                graph.removeBlock(pos);
            }
        }

        // Settle ONLY — the live ImpactProcessor runs no follow-up floating sweep
        // (the core ImpactEngine already settled the affected scope, and a truncated
        // collapse is finished by the resume manager across later ticks, landing in
        // informational CascadeEvents replay skips). A whole-world sweep here would
        // force-drop those resume leftovers and falsely diverge from the recording.
        List<NodePos> actualCollapsed = settle(graph, event.actorId());

        return asList(checkCollapsed(event.sequenceId(), "IMPACT", event.collapsed(), actualCollapsed));
    }

    private List<Divergence> applyFireDamage(StructureGraph graph, FireDamageEvent event, ReplayListener listener) {
        NodePos pos = event.pos();
        if (!graph.hasBlock(pos)) {
            return List.of(); // Block may be gone
        }

        Node node = graph.getNode(pos);
        if (node == null) {
            return List.of();
        }

        // Apply damage (fire damage is incremental)
        node.addDamage(event.damageDealt());

        if (event.destroyed()) {
            graph.removeBlock(pos);
        }

        // Settle and check (no whole-world sweep — the fire path runs cascade only).
        // Fire carries no actor, so its cascade frames stay unattributed.
        List<NodePos> actualCollapsed = settle(graph, null);

        return asList(checkCollapsed(event.sequenceId(), "FIRE_DAMAGE", event.collapsed(), actualCollapsed));
    }

    /**
     * Settle the graph through the cascade and return the collapsed positions.
     *
     * <p>The cascade was set off by the event currently being applied, so its
     * collapses belong to that event's actor. The core {@link CascadeEngine} doesn't
     * know about actors (it must stay unit-agnostic), so we tag the capture sink's
     * cascade steps with {@code actorId} here, where the triggering event is known —
     * threading the actor onto every cascade frame, not just the first block.
     *
     * @param actorId opaque id of who triggered this event, or {@code null} if none
     */
    private List<NodePos> settle(StructureGraph graph, String actorId) {
        return new ArrayList<>(cascadeEngine.settle(graph, attribute(captureSolver, actorId)).stream()
                .map(cn -> cn.pos())
                .toList());
    }

    /**
     * Wrap a capture sink so every {@code onCascadeStep} it receives is re-fired with
     * the triggering event's {@code actorId}. When there's no actor (or no real sink),
     * the original callback is returned unwrapped — the common, zero-cost case.
     */
    private static SolverCallback attribute(SolverCallback delegate, String actorId) {
        if (actorId == null || delegate == SolverCallback.NONE) {
            return delegate;
        }
        return new SolverCallback() {
            @Override
            public void onStressUpdated(Map<NodePos, Double> stressMap) {
                delegate.onStressUpdated(stressMap);
            }

            @Override
            public void onCascadeStep(CollapsedNode collapsed, int stepNumber, CollapseReason reason) {
                delegate.onCascadeStep(collapsed, stepNumber, reason, actorId);
            }

            @Override
            public void onCascadeComplete(List<CollapsedNode> allCollapsed) {
                delegate.onCascadeComplete(allCollapsed);
            }

            @Override
            public boolean wantsStressUpdates() {
                return delegate.wantsStressUpdates();
            }
        };
    }

    /** Drive the node at {@code pos} to an ABSOLUTE damage level (Node only exposes additive damage). */
    private void setDamageAbsolute(StructureGraph graph, NodePos pos, double level) {
        Node node = graph.getNode(pos);
        if (node != null) {
            node.addDamage(level - node.damage());
        }
    }

    private static List<Divergence> asList(Divergence divergence) {
        return divergence == null ? List.of() : List.of(divergence);
    }

    private static void addIfPresent(List<Divergence> divergences, Divergence divergence) {
        if (divergence != null) {
            divergences.add(divergence);
        }
    }

    /**
     * Check if the actual collapsed positions match the expected ones.
     */
    private Divergence checkCollapsed(long sequenceId, String eventType, List<NodePos> expected, List<NodePos> actual) {
        Set<NodePos> expectedSet = new HashSet<>(expected);
        Set<NodePos> actualSet = new HashSet<>(actual);

        if (!expectedSet.equals(actualSet)) {
            Set<NodePos> missing = new HashSet<>(expectedSet);
            missing.removeAll(actualSet);

            Set<NodePos> extra = new HashSet<>(actualSet);
            extra.removeAll(expectedSet);

            StringBuilder msg = new StringBuilder("Collapsed mismatch: ");
            if (!missing.isEmpty()) {
                msg.append("expected ").append(missing.size()).append(" more collapses");
            }
            if (!extra.isEmpty()) {
                if (!missing.isEmpty()) {
                    msg.append(", ");
                }
                msg.append("got ").append(extra.size()).append(" unexpected collapses");
            }

            return new Divergence(sequenceId, eventType, msg.toString());
        }

        return null;
    }

    /**
     * Check the recorded damaged map against the replayed one.
     *
     * <p>Comparison is exact: the recorded damage values are the engine's own
     * deterministic float results, so replay recomputes bit-identical values and
     * any difference is a real divergence (a wrong position, or a wrong level).
     *
     * <p>LEGACY accommodation: recordings made before the morning fix listed every
     * collapsed block in {@code damaged} too (the sets were not disjoint). Those
     * stale entries are not survivors and replay never reproduces them, so we
     * subtract the recorded {@code collapsed} set from the recorded {@code damaged}
     * set before comparing — an old file is not flagged for that overlap alone.
     */
    private Divergence checkDamaged(
            long sequenceId,
            String eventType,
            Map<NodePos, Double> recordedDamaged,
            List<NodePos> recordedCollapsed,
            Map<NodePos, Double> actualDamaged) {
        Set<NodePos> collapsedSet = new HashSet<>(recordedCollapsed);

        Set<NodePos> expectedPositions = new HashSet<>(recordedDamaged.keySet());
        expectedPositions.removeAll(collapsedSet); // drop legacy collapsed∩damaged overlap

        Set<NodePos> actualPositions = new HashSet<>(actualDamaged.keySet());

        Set<NodePos> missing = new HashSet<>(expectedPositions);
        missing.removeAll(actualPositions);

        Set<NodePos> extra = new HashSet<>(actualPositions);
        extra.removeAll(expectedPositions);

        // Of the positions present in BOTH, find any whose recorded level differs.
        int valueMismatches = 0;
        for (NodePos pos : expectedPositions) {
            Double actual = actualDamaged.get(pos);
            if (actual != null && !actual.equals(recordedDamaged.get(pos))) {
                valueMismatches++;
            }
        }

        if (missing.isEmpty() && extra.isEmpty() && valueMismatches == 0) {
            return null;
        }

        StringBuilder msg = new StringBuilder("Damaged mismatch: ");
        List<String> parts = new ArrayList<>();
        if (!missing.isEmpty()) {
            parts.add("expected " + missing.size() + " more cracked block(s)");
        }
        if (!extra.isEmpty()) {
            parts.add("got " + extra.size() + " unexpected cracked block(s)");
        }
        if (valueMismatches > 0) {
            parts.add(valueMismatches + " block(s) cracked to a different level");
        }
        msg.append(String.join(", ", parts));
        return new Divergence(sequenceId, eventType, msg.toString());
    }

    // ─────────────────────────────────────────────────────────────────────
    //  INVARIANT CHECKS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Check physics invariants after an event. Returns any violations found.
     *
     * <p>Uses the graph's incremental chunk-connectivity index via
     * {@link StructureGraph#getFloatingBlocks()} instead of a hand-rolled whole-graph
     * BFS, so the cost is an O(V) index scan rather than O(V+E) BFS plus two O(V)
     * scans. The result is byte-identical — {@code getFloatingBlocks()} is documented
     * and tested as exact-equivalent to {@code nodes − BFS(ground)}.
     */
    private List<InvariantViolation> checkInvariants(StructureGraph graph, StruxEvent event) {
        List<InvariantViolation> violations = new ArrayList<>();

        Set<NodePos> floating = graph.getFloatingBlocks();
        if (!floating.isEmpty()) {
            violations.add(new InvariantViolation(
                    event.sequenceId(),
                    "FLOATING_BLOCKS",
                    floating.size() + " blocks have no path to ground",
                    floating));
        }

        return violations;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  RECORDS AND INTERFACES
    // ─────────────────────────────────────────────────────────────────────

    /**
     * A divergence between recorded and replayed behavior.
     */
    public record Divergence(long sequenceId, String eventType, String message) {}

    /**
     * A physics invariant violation detected during replay.
     */
    public record InvariantViolation(long sequenceId, String type, String message, Set<NodePos> affectedPositions) {}

    /**
     * Result of a replay operation.
     */
    public record ReplayResult(
            String sessionId,
            int eventsReplayed,
            List<Divergence> divergences,
            List<InvariantViolation> violations,
            StructureGraph finalGraph) {

        public boolean isDeterministic() {
            return divergences.isEmpty();
        }

        public boolean hasInvariantViolations() {
            return !violations.isEmpty();
        }

        public boolean isFullyValid() {
            return isDeterministic() && !hasInvariantViolations();
        }
    }

    /**
     * Callback interface for replay visualization and progress.
     */
    public interface ReplayListener {

        ReplayListener NONE = new ReplayListener() {};

        default void onReplayStart(RecordingSession session, StructureGraph initialGraph) {}

        default void onEventStart(StruxEvent event, int index, int total) {}

        default void onEventComplete(StruxEvent event, int index, int total) {}

        default void onDivergence(Divergence divergence) {}

        default void onInvariantViolation(InvariantViolation violation) {}

        default void onReplayComplete(List<Divergence> divergences, List<InvariantViolation> violations) {}
    }
}
