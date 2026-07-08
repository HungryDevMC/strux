package dev.gesp.structural.minecraft.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.metrics.StruxMetrics;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import dev.gesp.structural.solver.CascadeResult;
import dev.gesp.structural.solver.StressSolver;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LocalPhysicsService} is a pure delegator: for the SAME graph + config it must
 * produce a result byte-identical to calling the wrapped core engine directly. This pins
 * the refactor — the seam is transparent. Each case runs the operation twice over two
 * identical {@link StructureGraph#copy() copies} (one via the service, one via a directly
 * constructed engine wired the same way) and asserts equality.
 */
@DisplayName("LocalPhysicsService delegates result-identically to the core engines")
class LocalPhysicsServiceContractTest {

    private static final MaterialSpec WEAK = new MaterialSpec(5.0, 6.0);
    private static final BooleanSupplier NEVER_PAUSE = () -> false;

    /** A long horizontal cantilever off a single grounded post — prone to overload/cascade. */
    private static StructureGraph cantilever() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), WEAK, false);
        for (int x = 1; x <= 8; x++) {
            g.addBlock(new NodePos(x, 1, 0), WEAK, false);
        }
        return g;
    }

    private static PhysicsConfig config() {
        return new PhysicsConfig();
    }

    private static LocalPhysicsService service(PhysicsConfig config) {
        return new LocalPhysicsService(config, new StruxMetrics());
    }

    private static CascadeEngine directCascade(PhysicsConfig config) {
        return new CascadeEngine(config).setMetrics(new StruxMetrics());
    }

    private static StressSolver directSolver(PhysicsConfig config) {
        return new StressSolver(config).setMetrics(new StruxMetrics());
    }

    @Test
    @DisplayName("onBreak == CascadeEngine.cascade (same collapsed order + steps + truncation)")
    void onBreakMatchesDirectCascade() {
        PhysicsConfig config = config();
        StructureGraph viaService = cantilever();
        StructureGraph viaEngine = viaService.copy();
        NodePos trigger = new NodePos(0, 1, 0); // break the post's base -> orphans the whole arm

        CascadeResult service = service(config).onBreak(viaService, trigger, SolverCallback.NONE, NEVER_PAUSE);
        CascadeResult engine = directCascade(config).cascade(viaEngine, trigger, SolverCallback.NONE, NEVER_PAUSE);

        assertTrue(service.hadCascade(), "the test setup must actually collapse something");
        assertEquals(engine.collapsed(), service.collapsed(), "collapsed positions + order must match");
        assertEquals(engine.steps(), service.steps(), "step count must match");
        assertEquals(engine.truncated(), service.truncated(), "truncation flag must match");
    }

    @Test
    @DisplayName("settle == CascadeEngine.settleResult (same collapsed + truncation)")
    void settleMatchesDirectSettleResult() {
        PhysicsConfig config = config();
        StructureGraph viaService = cantilever();
        StructureGraph viaEngine = viaService.copy();
        Set<NodePos> scope = new HashSet<>(viaService.getAllPositions());
        Set<NodePos> scopeCopy = new HashSet<>(viaEngine.getAllPositions());

        CascadeEngine.SettleOutcome service =
                service(config).settle(viaService, scope, SolverCallback.NONE, NEVER_PAUSE);
        CascadeEngine.SettleOutcome engine =
                directCascade(config).settleResult(viaEngine, scopeCopy, SolverCallback.NONE, NEVER_PAUSE);

        assertEquals(collapsedPositions(engine), collapsedPositions(service), "settled collapse must match");
        assertEquals(engine.truncated(), service.truncated(), "truncation flag must match");
    }

    @Test
    @DisplayName("solveProgressively == StressSolver.solveProgressively (same overloaded pick)")
    void solveProgressivelyMatchesDirect() {
        PhysicsConfig config = config();
        StructureGraph viaService = cantilever();
        StructureGraph viaEngine = viaService.copy();
        Set<NodePos> scope = new HashSet<>(viaService.getAllPositions());
        Set<NodePos> scopeCopy = new HashSet<>(viaEngine.getAllPositions());

        NodePos service = service(config).solveProgressively(viaService, scope);
        NodePos engine = directSolver(config).solveProgressively(viaEngine, scopeCopy);

        assertNotNull(engine, "the weak cantilever must present an overloaded block");
        assertEquals(engine, service, "the progressively-picked overloaded block must match");
    }

    @Test
    @DisplayName("solveScoped == StressSolver.solve (same per-node stress afterwards)")
    void solveScopedMatchesDirect() {
        PhysicsConfig config = config();
        StructureGraph viaService = cantilever();
        StructureGraph viaEngine = viaService.copy();
        Set<NodePos> scope = new HashSet<>(viaService.getAllPositions());
        Set<NodePos> scopeCopy = new HashSet<>(viaEngine.getAllPositions());

        service(config).solveScoped(viaService, scope);
        directSolver(config).solve(viaEngine, scopeCopy);

        List<NodePos> positions = new ArrayList<>(viaService.getAllPositions());
        positions.sort(NodePos.CANONICAL_ORDER);
        for (NodePos pos : positions) {
            Node s = viaService.getNode(pos);
            Node e = viaEngine.getNode(pos);
            assertEquals(
                    e.stressPercent(), s.stressPercent(), 1e-9, "stress at " + pos + " must match the direct solve");
        }
    }

    @Test
    @DisplayName("metrics attribution: both wrapped engines report work into the shared StruxMetrics")
    void metricsAreWiredOntoBothEngines() {
        PhysicsConfig config = config();
        StruxMetrics metrics = new StruxMetrics();
        LocalPhysicsService service = new LocalPhysicsService(config, metrics);

        // Drive the CascadeEngine path: a real cascade must count solver work + removals.
        StructureGraph g1 = cantilever();
        service.onBreak(g1, new NodePos(0, 1, 0), SolverCallback.NONE, NEVER_PAUSE);
        long afterCascade = metrics.solveInvocations;
        assertTrue(afterCascade > 0, "onBreak must attribute solver work to the shared metrics");
        assertTrue(metrics.blocksRemoved > 0, "onBreak must attribute removed blocks to the shared metrics");

        // Drive the StressSolver path: a scoped solve must add further solver work.
        StructureGraph g2 = cantilever();
        service.solveScoped(g2, new HashSet<>(g2.getAllPositions()));
        assertTrue(
                metrics.solveInvocations > afterCascade,
                "solveScoped must attribute solver work to the SAME shared metrics");
    }

    private static List<NodePos> collapsedPositions(CascadeEngine.SettleOutcome outcome) {
        return outcome.collapsed().stream()
                .map(n -> n.pos())
                .sorted(NodePos.CANONICAL_ORDER)
                .toList();
    }
}
