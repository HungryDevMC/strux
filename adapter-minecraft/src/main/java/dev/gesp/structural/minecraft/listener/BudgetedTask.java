package dev.gesp.structural.minecraft.listener;

import org.bukkit.scheduler.BukkitRunnable;

/**
 * Shared base for the adapter's repeating tasks that bound one pass with a
 * wall-clock budget — the seatbelt that stops a single tick being frozen by a long
 * backlog of work.
 *
 * <p>This base owns ONLY the budget itself: the {@code tickBudgetNanos} field plus
 * the {@link #setTickBudgetMs} setter (ms→ns, clamped non-negative) that every such
 * task otherwise re-implements identically. It does NOT prescribe a loop shape — a
 * task computes its own {@code deadline = System.nanoTime() + tickBudgetNanos()} and
 * decides how to spend the budget.
 *
 * <p>A FIFO queue-drain task should instead extend {@link BudgetedDrainTask}, which
 * adds the "process at least one item, re-check the deadline between items" loop on
 * top of this budget. Tasks with a different shape (a chunk-cursor sweep, a
 * world-by-world scan, a resumable session) extend this base directly and keep their
 * own loop — only the budget plumbing is shared.
 */
public abstract class BudgetedTask extends BukkitRunnable {

    /** Per-pass wall-clock budget in nanoseconds (the seatbelt). Main-thread only. */
    private long tickBudgetNanos;

    /**
     * Set the per-pass wall-clock budget (milliseconds). Clamped non-negative, so a
     * negative config value never produces a negative (past) deadline. Visible for
     * testing the budget behaviour.
     */
    public void setTickBudgetMs(double tickBudgetMs) {
        this.tickBudgetNanos = (long) (Math.max(0.0, tickBudgetMs) * 1_000_000.0);
    }

    /** The current per-pass budget in nanoseconds — for subclasses computing a deadline, and for tests. */
    protected long tickBudgetNanos() {
        return tickBudgetNanos;
    }
}
