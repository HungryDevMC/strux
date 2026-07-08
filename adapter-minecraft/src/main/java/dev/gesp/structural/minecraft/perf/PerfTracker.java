package dev.gesp.structural.minecraft.perf;

/**
 * Rolling wall-clock record of how long strux's main-thread solves actually
 * take, so admins can see the cost on <em>their</em> server and hardware.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                        PERF TRACKER                                │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  The core's StruxMetrics counts WORK (deterministic, machine-      │
 *   │  independent) for the regression gate. This is the complement:     │
 *   │  real nanoseconds, sampled from live gameplay, for the human-      │
 *   │  facing `/strux perf` readout. A small ring buffer keeps the last  │
 *   │  N solves so we can report a current average and the worst spike.  │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>Thread-safe: solves run on the main thread, but the readout (and any
 * future async reader) may touch it from elsewhere.
 */
public final class PerfTracker {

    private static final int WINDOW = 200;

    private final long[] nanos = new long[WINDOW];
    private final int[] blocks = new int[WINDOW];
    private int index = 0;
    private int count = 0;

    private long worstNanos = 0;
    private int worstBlocks = 0;
    private long totalSolves = 0;

    /** Record one solve: how long it took and how many blocks were in scope. */
    public synchronized void record(long elapsedNanos, int blockCount) {
        nanos[index] = elapsedNanos;
        blocks[index] = blockCount;
        index = (index + 1) % WINDOW;
        if (count < WINDOW) {
            count++;
        }
        totalSolves++;
        if (elapsedNanos > worstNanos) {
            worstNanos = elapsedNanos;
            worstBlocks = blockCount;
        }
    }

    /** Average solve time over the sample window, in milliseconds. */
    public synchronized double averageMillis() {
        if (count == 0) {
            return 0.0;
        }
        long sum = 0;
        for (int i = 0; i < count; i++) {
            sum += nanos[i];
        }
        return sum / (double) count / 1_000_000.0;
    }

    /**
     * Mean work count over the sample window — "avg blocks/nodes/items per pass".
     * Reuses the same ring as {@link #averageMillis()} so a reading pairs cleanly
     * with it ("how long" alongside "how much"). Allocation-free.
     */
    public synchronized double averageWork() {
        if (count == 0) {
            return 0.0;
        }
        long sum = 0;
        for (int i = 0; i < count; i++) {
            sum += blocks[i];
        }
        return sum / (double) count;
    }

    /** Worst single solve ever recorded, in milliseconds. */
    public synchronized double worstMillis() {
        return worstNanos / 1_000_000.0;
    }

    /** Block count of the worst recorded solve. */
    public synchronized int worstBlocks() {
        return worstBlocks;
    }

    /** Number of solves currently held in the sample window. */
    public synchronized int sampleCount() {
        return count;
    }

    /** Total solves recorded since startup. */
    public synchronized long totalSolves() {
        return totalSolves;
    }
}
