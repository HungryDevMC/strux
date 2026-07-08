package dev.gesp.structural.recording.io;

import dev.gesp.structural.recording.StruxEvent;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The footer of a {@code .strx} file: a small summary that lets a tool list and
 * seek a recording <em>without</em> parsing the whole event stream.
 *
 * <p>It carries, for the file it sits at the end of:
 *
 * <ul>
 *   <li>{@link #eventCount} — how many events are in the stream;</li>
 *   <li>{@link #typeCounts} — how many of each event kind (so a list view can show
 *       "12 blasts, 340 breaks" at a glance);</li>
 *   <li>{@link #durationMs} — the session's wall-clock length;</li>
 *   <li>{@link #actorEventCounts} — per-actor event tallies (a cheap per-player
 *       summary for a match list);</li>
 *   <li>{@link #checkpointOffsets} — the byte offset of every {@link #stride Nth}
 *       event in the (uncompressed) event stream, so a reader can jump near a target
 *       event and decode forward a little instead of from the start.</li>
 * </ul>
 *
 * <p>Pure data, no game types.
 *
 * @param eventCount       number of events in the stream
 * @param typeCounts       event kind → count
 * @param durationMs       session duration in milliseconds
 * @param actorEventCounts actorId → number of events attributed to it
 * @param stride           every {@code stride}-th event has a checkpoint offset
 * @param checkpointOffsets byte offsets (into the uncompressed event stream) of
 *                          events 0, stride, 2*stride, …
 */
public record SessionIndex(
        int eventCount,
        Map<StruxEvent.EventType, Integer> typeCounts,
        long durationMs,
        Map<String, Integer> actorEventCounts,
        int stride,
        List<Long> checkpointOffsets) {

    public SessionIndex {
        typeCounts = typeCounts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(typeCounts));
        actorEventCounts = actorEventCounts == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(actorEventCounts));
        checkpointOffsets = checkpointOffsets == null ? List.of() : List.copyOf(checkpointOffsets);
        if (stride < 1) {
            stride = 1;
        }
    }
}
