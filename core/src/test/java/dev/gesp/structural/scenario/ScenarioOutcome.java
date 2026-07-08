package dev.gesp.structural.scenario;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.metrics.StruxMetrics;
import dev.gesp.structural.model.NodePos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The captured result of running one {@link Scenario}: what was destroyed,
 * what collapsed, what survived, and how much work the engine did.
 *
 * <p>Two faces:
 * <ul>
 *   <li>{@link #toSnapshotText()} — a deterministic, sorted fingerprint of the
 *       <em>behaviour</em> (which blocks met which fate). This is what the golden
 *       snapshots diff against, so a refactor that changes the outcome fails.</li>
 *   <li>accessors like {@link #survives}, {@link #metrics} — for hand-written
 *       invariant assertions and the performance gate.</li>
 * </ul>
 *
 * <p>Block-removal <em>order</em> is intentionally left out of the snapshot: it
 * can depend on hash iteration, while the <em>set</em> of removed blocks is the
 * deterministic, meaningful outcome. Lists are sorted by (x, y, z).
 */
public final class ScenarioOutcome {

    private static final Comparator<NodePos> ORDER =
            Comparator.comparingInt(NodePos::x).thenComparingInt(NodePos::y).thenComparingInt(NodePos::z);

    private final String trigger;
    private final int initialSize;
    private final List<NodePos> destroyed; // blast crater (empty for a break)
    private final List<NodePos> collapsed; // secondary structural cascade
    private final List<NodePos> damagedSurvivors; // still standing but cracked (blast)
    private final StructureGraph finalGraph;
    private final StruxMetrics metrics;

    ScenarioOutcome(
            String trigger,
            int initialSize,
            List<NodePos> destroyed,
            List<NodePos> collapsed,
            List<NodePos> damagedSurvivors,
            StructureGraph finalGraph,
            StruxMetrics metrics) {
        this.trigger = trigger;
        this.initialSize = initialSize;
        this.destroyed = sorted(destroyed);
        this.collapsed = sorted(collapsed);
        this.damagedSurvivors = sorted(damagedSurvivors);
        this.finalGraph = finalGraph;
        this.metrics = metrics;
    }

    private static List<NodePos> sorted(List<NodePos> in) {
        List<NodePos> out = new ArrayList<>(in);
        out.sort(ORDER);
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  ACCESSORS — for invariant assertions and the performance gate
    // ─────────────────────────────────────────────────────────────────────

    /** Total blocks removed from the structure (crater + cascade). */
    public int removedCount() {
        return destroyed.size() + collapsed.size();
    }

    /** Blocks still present in the structure after settling. */
    public int survivorCount() {
        return finalGraph.size();
    }

    /** Does a block still stand at this position? */
    public boolean survives(NodePos pos) {
        return finalGraph.hasBlock(pos);
    }

    public List<NodePos> destroyed() {
        return destroyed;
    }

    public List<NodePos> collapsed() {
        return collapsed;
    }

    public int initialSize() {
        return initialSize;
    }

    public StruxMetrics metrics() {
        return metrics;
    }

    public StructureGraph finalGraph() {
        return finalGraph;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SNAPSHOT — the deterministic behavioural fingerprint
    // ─────────────────────────────────────────────────────────────────────

    public String toSnapshotText() {
        StringBuilder sb = new StringBuilder();
        sb.append("trigger: ").append(trigger).append('\n');
        sb.append("initial: ").append(initialSize).append('\n');
        sb.append("destroyed: ").append(destroyed.size()).append('\n');
        sb.append("collapsed: ").append(collapsed.size()).append('\n');
        sb.append("survivors: ").append(survivorCount()).append('\n');
        appendPositions(sb, "destroyed-positions", destroyed);
        appendPositions(sb, "collapsed-positions", collapsed);
        appendPositions(sb, "damaged-survivor-positions", damagedSurvivors);
        return sb.toString();
    }

    private static void appendPositions(StringBuilder sb, String label, List<NodePos> positions) {
        sb.append(label).append(":");
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
