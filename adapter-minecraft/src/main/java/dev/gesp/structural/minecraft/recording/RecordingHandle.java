package dev.gesp.structural.minecraft.recording;

/**
 * A handle to a recording started via {@link RecordingService#startRecording}.
 *
 * <p>Holds the assigned session id (including its on-disk {@code tag/} prefix)
 * and lets the caller stop <em>this</em> session. Because the underlying
 * recorder is single-session/global, {@link #stop()} only stops if this handle
 * is still the active recording — stopping is idempotent and safe to call
 * twice.
 */
public final class RecordingHandle {

    private final RecordingService service;
    private final String sessionId;
    private final String tag;
    private final String label;

    RecordingHandle(RecordingService service, String sessionId, String tag, String label) {
        this.service = service;
        this.sessionId = sessionId;
        this.tag = tag;
        this.label = label;
    }

    /** The full session id, e.g. {@code "match/match-arena3-2026-06-04-14-32-15"}. */
    public String sessionId() {
        return sessionId;
    }

    /** The category/tag this recording was started under (e.g. {@code "match"}). */
    public String tag() {
        return tag;
    }

    /** The human-friendly label this recording was started with (e.g. {@code "arena3"}). */
    public String label() {
        return label;
    }

    /**
     * Stop this recording if it is still the active one. Idempotent: a no-op if
     * this session has already been stopped or superseded.
     *
     * @return true if this call actually stopped the recording
     */
    public boolean stop() {
        return service.stopRecording(this);
    }

    /** True while this handle's session is the recorder's active session. */
    public boolean isActive() {
        return service.isActive(this);
    }
}
