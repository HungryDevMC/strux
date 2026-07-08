package dev.gesp.structural.recording;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.persistence.StructureData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A recording session containing metadata, initial state, and events.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                      RECORDING SESSION                             │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  sessionId: "session-2024-06-04-14-32-15"                          │
 *   │  startTimeMs: 1717516800000                                        │
 *   │  worldId: "00000000-0000-0000-0000-000000000000"                   │
 *   │                                                                     │
 *   │  initialState: {                                                   │
 *   │    worldId: "world",                                               │
 *   │    blocks: [...]    ← snapshot of graph at recording start         │
 *   │  }                                                                 │
 *   │                                                                     │
 *   │  events: [                                                         │
 *   │    { type: BLOCK_BREAK, sequenceId: 1, ... },                      │
 *   │    { type: BLAST, sequenceId: 2, ... },                            │
 *   │    ...                                                             │
 *   │  ]                                                                 │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class RecordingSession {

    /**
     * Current schema version. v2 added the {@link #physicsConfig} the session ran
     * under (so a replay re-simulates with the recording's physics, not the
     * server's current one), an {@link #engineVersion} stamp (to detect drift), and
     * opaque {@link #metadata} + {@link #actors} maps a game fills with its own
     * context (match id, arena, team rosters). v3 added {@link MarkerEvent} (named
     * jump targets a game/test injects) and an optional {@link StressDelta} payload
     * on break/blast events (load ratios that crossed a bucket boundary, for the
     * debug stress view). All additions are optional: a v1/v2 file loads with the
     * new fields null/empty.
     */
    public static final int SCHEMA_VERSION = 3;

    private int schemaVersion;
    private String sessionId;
    private long startTimeMs;
    private long endTimeMs;
    private String worldId;
    private StructureData initialState;
    private List<StruxEvent> events;

    /** The physics configuration the session ran under (null on a v1 file). */
    private PhysicsConfig physicsConfig;

    /** Engine version stamp, for detecting drift between record and replay (null on a v1 file). */
    private String engineVersion;

    /** Opaque game context (match id, arena, team rosters, …). The core never reads it. */
    private Map<String, String> metadata;

    /** Opaque actorId → display-name map, so a replay can label actors without a live lookup. */
    private Map<String, String> actors;

    /** Default constructor for deserialization. */
    public RecordingSession() {
        this.schemaVersion = SCHEMA_VERSION;
        this.events = new ArrayList<>();
        this.metadata = new LinkedHashMap<>();
        this.actors = new LinkedHashMap<>();
    }

    /**
     * Create a new recording session.
     *
     * @param sessionId    unique session identifier
     * @param startTimeMs  session start time (millis since epoch)
     * @param worldId      world/dimension identifier
     * @param initialState snapshot of the structure graph at recording start
     */
    public RecordingSession(String sessionId, long startTimeMs, String worldId, StructureData initialState) {
        this();
        this.sessionId = sessionId;
        this.startTimeMs = startTimeMs;
        this.worldId = worldId;
        this.initialState = initialState;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public long getStartTimeMs() {
        return startTimeMs;
    }

    public void setStartTimeMs(long startTimeMs) {
        this.startTimeMs = startTimeMs;
    }

    public long getEndTimeMs() {
        return endTimeMs;
    }

    public void setEndTimeMs(long endTimeMs) {
        this.endTimeMs = endTimeMs;
    }

    public String getWorldId() {
        return worldId;
    }

    public void setWorldId(String worldId) {
        this.worldId = worldId;
    }

    public StructureData getInitialState() {
        return initialState;
    }

    public void setInitialState(StructureData initialState) {
        this.initialState = initialState;
    }

    public List<StruxEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    public void setEvents(List<StruxEvent> events) {
        this.events = events != null ? new ArrayList<>(events) : new ArrayList<>();
    }

    public void addEvent(StruxEvent event) {
        this.events.add(event);
    }

    public int eventCount() {
        return events.size();
    }

    public PhysicsConfig getPhysicsConfig() {
        return physicsConfig;
    }

    public void setPhysicsConfig(PhysicsConfig physicsConfig) {
        this.physicsConfig = physicsConfig;
    }

    public String getEngineVersion() {
        return engineVersion;
    }

    public void setEngineVersion(String engineVersion) {
        this.engineVersion = engineVersion;
    }

    /** Opaque game context (never null; empty when unset). */
    public Map<String, String> getMetadata() {
        return metadata != null ? metadata : new LinkedHashMap<>();
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
    }

    /** Opaque actorId → display-name map (never null; empty when unset). */
    public Map<String, String> getActors() {
        return actors != null ? actors : new LinkedHashMap<>();
    }

    public void setActors(Map<String, String> actors) {
        this.actors = actors != null ? new LinkedHashMap<>(actors) : new LinkedHashMap<>();
    }

    /** Duration of the session in milliseconds (0 if not yet ended). */
    public long durationMs() {
        return endTimeMs > 0 ? endTimeMs - startTimeMs : 0;
    }

    @Override
    public String toString() {
        return "RecordingSession{sessionId='" + sessionId + "', events=" + events.size() + "}";
    }
}
