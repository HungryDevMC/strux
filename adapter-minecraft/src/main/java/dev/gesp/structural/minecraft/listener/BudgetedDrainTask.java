package dev.gesp.structural.minecraft.listener;

/**
 * Shared base for the adapter's per-tick, wall-clock-budgeted FIFO drain tasks.
 *
 * <p>Several repeating tasks share the same shape: each tick they pull queued work
 * items and process them, but a single tick must never be frozen by a long backlog.
 * So each tick gets a wall-clock budget — once it is spent, the rest of the backlog
 * waits for the next tick. This base adds that drain loop on top of the budget owned
 * by {@link BudgetedTask}, so the {@code setTickBudgetMs}/{@code tickBudgetNanos}
 * pair AND the zero-budget-safety invariant each live in ONE place.
 *
 * <pre>
 *   deadline = now + tickBudgetNanos
 *   process at least one item              ← the zero-budget guarantee
 *   re-check now &gt;= deadline between items  ← the per-tick seatbelt
 * </pre>
 *
 * <p><b>Zero-budget safety.</b> {@link #drain} always processes at least one item
 * before it consults the deadline, then re-checks BETWEEN items. So even a zero (or
 * already-expired) budget drains exactly one item per pass — never zero (which would
 * leave the queue stuck forever) and never the whole backlog at once (which would
 * freeze the tick). This is the load-bearing invariant; it is pinned by a direct
 * unit test on a trivial subclass.
 *
 * @param <T> the queued work-item type a subclass drains.
 */
public abstract class BudgetedDrainTask<T> extends BudgetedTask {

    /** Take the next item to process, or {@code null} when the source is empty. */
    protected abstract T poll();

    /** Whether the item source currently has nothing left to drain. */
    protected abstract boolean isEmpty();

    /** Process one drained item — the subclass's per-item work. */
    protected abstract void process(T item);

    /**
     * Drain queued items until either the source is empty or the wall-clock
     * {@code deadline} (as a {@link System#nanoTime()} value) has passed — but always
     * process at least one item first, re-checking the deadline only BETWEEN items.
     *
     * @param deadline the {@code System.nanoTime()} value at which to stop starting
     *     new items; an already-passed deadline still drains exactly one item.
     * @return how many items this pass processed (zero only if the source was empty).
     */
    protected int drain(long deadline) {
        // Process at least one item even if we are already over budget, then re-check
        // between items so one tick can't be frozen by a long backlog.
        int processed = 0;
        while (!isEmpty()) {
            process(poll());
            processed++;
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
        return processed;
    }
}
