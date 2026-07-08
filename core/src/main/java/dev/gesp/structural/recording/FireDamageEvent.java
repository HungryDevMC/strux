package dev.gesp.structural.recording;

import dev.gesp.structural.model.NodePos;
import java.util.List;

/**
 * Event recorded when fire damages a block.
 *
 * @param timestampMs  wall-clock time
 * @param sequenceId   monotonic sequence for replay ordering
 * @param pos          position of the damaged block
 * @param materialId   material identifier of the block
 * @param damageDealt  damage applied this tick (0.0-1.0)
 * @param totalDamage  total accumulated damage on the block after this tick
 * @param destroyed    whether the fire destroyed the block
 * @param collapsed    positions of blocks that collapsed as a result
 */
public record FireDamageEvent(
        long timestampMs,
        long sequenceId,
        NodePos pos,
        String materialId,
        double damageDealt,
        double totalDamage,
        boolean destroyed,
        List<NodePos> collapsed)
        implements StruxEvent {

    public FireDamageEvent {
        collapsed = collapsed == null ? List.of() : List.copyOf(collapsed);
    }

    @Override
    public EventType type() {
        return EventType.FIRE_DAMAGE;
    }
}
