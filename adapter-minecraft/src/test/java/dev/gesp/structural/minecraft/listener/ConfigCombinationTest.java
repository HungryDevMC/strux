package dev.gesp.structural.minecraft.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * LAYER 3 — configuration COMBINATION matrix.
 *
 * <p>Single-key parse and single-flag behaviour are covered by
 * {@code ConfigMatrixParseTest} (layer 1) and the per-feature behaviour tests
 * (layer 2). This class pins the semantics of pairs of keys that <b>interact</b>
 * at a real coupling point in the code, chosen by reading where two settings
 * touch the same value.
 *
 * <p>Each combination documents WHY the pair is the risky one — a place where a
 * naive implementation would let one setting silently change what another
 * setting is supposed to guarantee.
 */
@DisplayName("Config combinations: interacting keys keep their documented semantics together")
class ConfigCombinationTest {

    private static final MaterialSpec LIGHT = new MaterialSpec(1.0, 100.0);

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private StructureManager manager;
    private WorldMock world;
    private Logger logger;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        manager = plugin.getStructureManager();
        world = server.addSimpleWorld("config_combo_world");
        logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private AsyncSettleCoordinator inlineCoordinator() {
        // worker == null → the async solve runs inline at submit() time: a deterministic
        // seam for the SAME code path the background worker drives in production.
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

    /**
     * Drive one stranded-stack settle through the async coordinator at a given
     * {@code cascade.settle-budget-ms} and return the collapsed positions.
     */
    private List<NodePos> asyncCollapseAtBudget(double budgetMs) {
        StructureGraph graph = stack(0);
        Set<NodePos> scope = graph.getDependentSubgraph(new NodePos(0, 1, 0));
        graph.removeBlock(new NodePos(0, 1, 0)); // strand the stack above

        manager.setSettleBudgetMs(budgetMs);

        AsyncSettleCoordinator coord = inlineCoordinator();
        coord.submit(world, scope);
        CascadeEngine.SettleOutcome got = coord.drainCompleted(world);
        assertNotNull(
                got, "an unconflicted async solve drains to a result on the first drain (budget " + budgetMs + ")");
        // The whole stranded stack must actually be gone from the LIVE graph.
        for (CollapsedNode n : got.collapsed()) {
            assertFalse(
                    graph.hasBlock(n.pos()),
                    "the match-apply must remove every collapsed node from the live graph, whatever the budget");
        }
        assertEquals(1, coord.asyncSettleSolves(), "exactly one solve applied");
        return positions(got.collapsed());
    }

    // ── Combination 1: cascade.async-settle × cascade.settle-budget-ms ──────────
    //
    // COUPLING: async-settle computes the collapse on a graph SNAPSHOT off the main
    // thread, then APPLIES it main-thread through the budgeted collapse path. So the
    // two keys meet at the apply step. settle-budget-ms bounds how much wall-clock
    // ONE apply pass may spend before pausing and resuming next tick (0 = unbounded).
    //
    // RISK: a naive apply could let a small/zero budget change the RESULT — e.g. drop
    // a partial crater, or re-solve against a half-applied graph and diverge. The
    // documented invariant is the opposite: the budget only spreads the SAME collapse
    // across ticks; the crater is byte-identical to a one-tick solve. This proves the
    // async solve result is independent of the budget for budget ∈ {0, tiny, default}.
    @Test
    @DisplayName("combo: the async collapse is bit-identical across settle-budget-ms {0, tiny, default}")
    void asyncCollapseIsBudgetIndependent() {
        List<NodePos> atZero = asyncCollapseAtBudget(0.0); // unbudgeted (legacy unbounded)
        List<NodePos> atTiny = asyncCollapseAtBudget(0.001); // pauses almost immediately in prod
        List<NodePos> atDefault = asyncCollapseAtBudget(8.0); // the shipped default

        assertFalse(atDefault.isEmpty(), "the stranded stack must actually collapse");
        assertEquals(
                atZero,
                atTiny,
                "a tiny budget carves the identical crater in the identical order as an unbudgeted one");
        assertEquals(atZero, atDefault, "the shipped default carves the identical crater as an unbudgeted settle");
    }

    // ── Combination 2: cascade.settle-budget-ms × the one-pass apply guarantee ───
    //
    // COUPLING: settle-budget-ms=0 is documented as "no budget" — the settle never
    // pauses. The async coordinator's drainCompleted applies the whole solved outcome
    // in a single drain. This pins that budget 0 + async on still respects the
    // one-pass apply: a single drain yields the full result and retires the job (no
    // dangling in-flight state that a resume tick would have to finish).
    @Test
    @DisplayName("combo: settle-budget-ms=0 with async on still applies the whole collapse in one pass")
    void zeroBudgetAppliesInOnePass() {
        StructureGraph graph = stack(0);
        Set<NodePos> scope = graph.getDependentSubgraph(new NodePos(0, 1, 0));
        graph.removeBlock(new NodePos(0, 1, 0));

        manager.setSettleBudgetMs(0.0);

        // Reference: a plain synchronous settle over a copy of the live graph.
        CascadeEngine.SettleOutcome expected =
                new CascadeEngine(manager.getConfig()).settleResult(graph.copy(), scope, SolverCallback.NONE);
        assertFalse(expected.collapsed().isEmpty(), "reference settle must collapse the stranded stack");

        AsyncSettleCoordinator coord = inlineCoordinator();
        coord.submit(world, scope);
        CascadeEngine.SettleOutcome got = coord.drainCompleted(world);

        assertNotNull(got, "one drain yields the whole result under an unbudgeted settle");
        assertEquals(
                positions(expected.collapsed()),
                positions(got.collapsed()),
                "unbudgeted async collapse equals a synchronous settle exactly");
        assertFalse(
                coord.inFlight(world), "the job is retired after the single drain — nothing left for a resume tick");
    }
}
