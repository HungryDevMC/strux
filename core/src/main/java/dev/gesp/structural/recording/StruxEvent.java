package dev.gesp.structural.recording;

/**
 * Sealed interface for all events that can be recorded during gameplay.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                         STRUX EVENTS                               │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Events are captured during gameplay for:                          │
 *   │    • Debugging: replay to reproduce bugs                           │
 *   │    • Playtesting: analyze structural behavior                      │
 *   │    • Verification: ensure deterministic physics                    │
 *   │                                                                     │
 *   │  Event types:                                                       │
 *   │    BLOCK_BREAK  → player/piston breaks a block                     │
 *   │    BLOCK_PLACE  → player places a block                            │
 *   │    BLAST        → explosion (TNT, creeper, etc.)                   │
 *   │    IMPACT       → projectile/entity collision                      │
 *   │    FIRE_DAMAGE  → fire weakens a block                             │
 *   │    CASCADE      → structural cascade event                         │
 *   │                                                                     │
 *   │  All events have:                                                  │
 *   │    timestampMs → wall-clock time for debugging                     │
 *   │    sequenceId  → monotonic counter for deterministic replay        │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>The sealed interface ensures all event types are known at compile time,
 * enabling exhaustive pattern matching and type-safe serialization.
 */
public sealed interface StruxEvent
        permits BlockBreakEvent, BlockPlaceEvent, BlastEvent, ImpactEvent, FireDamageEvent, CascadeEvent, MarkerEvent {

    /**
     * Wall-clock timestamp when the event occurred (milliseconds since epoch).
     * Used for debugging and timeline visualization; NOT used for replay ordering.
     */
    long timestampMs();

    /**
     * Monotonically increasing sequence number within a recording session.
     * This is the canonical ordering for deterministic replay.
     */
    long sequenceId();

    /**
     * Event type discriminator for serialization.
     */
    EventType type();

    /**
     * Enumeration of event types for JSON serialization.
     */
    enum EventType {
        BLOCK_BREAK,
        BLOCK_PLACE,
        BLAST,
        IMPACT,
        FIRE_DAMAGE,
        CASCADE,
        MARKER
    }
}
