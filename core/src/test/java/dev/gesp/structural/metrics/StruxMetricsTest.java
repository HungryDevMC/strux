package dev.gesp.structural.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StruxMetrics#add} folds per-worker counters back together after a parallel
 * settle, so it must sum every field and survive a null argument.
 */
@DisplayName("StruxMetrics.add: sum worker counters")
class StruxMetricsTest {

    @Test
    @DisplayName("add() sums all three counters into the receiver")
    void addSumsEveryField() {
        StruxMetrics target = new StruxMetrics();
        target.solveInvocations = 2;
        target.nodeVisits = 30;
        target.blocksRemoved = 4;

        StruxMetrics other = new StruxMetrics();
        other.solveInvocations = 5;
        other.nodeVisits = 100;
        other.blocksRemoved = 7;

        target.add(other);

        assertEquals(7, target.solveInvocations, "solveInvocations must accumulate");
        assertEquals(130, target.nodeVisits, "nodeVisits must accumulate");
        assertEquals(11, target.blocksRemoved, "blocksRemoved must accumulate");

        // The source is left untouched (it is only read).
        assertEquals(5, other.solveInvocations);
        assertEquals(100, other.nodeVisits);
        assertEquals(7, other.blocksRemoved);
    }

    @Test
    @DisplayName("add(null) is a no-op")
    void addNullIsNoOp() {
        StruxMetrics target = new StruxMetrics();
        target.solveInvocations = 1;
        target.nodeVisits = 9;
        target.blocksRemoved = 3;

        target.add(null);

        assertEquals(1, target.solveInvocations);
        assertEquals(9, target.nodeVisits);
        assertEquals(3, target.blocksRemoved);
    }
}
