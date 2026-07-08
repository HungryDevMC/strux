package dev.gesp.structural.minecraft.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.StructuralIntegrityPlugin;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.perf.TaskTimings;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Pins the async settle coordinator's contract with an <em>inline</em> executor seam
 * (worker = {@code null}) — MockBukkit has no scheduler threads, so the solve runs on
 * the calling thread at {@code submit()} time and is deterministic. Covers the
 * observable behaviours from the brief: async-vs-sync equivalence, topology-conflict
 * re-solve, out-of-scope edits not conflicting, the 3-strike inline fallback, and the
 * scope-aware fire-scorch semantics — an in-scope damage-only worsening applies a
 * conservative subset without striking, while a repair (damage down) still conflicts.
 */
@DisplayName("AsyncSettleCoordinator: off-thread settle, apply main-thread")
class AsyncSettleCoordinatorTest {

    private static final MaterialSpec LIGHT = new MaterialSpec(1.0, 100.0);

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private StructureManager manager;
    private WorldMock world;
    private Logger logger;
    private final List<LogRecord> logs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        manager = plugin.getStructureManager();
        world = server.addSimpleWorld("async_settle_world");
        logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logs.clear();
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private AsyncSettleCoordinator inlineCoordinator() {
        // worker == null → the solve runs inline at submit() time (the test seam).
        return new AsyncSettleCoordinator(
                manager, new CascadeEngine(manager.getConfig()), null, logger, new TaskTimings());
    }

    /** An 8-tall stack on ground at column {@code x}; returns the graph. */
    private StructureGraph stack(int x) {
        StructureGraph g = manager.getOrCreateGraph(world);
        g.addGroundBlock(new NodePos(x, 0, 0));
        for (int y = 1; y <= 8; y++) {
            g.addBlock(new NodePos(x, y, 0), LIGHT, false);
        }
        return g;
    }

    private static List<NodePos> positions(List<CollapsedNode> collapsed) {
        List<NodePos> out = new ArrayList<>(collapsed.size());
        for (CollapsedNode n : collapsed) {
            out.add(n.pos());
        }
        return out;
    }

    @Test
    @DisplayName("AC1: the async collapse equals a synchronous settle — same blocks, same order")
    void asyncEqualsSync() {
        StructureGraph graph = stack(0);
        Set<NodePos> scope = graph.getDependentSubgraph(new NodePos(0, 1, 0));
        graph.removeBlock(new NodePos(0, 1, 0)); // strand the stack above

        // Reference: a synchronous settle on a full copy of the live graph.
        StructureGraph reference = graph.copy();
        CascadeEngine engine = new CascadeEngine(manager.getConfig());
        CascadeEngine.SettleOutcome expected = engine.settleResult(reference, scope, SolverCallback.NONE);
        assertFalse(expected.collapsed().isEmpty(), "the reference settle must actually collapse the stranded stack");

        AsyncSettleCoordinator coord = inlineCoordinator();
        coord.submit(world, scope);
        CascadeEngine.SettleOutcome got = coord.drainCompleted(world);

        assertNotNull(got, "an unconflicted solve drains to a result on the first drain");
        assertEquals(
                positions(expected.collapsed()),
                positions(got.collapsed()),
                "async must collapse the identical blocks in the identical canonical order");
        for (CollapsedNode n : got.collapsed()) {
            assertFalse(graph.hasBlock(n.pos()), "the match apply must remove the collapsed node from the LIVE graph");
        }
        assertEquals(1, coord.asyncSettleSolves(), "one solve applied");
        assertEquals(0, coord.asyncSettleConflicts(), "no conflicts on a clean solve");
        assertFalse(coord.inFlight(world), "the job is retired once drained");
    }

    @Test
    @DisplayName("AC2: a block removed inside the scope mid-solve discards the answer and re-solves")
    void inScopeEditForcesResolve() {
        StructureGraph graph = stack(0);
        Set<NodePos> scope = graph.getDependentSubgraph(new NodePos(0, 1, 0));
        graph.removeBlock(new NodePos(0, 1, 0));

        AsyncSettleCoordinator coord = inlineCoordinator();
        coord.submit(world, scope); // snapshot taken here

        // A player breaks a block INSIDE the in-flight scope before we drain.
        graph.removeBlock(new NodePos(0, 4, 0));

        // Reference: a synchronous settle against the POST-EDIT graph.
        StructureGraph reference = graph.copy();
        CascadeEngine.SettleOutcome expected =
                new CascadeEngine(manager.getConfig()).settleResult(reference, scope, SolverCallback.NONE);

        CascadeEngine.SettleOutcome firstDrain = coord.drainCompleted(world);
        assertNull(firstDrain, "the stale answer is discarded and a re-solve submitted — nothing to apply yet");
        assertEquals(1, coord.asyncSettleConflicts(), "exactly one conflict counted");

        CascadeEngine.SettleOutcome got = coord.drainCompleted(world);
        assertNotNull(got, "the re-solve drains on the next drain");
        assertEquals(
                positions(expected.collapsed()),
                positions(got.collapsed()),
                "the final collapse matches a synchronous settle against the post-edit graph");
        assertEquals(1, coord.asyncSettleSolves(), "one solve ultimately applied");
    }

    @Test
    @DisplayName("AC3: a change OUTSIDE the scope applies without a re-solve (no conflict)")
    void outOfScopeEditDoesNotConflict() {
        StructureGraph graph = stack(0);
        // A second, independent structure far away (different chunk) — outside the stack's scope.
        graph.addGroundBlock(new NodePos(64, 0, 0));
        graph.addBlock(new NodePos(64, 1, 0), LIGHT, false);

        Set<NodePos> scope = graph.getDependentSubgraph(new NodePos(0, 1, 0));
        graph.removeBlock(new NodePos(0, 1, 0));

        AsyncSettleCoordinator coord = inlineCoordinator();
        coord.submit(world, scope);

        // Mutate the far structure — bumps modCount, but touches nothing in the scope.
        graph.applyDamage(new NodePos(64, 1, 0), 0.5);

        CascadeEngine.SettleOutcome got = coord.drainCompleted(world);
        assertNotNull(got, "an out-of-scope edit must NOT block the apply");
        assertFalse(got.collapsed().isEmpty(), "the stranded stack still collapses");
        assertEquals(0, coord.asyncSettleConflicts(), "an out-of-scope edit is not a conflict");
        assertEquals(1, coord.asyncSettleSolves(), "the solve applied");
    }

    /** An executor that never runs tasks itself — the test runs the captured task by hand. */
    private static final class ManualExecutor extends java.util.concurrent.AbstractExecutorService {
        private Runnable pending;

        @Override
        public void execute(Runnable command) {
            this.pending = command;
        }

        /** Run the last-submitted task, completing its future. */
        void runPending() {
            Runnable r = pending;
            pending = null;
            r.run();
        }

        @Override
        public void shutdown() {}

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
            return true;
        }
    }

    @Test
    @DisplayName("worker path: drain parks while the future is unfinished, then applies on completion")
    void workerPathParksThenApplies() {
        StructureGraph graph = stack(0);
        Set<NodePos> scope = graph.getDependentSubgraph(new NodePos(0, 1, 0));
        graph.removeBlock(new NodePos(0, 1, 0));

        StructureGraph reference = graph.copy();
        CascadeEngine.SettleOutcome expected =
                new CascadeEngine(manager.getConfig()).settleResult(reference, scope, SolverCallback.NONE);

        ManualExecutor exec = new ManualExecutor();
        AsyncSettleCoordinator coord = new AsyncSettleCoordinator(
                manager, new CascadeEngine(manager.getConfig()), exec, logger, new TaskTimings());

        coord.submit(world, scope);
        assertNull(coord.drainCompleted(world), "the future is not done yet — drain must park, not apply");
        coord.submit(world, scope); // already in flight → no-op guard
        assertTrue(coord.inFlight(world), "still in flight while the worker runs");

        exec.runPending(); // worker completes
        CascadeEngine.SettleOutcome got = coord.drainCompleted(world);
        assertNotNull(got, "once the future is done the drain applies");
        assertEquals(
                positions(expected.collapsed()), positions(got.collapsed()), "worker path matches the sync collapse");
        assertEquals(1, coord.asyncSettleSolves());
    }

    @Test
    @DisplayName("worker exception logs SEVERE and falls back to an inline synchronous settle")
    void workerExceptionFallsBackInline() {
        StructureGraph graph = stack(0);
        Set<NodePos> scope = graph.getDependentSubgraph(new NodePos(0, 1, 0));
        graph.removeBlock(new NodePos(0, 1, 0));

        CascadeEngine boom = new CascadeEngine(manager.getConfig()) {
            @Override
            public SettleOutcome settleResult(StructureGraph g, Set<NodePos> s, SolverCallback cb) {
                throw new IllegalStateException("kaboom");
            }
        };
        ManualExecutor exec = new ManualExecutor();
        AsyncSettleCoordinator coord = new AsyncSettleCoordinator(manager, boom, exec, logger, new TaskTimings());

        coord.submit(world, scope);
        exec.runPending(); // the task throws → the future completes exceptionally

        CascadeEngine.SettleOutcome got = coord.drainCompleted(world);
        assertNotNull(got, "a worker failure must still settle inline — the cascade is never dropped");
        assertFalse(got.collapsed().isEmpty(), "the inline fallback collapsed the stranded stack");
        assertFalse(coord.inFlight(world), "the failed job is retired");
        assertTrue(
                logs.stream().anyMatch(r -> r.getLevel() == Level.SEVERE),
                "a worker exception is surfaced at SEVERE, never swallowed");
    }

    @Test
    @DisplayName("deferWhile gates submits, and a conflict during deferral parks without a strike")
    void deferGateParksInsteadOfStriking() {
        StructureGraph graph = stack(0);
        Set<NodePos> scope = graph.getDependentSubgraph(new NodePos(0, 1, 0));
        graph.removeBlock(new NodePos(0, 1, 0));

        AsyncSettleCoordinator coord = inlineCoordinator();
        boolean[] blastActive = {true};
        coord.setDeferWhile(() -> blastActive[0]);

        // While the gate is up nothing is submitted (a mid-blast solve is stale on arrival).
        coord.submit(world, scope);
        assertFalse(coord.inFlight(world), "no solve may be submitted while deferred");

        // Gate down → the solve goes in. Then the gate comes back up (a new blast) and the
        // scope is mutated by that blast: the conflicted job parks with NO strike and NO
        // inline fallback (that inline settle is the main-thread spike this avoids).
        blastActive[0] = false;
        coord.submit(world, scope);
        assertTrue(coord.inFlight(world));
        blastActive[0] = true;
        // The blast carves the crater — a TOPOLOGY change in scope, the genuine conflict.
        graph.removeBlock(new NodePos(0, 3, 0));
        assertNull(coord.drainCompleted(world), "a conflicted job parks while deferred");
        assertFalse(coord.inFlight(world), "the parked job is retired so it can resubmit after the blast");
        assertEquals(1, coord.asyncSettleConflicts(), "the conflict is still counted");
        assertTrue(
                logs.stream().noneMatch(r -> r.getLevel() == Level.WARNING),
                "no 3-strike WARNING and no inline fallback while deferred");

        // Blast over → a clean submit + drain applies normally.
        blastActive[0] = false;
        coord.submit(world, scope);
        CascadeEngine.SettleOutcome got = coord.drainCompleted(world);
        assertNotNull(got, "after the blast the solve applies");
        assertFalse(got.collapsed().isEmpty(), "the stranded stack collapses");
    }

    @Test
    @DisplayName("empty seeds fall back to the whole graph; a drain with no job is a no-op")
    void emptySeedsAndNoJob() {
        StructureGraph graph = stack(0);
        graph.removeBlock(new NodePos(0, 1, 0));

        AsyncSettleCoordinator coord = inlineCoordinator();
        assertNull(coord.drainCompleted(world), "no job submitted → drain returns null");
        coord.submit(world, Set.of()); // empty seeds → whole-graph fallback
        CascadeEngine.SettleOutcome got = coord.drainCompleted(world);
        assertNotNull(got, "the whole-graph settle drains");
        assertFalse(got.collapsed().isEmpty(), "the stranded stack collapses under the whole-graph fallback");
    }

    @Test
    @DisplayName("AC4: three consecutive TOPOLOGY conflicts fall back to an inline synchronous settle + WARNING")
    void threeStrikesFallsBackInline() {
        StructureGraph graph = stack(0);
        // A tall stack so we have several removable in-scope blocks to force topology
        // conflicts on successive drains without emptying the stack.
        for (int y = 9; y <= 12; y++) {
            graph.addBlock(new NodePos(0, y, 0), LIGHT, false);
        }
        Set<NodePos> scope = graph.getDependentSubgraph(new NodePos(0, 1, 0));
        graph.removeBlock(new NodePos(0, 1, 0));

        AsyncSettleCoordinator coord = inlineCoordinator();
        coord.submit(world, scope);

        // Conflict #1 and #2: REMOVE a scope block (topology change) before each drain →
        // the solved answer is stale and could over-collapse → re-solve, no apply.
        graph.removeBlock(new NodePos(0, 12, 0));
        assertNull(coord.drainCompleted(world), "conflict 1 → re-solve, nothing applied");
        graph.removeBlock(new NodePos(0, 11, 0));
        assertNull(coord.drainCompleted(world), "conflict 2 → re-solve, nothing applied");

        // Conflict #3: the fourth attempt would be submitted, so instead we settle inline.
        graph.removeBlock(new NodePos(0, 10, 0));
        CascadeEngine.SettleOutcome got = coord.drainCompleted(world);

        assertEquals(3, coord.asyncSettleConflicts(), "three conflicts counted");
        assertNotNull(got, "the inline fallback still produces a collapse — the cascade is never dropped");
        assertFalse(got.collapsed().isEmpty(), "the fallback settle collapsed the stranded stack");
        assertFalse(coord.inFlight(world), "the job is retired after the inline fallback");
        assertTrue(
                logs.stream()
                        .anyMatch(r ->
                                r.getLevel() == Level.WARNING && r.getMessage().contains(world.getName())),
                "the fallback logs one WARNING naming the world");
    }

    @Test
    @DisplayName("fire scorch: in-scope damage that only WORSENS applies without a strike (conservative subset)")
    void inScopeDamageWorseningAppliesWithoutStrike() {
        StructureGraph graph = stack(0);
        Set<NodePos> scope = graph.getDependentSubgraph(new NodePos(0, 1, 0));
        graph.removeBlock(new NodePos(0, 1, 0)); // strand the stack above

        AsyncSettleCoordinator coord = inlineCoordinator();
        coord.submit(world, scope); // snapshot taken here

        // Fire scorch chars an IN-SCOPE block mid-solve: damage only increases, no topology
        // change. This is the thrash the fix removes — it must NOT strike.
        graph.applyDamage(new NodePos(0, 3, 0), 0.2);

        CascadeEngine.SettleOutcome got = coord.drainCompleted(world);
        assertNotNull(got, "a damage-only worsening must still apply on the first drain — no re-solve");
        assertFalse(got.collapsed().isEmpty(), "the stranded stack still collapses");
        assertEquals(0, coord.asyncSettleConflicts(), "worsening damage in scope is NOT a conflict");
        assertEquals(1, coord.asyncSettleSolves(), "the conservative collapse is applied");
        assertEquals(1, coord.asyncSettleDamageSkips(), "the damage-only skip is counted honestly");
        for (CollapsedNode n : got.collapsed()) {
            assertFalse(graph.hasBlock(n.pos()), "the applied collapse is removed from the LIVE graph");
        }
    }

    @Test
    @DisplayName("fire scorch: sustained in-scope damage never accumulates to the inline fallback")
    void sustainedDamageNeverFallsBackInline() {
        StructureGraph graph = stack(0);
        Set<NodePos> scope = graph.getDependentSubgraph(new NodePos(0, 1, 0));
        graph.removeBlock(new NodePos(0, 1, 0));

        AsyncSettleCoordinator coord = inlineCoordinator();
        coord.submit(world, scope);

        // Three scorch scans land across the solve boundary — the old code struck three
        // times and ran a keep-sized inline settle on the main thread. Now each is a
        // conservative apply; the FIRST worsening already drains a result.
        graph.applyDamage(new NodePos(0, 3, 0), 0.1);
        graph.applyDamage(new NodePos(0, 4, 0), 0.1);
        graph.applyDamage(new NodePos(0, 5, 0), 0.1);

        CascadeEngine.SettleOutcome got = coord.drainCompleted(world);
        assertNotNull(got, "sustained fire must not park the settle forever");
        assertEquals(0, coord.asyncSettleConflicts(), "no strikes accumulate from fire scorch");
        assertTrue(
                logs.stream().noneMatch(r -> r.getLevel() == Level.WARNING),
                "no 3-strike WARNING and no inline fallback under sustained fire");
        assertEquals(1, coord.asyncSettleDamageSkips(), "one drain, one damage-only skip");
    }

    @Test
    @DisplayName("repair: in-scope damage DECREASING is a real conflict — a stale collapse could over-collapse")
    void inScopeRepairStillConflicts() {
        StructureGraph graph = stack(0);
        graph.applyDamage(new NodePos(0, 3, 0), 0.5); // pre-damage a block so it can be repaired
        Set<NodePos> scope = graph.getDependentSubgraph(new NodePos(0, 1, 0));
        graph.removeBlock(new NodePos(0, 1, 0));

        AsyncSettleCoordinator coord = inlineCoordinator();
        coord.submit(world, scope); // snapshot captures damage 0.5 on (0,3,0)

        // A repair stone heals the block mid-solve — damage goes DOWN. The solved answer
        // was computed against a weaker block and could over-collapse, so this must strike.
        graph.applyDamage(new NodePos(0, 3, 0), -0.4);

        assertNull(coord.drainCompleted(world), "a repair mid-solve discards the answer and re-solves");
        assertEquals(1, coord.asyncSettleConflicts(), "repair is a genuine conflict");
        assertEquals(0, coord.asyncSettleDamageSkips(), "a repair is never a conservative damage-only skip");
    }
}
