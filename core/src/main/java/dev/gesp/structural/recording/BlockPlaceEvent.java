package dev.gesp.structural.recording;

import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.model.ThermalClass;
import java.util.List;

/**
 * Event recorded when a block is placed.
 *
 * @param timestampMs     wall-clock time
 * @param sequenceId      monotonic sequence for replay ordering
 * @param pos             position of the placed block
 * @param materialId      material identifier (adapter-specific, e.g., "STONE")
 * @param mass            mass of the placed block
 * @param maxLoad         maximum load capacity of the placed block
 * @param blastResistance blast resistance of the placed block's material (1.0 = default)
 * @param fireResistance  fire resistance of the placed block's material (1.0 = default)
 * @param thermalClass    temperature-strength family of the placed block's material
 * @param grounded        whether the live place path anchored this block to ground. This
 *                        is the adapter's real grounding decision (a ground material, or a
 *                        foundation block on terrain), NOT a {@code y == 0} guess — replay
 *                        must reproduce it or a grounded anchor falls as FLOATING (or an
 *                        ordinary block at world y=0 becomes an infinite anchor)
 * @param collapsed       positions of blocks that collapsed due to overload from placement
 * @param actorId         opaque identifier of who placed the block (e.g. a player UUID),
 *                        or {@code null} when the recording did not attribute it
 */
public record BlockPlaceEvent(
        long timestampMs,
        long sequenceId,
        NodePos pos,
        String materialId,
        double mass,
        double maxLoad,
        double blastResistance,
        double fireResistance,
        ThermalClass thermalClass,
        boolean grounded,
        List<NodePos> collapsed,
        String actorId)
        implements StruxEvent {

    public BlockPlaceEvent {
        collapsed = collapsed == null ? List.of() : List.copyOf(collapsed);
        if (blastResistance <= 0.0) {
            blastResistance = 1.0;
        }
        if (fireResistance <= 0.0) {
            fireResistance = 1.0;
        }
        if (thermalClass == null) {
            thermalClass = ThermalClass.INERT;
        }
    }

    /**
     * Legacy place shape (no material spec beyond mass/maxLoad, no grounding flag):
     * blast/fire resistance default to 1.0, thermal class to INERT, and {@code grounded}
     * to {@code false} — the same non-anchored default the live place path uses for any
     * ordinary block.
     */
    public BlockPlaceEvent(
            long timestampMs,
            long sequenceId,
            NodePos pos,
            String materialId,
            double mass,
            double maxLoad,
            List<NodePos> collapsed,
            String actorId) {
        this(
                timestampMs,
                sequenceId,
                pos,
                materialId,
                mass,
                maxLoad,
                1.0,
                1.0,
                ThermalClass.INERT,
                false,
                collapsed,
                actorId);
    }

    /** Unattributed place (no actor): the pre-schema-v2 shape. */
    public BlockPlaceEvent(
            long timestampMs,
            long sequenceId,
            NodePos pos,
            String materialId,
            double mass,
            double maxLoad,
            List<NodePos> collapsed) {
        this(timestampMs, sequenceId, pos, materialId, mass, maxLoad, collapsed, null);
    }

    @Override
    public EventType type() {
        return EventType.BLOCK_PLACE;
    }
}
