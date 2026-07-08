package dev.gesp.structural.recording;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.persistence.StructureConverter;
import dev.gesp.structural.recording.ReplayEngine.ReplayListener;
import dev.gesp.structural.recording.ReplayEngine.ReplayResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Renders a recorded session's re-simulated outcome as a deterministic, plain-text snapshot — the
 * session-level analogue of the scenario test harness's {@code ScenarioOutcome.toSnapshotText()}.
 *
 * <p>It re-simulates the whole event stream through {@link ReplayEngine} (using the recording's own
 * physics config) and reports which blocks the recording removed from the world, sorted by position
 * so the text is stable across runs. This is what lets a recorded span become a checked-in regression
 * golden: the {@code replay-cli export-test} command writes this text next to a trimmed {@code .strx}
 * fixture, and a core discovery test re-derives it and fails if a physics change shifts the outcome.
 */
public final class RecordedSessionSnapshot {

    private static final Comparator<NodePos> ORDER =
            Comparator.comparingInt(NodePos::x).thenComparingInt(NodePos::y).thenComparingInt(NodePos::z);

    private RecordedSessionSnapshot() {}

    /** Re-simulate {@code session} and render its outcome as deterministic snapshot text. */
    public static String toText(RecordingSession session) {
        StructureGraph initial = StructureConverter.toGraph(session.getInitialState());
        Set<NodePos> before = initial.getAllPositions();

        PhysicsConfig config = session.getPhysicsConfig();
        ReplayEngine engine = config != null ? new ReplayEngine(config) : new ReplayEngine();
        ReplayResult result = engine.replay(session, ReplayListener.NONE);
        Set<NodePos> after = result.finalGraph().getAllPositions();

        List<NodePos> removed = new ArrayList<>();
        for (NodePos p : before) {
            if (!after.contains(p)) {
                removed.add(p);
            }
        }
        removed.sort(ORDER);

        StringBuilder sb = new StringBuilder();
        sb.append("session: ").append(session.getSessionId()).append('\n');
        sb.append("events: ").append(session.eventCount()).append('\n');
        sb.append("initial: ").append(before.size()).append('\n');
        sb.append("removed: ").append(removed.size()).append('\n');
        sb.append("survivors: ").append(after.size()).append('\n');
        appendPositions(sb, "removed-positions", removed);
        return sb.toString();
    }

    private static void appendPositions(StringBuilder sb, String label, List<NodePos> positions) {
        sb.append(label).append(':');
        if (positions.isEmpty()) {
            sb.append(" (none)\n");
            return;
        }
        sb.append('\n');
        for (NodePos p : positions) {
            sb.append("  (")
                    .append(p.x())
                    .append(',')
                    .append(p.y())
                    .append(',')
                    .append(p.z())
                    .append(")\n");
        }
    }
}
