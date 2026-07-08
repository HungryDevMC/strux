package dev.gesp.structural.api;

/**
 * Reason why a block is collapsing.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                      COLLAPSE REASONS                              │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  FLOATING - No path to ground. Block has no support at all.        │
 *   │             This happens instantly - nothing holds it up!          │
 *   │                                                                     │
 *   │       [B] ← floating (A was removed)                               │
 *   │        ×                                                           │
 *   │       [A] ← gone                                                   │
 *   │        │                                                           │
 *   │      [GND]                                                         │
 *   │                                                                     │
 *   │                                                                     │
 *   │  OVERLOADED - Stress exceeds capacity. Block is supported but      │
 *   │               bearing too much weight. Shows cracks before break.  │
 *   │                                                                     │
 *   │       [D]                                                          │
 *   │        │                                                           │
 *   │       [C]                                                          │
 *   │        │                                                           │
 *   │       [B] ← stress > 100%, cracking...                             │
 *   │        │                                                           │
 *   │       [A]                                                          │
 *   │        │                                                           │
 *   │      [GND]                                                         │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public enum CollapseReason {

    /**
     * Block has no path to ground - it's floating in mid-air.
     * Collapses immediately with no warning.
     */
    FLOATING,

    /**
     * Block is overloaded (stress > 100% capacity).
     * Should show cracking effects before collapsing.
     */
    OVERLOADED,

    /**
     * Block is the trigger of a cascade - it was removed first.
     * For player breaks, the world-side removal is already handled by Bukkit.
     * For internal triggers (entity weight, fire, etc.), callers should
     * schedule this block for world-side collapse like any other.
     */
    TRIGGER
}
