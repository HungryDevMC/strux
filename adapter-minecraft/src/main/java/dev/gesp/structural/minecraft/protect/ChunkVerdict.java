package dev.gesp.structural.minecraft.protect;

/**
 * Whether physics is allowed for a whole chunk column, decided in ONE consultation.
 *
 * <pre>
 *   Per-block protection queries are the killer when something sweeps every tracked
 *   block (weather, a future world-wide settle): at 135k blocks a per-block WorldGuard
 *   region query froze the main thread for 20+ seconds.
 *
 *   Most chunks have a uniform answer: the world's master switch and per-world disables
 *   apply to the whole chunk, and a chunk with NO region touching it can only ever
 *   ALLOW. So a caller can ask once per chunk and skip the per-block path entirely —
 *   EXACTLY, no approximation — whenever the chunk's answer is uniform.
 * </pre>
 *
 * <ul>
 *   <li>{@link #ALL_ALLOWED} — every block in the chunk is allowed; do not ask per block.
 *   <li>{@link #ALL_DENIED}  — every block in the chunk is denied; do not ask per block.
 *   <li>{@link #PER_BLOCK}   — the answer is not uniform (e.g. a region boundary cuts the
 *       chunk); the caller must fall back to a per-block check for exactness.
 * </ul>
 */
public enum ChunkVerdict {
    ALL_ALLOWED,
    ALL_DENIED,
    PER_BLOCK;

    /**
     * Combine two independent gates' verdicts for the same chunk (logical AND of
     * "allowed"): both must allow for the result to allow.
     *
     * <pre>
     *   DENIED on either side  → ALL_DENIED   (one veto denies the whole chunk)
     *   ALLOWED on both sides  → ALL_ALLOWED  (uniform yes)
     *   otherwise              → PER_BLOCK    (at least one side is non-uniform)
     * </pre>
     */
    public ChunkVerdict and(ChunkVerdict other) {
        if (this == ALL_DENIED || other == ALL_DENIED) {
            return ALL_DENIED;
        }
        if (this == ALL_ALLOWED && other == ALL_ALLOWED) {
            return ALL_ALLOWED;
        }
        return PER_BLOCK;
    }
}
