package dev.gesp.structural.recording;

import dev.gesp.structural.model.NodePos;
import java.util.List;

/**
 * Event recorded when a block is broken (by player, piston, or other means).
 *
 * @param timestampMs wall-clock time
 * @param sequenceId  monotonic sequence for replay ordering
 * @param pos         position of the broken block
 * @param materialId  material identifier (adapter-specific, e.g., "STONE")
 * @param collapsed   positions of blocks that collapsed as a result
 * @param actorId     opaque identifier of who caused this break (e.g. a player UUID),
 *                    or {@code null} when the recording did not attribute it. The core
 *                    never interprets it — it is a tag an adapter fills and analytics
 *                    groups by.
 * @param stress      OPTIONAL stress snapshot (schema v3): the blocks whose load ratio
 *                    crossed a bucket boundary while this break settled, or {@code null}
 *                    when stress capture was off. Costs nothing when absent.
 */
public record BlockBreakEvent(
        long timestampMs,
        long sequenceId,
        NodePos pos,
        String materialId,
        List<NodePos> collapsed,
        String actorId,
        StressDelta stress)
        implements StruxEvent {

    public BlockBreakEvent {
        collapsed = collapsed == null ? List.of() : List.copyOf(collapsed);
    }

    /** Attributed break with no stress snapshot — delegates with {@code stress = null}. */
    public BlockBreakEvent(
            long timestampMs,
            long sequenceId,
            NodePos pos,
            String materialId,
            List<NodePos> collapsed,
            String actorId) {
        this(timestampMs, sequenceId, pos, materialId, collapsed, actorId, null);
    }

    /** Unattributed break (no actor): the pre-schema-v2 shape. */
    public BlockBreakEvent(long timestampMs, long sequenceId, NodePos pos, String materialId, List<NodePos> collapsed) {
        this(timestampMs, sequenceId, pos, materialId, collapsed, null, null);
    }

    @Override
    public EventType type() {
        return EventType.BLOCK_BREAK;
    }
}
