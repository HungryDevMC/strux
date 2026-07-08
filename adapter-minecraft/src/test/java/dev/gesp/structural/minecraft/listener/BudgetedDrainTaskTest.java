package dev.gesp.structural.minecraft.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit test for the shared {@link BudgetedDrainTask} base: the per-tick wall-clock
 * budget and the "process at least one item, re-check the deadline between items"
 * drain loop that {@code ImpactProcessor} folds onto.
 *
 * <p>The load-bearing invariant is the zero-budget safety: even with no budget the
 * loop must drain EXACTLY one item per pass (never zero — that would deadlock the
 * queue forever; never more — that would freeze the tick).
 */
@DisplayName("BudgetedDrainTask: budget math + zero-budget-safe drain loop")
class BudgetedDrainTaskTest {

    /**
     * A trivial subclass over an in-memory queue. {@code drainOnce} exposes the
     * protected {@link BudgetedDrainTask#drain} primitive for direct assertions
     * without needing a Bukkit scheduler; {@code processed} records the order items
     * were handed to {@link #process}.
     */
    private static final class FakeDrainTask extends BudgetedDrainTask<Integer> {
        private final Deque<Integer> queue = new ArrayDeque<>();
        final List<Integer> processed = new ArrayList<>();

        @Override
        protected Integer poll() {
            return queue.pollFirst();
        }

        @Override
        protected boolean isEmpty() {
            return queue.isEmpty();
        }

        @Override
        protected void process(Integer item) {
            processed.add(item);
        }

        void enqueue(int item) {
            queue.addLast(item);
        }

        int queueSize() {
            return queue.size();
        }

        /** Run one drain pass against a deadline derived from the current budget. */
        int drainOnce() {
            return drain(System.nanoTime() + tickBudgetNanos());
        }

        @Override
        public void run() {
            drainOnce();
        }
    }

    @Test
    @DisplayName("setTickBudgetMs converts ms→ns and clamps negatives to zero")
    void budgetMathClampsAndConverts() {
        FakeDrainTask task = new FakeDrainTask();

        task.setTickBudgetMs(10.0);
        assertEquals(10_000_000L, task.tickBudgetNanos(), "10 ms must be 10,000,000 ns");

        task.setTickBudgetMs(0.0);
        assertEquals(0L, task.tickBudgetNanos(), "a zero budget stays zero nanos");

        task.setTickBudgetMs(-5.0);
        assertEquals(0L, task.tickBudgetNanos(), "a negative budget is clamped to zero, never negative");

        task.setTickBudgetMs(2.5);
        assertEquals(2_500_000L, task.tickBudgetNanos(), "fractional ms convert exactly (2.5 ms → 2,500,000 ns)");
    }

    @Test
    @DisplayName("Zero budget drains EXACTLY one item per pass (never zero → no deadlock)")
    void zeroBudgetDrainsExactlyOnePerPass() {
        FakeDrainTask task = new FakeDrainTask();
        task.setTickBudgetMs(0.0);
        task.enqueue(1);
        task.enqueue(2);
        task.enqueue(3);

        int first = task.drainOnce();
        assertEquals(1, first, "a zero budget must still process exactly one item, not zero");
        assertEquals(2, task.queueSize(), "exactly one item left the queue");
        assertEquals(List.of(1), task.processed, "the oldest item was processed first (FIFO)");

        int second = task.drainOnce();
        assertEquals(1, second, "the next pass drains exactly one more");
        assertEquals(1, task.queueSize(), "the queue shrinks one per pass under a zero budget");
        assertEquals(List.of(1, 2), task.processed, "items are processed in FIFO order across passes");
    }

    @Test
    @DisplayName("An empty queue drains zero items and never blocks")
    void emptyQueueDrainsNothing() {
        FakeDrainTask task = new FakeDrainTask();
        task.setTickBudgetMs(0.0);

        int drained = task.drainOnce();

        assertEquals(0, drained, "nothing in the queue → nothing drained (the loop does not block on empty)");
        assertTrue(task.processed.isEmpty(), "no item was processed");
    }

    @Test
    @DisplayName("A large budget drains the whole queue in one pass")
    void largeBudgetDrainsEverythingInOnePass() {
        FakeDrainTask task = new FakeDrainTask();
        task.setTickBudgetMs(60_000.0); // a full minute — far more than this loop needs
        for (int i = 0; i < 50; i++) {
            task.enqueue(i);
        }

        int drained = task.drainOnce();

        assertEquals(50, drained, "a generous budget drains every queued item in a single pass");
        assertEquals(0, task.queueSize(), "the queue is empty after one large-budget pass");
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            expected.add(i);
        }
        assertEquals(expected, task.processed, "all items processed once, in FIFO order");
    }

    @Test
    @DisplayName("The deadline is re-checked BETWEEN items: an expired deadline stops after one item")
    void deadlineRecheckedBetweenItems() {
        FakeDrainTask task = new FakeDrainTask();
        for (int i = 0; i < 5; i++) {
            task.enqueue(i);
        }

        // A deadline already in the past: the loop must still process the first item
        // (the >=1 guarantee) but then see the expired deadline and stop — proving the
        // check happens AFTER an item, between iterations, not before the first one.
        long pastDeadline = System.nanoTime() - 1L;
        int drained = task.drain(pastDeadline);

        assertEquals(1, drained, "an already-expired deadline still drains exactly one item, then stops");
        assertEquals(4, task.queueSize(), "the remaining items wait for the next pass");
        assertEquals(List.of(0), task.processed, "only the first (oldest) item ran");
    }
}
