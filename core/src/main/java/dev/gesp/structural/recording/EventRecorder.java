package dev.gesp.structural.recording;

/**
 * Interface for recording structural physics events.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                       EVENT RECORDER                               │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Design goals:                                                      │
 *   │                                                                     │
 *   │  1. ZERO-COST WHEN DISABLED                                        │
 *   │     The NOOP singleton is a true no-op; JIT eliminates dead code   │
 *   │     paths entirely when recording is disabled.                      │
 *   │                                                                     │
 *   │  2. MINIMAL OVERHEAD WHEN ENABLED                                  │
 *   │     Event creation: ~200ns (simple record allocation)              │
 *   │     Buffer add: ~50ns (lock-free ConcurrentLinkedQueue)            │
 *   │     All disk I/O is async - never blocks main thread               │
 *   │                                                                     │
 *   │  3. BACKPRESSURE PROTECTION                                        │
 *   │     Per-tick throttling prevents lag spikes during mass events     │
 *   │     Old events are dropped if buffer overflows                     │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public interface EventRecorder {

    /**
     * No-op recorder that does nothing. Use when recording is disabled.
     *
     * <p>This singleton is designed for zero overhead: all methods are empty,
     * allowing the JIT to inline and eliminate dead code paths.
     */
    EventRecorder NOOP = new EventRecorder() {
        @Override
        public void record(StruxEvent event) {
            // Intentionally empty - zero cost when disabled
        }

        @Override
        public boolean isRecording() {
            return false;
        }

        @Override
        public void flush() {
            // Intentionally empty
        }

        @Override
        public void close() {
            // Intentionally empty
        }
    };

    /**
     * Record an event.
     *
     * <p>Implementations must be thread-safe and non-blocking on the calling thread.
     * Events may be buffered and written asynchronously.
     *
     * @param event the event to record
     */
    void record(StruxEvent event);

    /**
     * Check if recording is currently active.
     *
     * @return true if events are being recorded
     */
    boolean isRecording();

    /**
     * Flush any buffered events to storage.
     *
     * <p>This may block until buffered events are written. Use sparingly,
     * typically only on session stop or server shutdown.
     */
    void flush();

    /**
     * Stop recording and release resources.
     *
     * <p>This flushes remaining events and closes the recording session.
     * After calling close(), the recorder should not be reused.
     */
    void close();
}
