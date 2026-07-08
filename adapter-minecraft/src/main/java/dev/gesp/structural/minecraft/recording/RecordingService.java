package dev.gesp.structural.minecraft.recording;

import dev.gesp.structural.minecraft.manager.StructureManager;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.World;

/**
 * Host-facing API for starting and stopping strux recordings programmatically.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                       RECORDING SERVICE                            │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Lets a dependent plugin (e.g. the Siege gamemode) auto-record      │
 *   │  arena matches and build sessions as SEPARATE, tagged recordings    │
 *   │  without going through the /strux record command.                   │
 *   │                                                                     │
 *   │  Registered with Bukkit's ServicesManager, and also reachable via   │
 *   │  StructuralIntegrityPlugin#getRecordingService().                   │
 *   │                                                                     │
 *   │  SCOPING / OVERLAP POLICY                                           │
 *   │  The underlying recorder records ONE session at a time (global).    │
 *   │  startRecording therefore throws IllegalStateException if a         │
 *   │  recording is already running — a match and a build session can't   │
 *   │  record simultaneously. Stop the active one first. This is enforced │
 *   │  rather than silently swapping sessions, which would corrupt the    │
 *   │  in-flight capture.                                                  │
 *   │                                                                     │
 *   │  FILE LAYOUT                                                        │
 *   │    recordings/<tag>/<tag>-<label>-<timestamp>.json                 │
 *   │    e.g. recordings/match/match-arena3-2026-06-04-14-32-15.json     │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public final class RecordingService {

    private static final DateTimeFormatter SESSION_NAME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss").withZone(ZoneId.systemDefault());

    private final MinecraftEventRecorder recorder;
    private final StructureManager structureManager;
    private final List<RecordingLifecycleListener> listeners = new CopyOnWriteArrayList<>();

    public RecordingService(MinecraftEventRecorder recorder, StructureManager structureManager) {
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.structureManager = Objects.requireNonNull(structureManager, "structureManager");
    }

    /**
     * Callback a dependent plugin (the Siege gamemode) registers to start/stop its own
     * parallel track file in lockstep with the strux anchor recording, on the same
     * timebase. This is the entire recording-side coupling — strux never stores the
     * dependent's data, it only announces when its anchor track opens and closes.
     */
    public interface RecordingLifecycleListener {

        /**
         * The strux anchor recording just started.
         *
         * @param sessionId   the anchor session id (the shared key the dependent track references)
         * @param startTimeMs the anchor start time (millis since epoch) — the shared timebase
         */
        default void onRecordingStarted(String sessionId, long startTimeMs) {}

        /**
         * The strux anchor recording just stopped.
         *
         * @param sessionId   the session id that stopped
         * @param startTimeMs the start time it ran on (the same timebase as {@code onRecordingStarted})
         */
        default void onRecordingStopped(String sessionId, long startTimeMs) {}
    }

    /** Register a lifecycle listener (idempotent in the sense that duplicates each fire). */
    public void addLifecycleListener(RecordingLifecycleListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Remove a previously-registered lifecycle listener. */
    public void removeLifecycleListener(RecordingLifecycleListener listener) {
        listeners.remove(listener);
    }

    /**
     * The start time (millis since epoch) of the active session, or {@code 0} when
     * nothing is recording. Exposed so a dependent plugin can stamp its parallel track
     * on the same clock as the strux anchor.
     */
    public long currentSessionStartMs() {
        return recorder.currentSessionStartMs();
    }

    /**
     * Start a tagged recording for the request's world.
     *
     * @param request what to record (tag, label, world, verify-on-stop)
     * @return a handle the caller can later {@link RecordingHandle#stop() stop}
     * @throws IllegalStateException if a recording is already in progress
     */
    public RecordingHandle startRecording(RecordingRequest request) {
        Objects.requireNonNull(request, "request");
        if (recorder.isRecording()) {
            throw new IllegalStateException("A recording is already in progress (" + recorder.currentSessionId()
                    + "); strux records one session at a time. Stop it before starting another.");
        }

        World world = request.world();
        String timestamp = SESSION_NAME_FORMAT.format(Instant.now());
        String sessionId = request.tag() + "/" + request.tag() + "-" + request.label() + "-" + timestamp;

        // getOrCreateGraph (not getGraph) so a brand-new/empty world still yields a
        // valid initial-state snapshot instead of a null graph.
        String assigned = recorder.startRecording(
                sessionId, world.getUID().toString(), structureManager.getOrCreateGraph(world), request.verifyOnStop());
        if (assigned == null) {
            // Lost a race with another starter between the check and the call.
            throw new IllegalStateException("A recording is already in progress; could not start " + sessionId);
        }
        // Stamp the host's opaque context (match id, rosters, actor names) onto the
        // session so the recording carries it for replay/analytics.
        recorder.setSessionContext(request.metadata(), request.actors());
        // Match recordings default to capturing schema-v3 stress snapshots (the debug
        // stress view is worth the extra work for a reviewed match); other tags keep the
        // global config default. A request can override either way.
        if (RecordingRequest.MATCH.equals(request.tag())) {
            recorder.setCaptureStress(true);
        }
        // Announce the anchor track so a dependent plugin can open its parallel track in
        // lockstep on the same timebase (sessionId + startTimeMs).
        long startTimeMs = recorder.currentSessionStartMs();
        for (RecordingLifecycleListener listener : listeners) {
            listener.onRecordingStarted(assigned, startTimeMs);
        }
        return new RecordingHandle(this, assigned, request.tag(), request.label());
    }

    /**
     * Stop the recording behind the given handle, if it is still the active one.
     *
     * @return true if this call stopped the recording; false if it had already
     *         stopped or been superseded by a different session
     */
    public boolean stopRecording(RecordingHandle handle) {
        Objects.requireNonNull(handle, "handle");
        if (!isActive(handle)) {
            return false;
        }
        // Capture the timebase before the stop clears the active session, so the
        // stopped callback carries the same (sessionId, startTimeMs) the start did.
        long startTimeMs = recorder.currentSessionStartMs();
        boolean stopped = recorder.stopRecording() != null;
        if (stopped) {
            for (RecordingLifecycleListener listener : listeners) {
                listener.onRecordingStopped(handle.sessionId(), startTimeMs);
            }
        }
        return stopped;
    }

    /**
     * Drop a named marker onto the handle's recording — a jump target the replay UI can
     * navigate to ("round start", "wall breached"). The Siege gamemode and tests use this
     * to inject domain events the physics engine knows nothing about.
     *
     * @param handle the active recording's handle
     * @param name   the marker name
     * @param meta   opaque key/value context (may be null/empty)
     * @return true if the marker was recorded; false if this handle is not the active session
     */
    public boolean mark(RecordingHandle handle, String name, Map<String, String> meta) {
        Objects.requireNonNull(handle, "handle");
        if (!isActive(handle)) {
            return false;
        }
        recorder.mark(name, meta);
        return true;
    }

    /** True while the handle's session is the recorder's active session. */
    public boolean isActive(RecordingHandle handle) {
        return recorder.isRecording() && handle.sessionId().equals(recorder.currentSessionId());
    }

    /** True if any recording (command- or service-started) is currently running. */
    public boolean isRecording() {
        return recorder.isRecording();
    }

    /** The id of the active session, or {@code null} if nothing is recording. */
    public String currentSessionId() {
        return recorder.currentSessionId();
    }
}
