package dev.gesp.structural.recording;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An arbitrary named marker injected into a recording by a caller.
 *
 * <p>Unlike the physics events (break, blast, …) a marker is not something the
 * engine did — it is a label a game or a test drops onto the timeline to say
 * "something interesting happened here". The Siege gamemode injects markers like
 * {@code "wall breached"} or {@code "round start"}; tests inject markers to assert
 * against. Later phases turn markers into jump targets in the replay UI.
 *
 * <p>The core stays game-type-free: a marker is just a {@link String} name plus an
 * opaque {@code Map<String,String>} of metadata that the engine never reads.
 *
 * @param timestampMs wall-clock time
 * @param sequenceId  monotonic sequence for replay ordering
 * @param name        human-readable marker name (e.g. {@code "round start"})
 * @param meta        opaque key/value context the caller attaches (never null)
 */
public record MarkerEvent(long timestampMs, long sequenceId, String name, Map<String, String> meta)
        implements StruxEvent {

    public MarkerEvent {
        name = name == null ? "" : name;
        meta = meta == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(meta));
    }

    @Override
    public EventType type() {
        return EventType.MARKER;
    }
}
