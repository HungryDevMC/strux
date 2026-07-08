package dev.gesp.structural.recording;

import dev.gesp.structural.model.NodePos;
import java.util.List;
import java.util.Map;

/**
 * Event recorded when an explosion occurs.
 *
 * @param timestampMs wall-clock time
 * @param sequenceId  monotonic sequence for replay ordering
 * @param center      explosion center position
 * @param power       explosion power (e.g., 4.0 for TNT)
 * @param shape       explosion shape identifier (e.g., "SPHERICAL", "DIRECTIONAL")
 * <p>{@code destroyed}, {@code collapsed} and {@code damaged} are pairwise
 * disjoint: a block is shattered, OR it fell, OR it survived weakened. A block
 * cracked then dropped by the same blast is listed only in {@code collapsed}.
 *
 * @param destroyed   positions of blocks destroyed directly by the blast
 * @param collapsed   positions of blocks that collapsed in the subsequent cascade
 * @param damaged     positions mapped to damage values (0.0-1.0) for blocks that survived but were weakened
 * @param actorId     opaque identifier of who set off the blast (e.g. the TNT igniter's
 *                    UUID), or {@code null} when the recording did not attribute it
 * @param stress      OPTIONAL stress snapshot (schema v3): the blocks whose load ratio
 *                    crossed a bucket boundary while this blast settled, or {@code null}
 *                    when stress capture was off. Costs nothing when absent.
 */
public record BlastEvent(
        long timestampMs,
        long sequenceId,
        NodePos center,
        double power,
        String shape,
        List<NodePos> destroyed,
        List<NodePos> collapsed,
        Map<NodePos, Double> damaged,
        String actorId,
        StressDelta stress)
        implements StruxEvent {

    public BlastEvent {
        destroyed = destroyed == null ? List.of() : List.copyOf(destroyed);
        collapsed = collapsed == null ? List.of() : List.copyOf(collapsed);
        damaged = damaged == null ? Map.of() : Map.copyOf(damaged);
    }

    /** Attributed blast with no stress snapshot — delegates with {@code stress = null}. */
    public BlastEvent(
            long timestampMs,
            long sequenceId,
            NodePos center,
            double power,
            String shape,
            List<NodePos> destroyed,
            List<NodePos> collapsed,
            Map<NodePos, Double> damaged,
            String actorId) {
        this(timestampMs, sequenceId, center, power, shape, destroyed, collapsed, damaged, actorId, null);
    }

    /** Unattributed blast (no actor): the pre-schema-v2 shape. */
    public BlastEvent(
            long timestampMs,
            long sequenceId,
            NodePos center,
            double power,
            String shape,
            List<NodePos> destroyed,
            List<NodePos> collapsed,
            Map<NodePos, Double> damaged) {
        this(timestampMs, sequenceId, center, power, shape, destroyed, collapsed, damaged, null, null);
    }

    @Override
    public EventType type() {
        return EventType.BLAST;
    }
}
