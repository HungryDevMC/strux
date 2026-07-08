package dev.gesp.structural.recording;

import dev.gesp.structural.model.NodePos;
import java.util.List;

/**
 * Event recorded when a structural cascade occurs.
 *
 * <p>This captures the full cascade chain for replay and analysis. Each step
 * in the cascade represents one block that failed due to being floating or overloaded.
 *
 * @param timestampMs wall-clock time
 * @param sequenceId  monotonic sequence for replay ordering
 * @param trigger     position of the initial trigger (broken/destroyed block)
 * @param reason      why the cascade started (e.g., "BREAK", "BLAST", "FIRE")
 * @param steps       ordered list of cascade steps showing collapse progression
 */
public record CascadeEvent(long timestampMs, long sequenceId, NodePos trigger, String reason, List<CascadeStep> steps)
        implements StruxEvent {

    public CascadeEvent {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    @Override
    public EventType type() {
        return EventType.CASCADE;
    }
}
