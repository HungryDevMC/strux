package dev.gesp.structural.minecraft.physics;

import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.metrics.StruxMetrics;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import dev.gesp.structural.solver.CascadeResult;
import dev.gesp.structural.solver.StressSolver;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The <b>Pro</b> binding of {@link PhysicsService}: the core engines run in-process, on the
 * caller's (owner) thread — exactly the path {@code StructureManager} used before the seam
 * existed. This is a thin delegator; it adds no behaviour of its own.
 *
 * <p>It owns one {@link CascadeEngine} and one {@link StressSolver}, both built from the
 * given {@link PhysicsConfig} and sharing the given {@link StruxMetrics} work-counter —
 * constructed verbatim as the manager used to, so metrics attribution and collapse results
 * are byte-identical to the direct-engine path.
 *
 * <p>Honours the {@link PhysicsService} threading contract trivially: it neither spawns
 * threads nor retains the graph, so every call runs and completes on the owner thread.
 */
public final class LocalPhysicsService implements PhysicsService {

    private final CascadeEngine cascadeEngine;
    private final StressSolver stressSolver;

    /**
     * @param config  the physics config the engines solve against
     * @param metrics the shared deterministic work-counter; wired onto both engines so
     *                {@code /strux perf} reports cumulative engine work (the {@link
     *                CascadeEngine} propagates it to its own internal solver)
     */
    public LocalPhysicsService(PhysicsConfig config, StruxMetrics metrics) {
        this.cascadeEngine = new CascadeEngine(config);
        this.stressSolver = new StressSolver(config);
        this.cascadeEngine.setMetrics(metrics);
        this.stressSolver.setMetrics(metrics);
    }

    @Override
    public CascadeResult onBreak(
            StructureGraph graph, NodePos triggerPos, SolverCallback callback, BooleanSupplier pause) {
        return cascadeEngine.cascade(graph, triggerPos, callback, pause);
    }

    @Override
    public CascadeEngine.SettleOutcome settle(
            StructureGraph graph, Set<NodePos> scope, SolverCallback callback, BooleanSupplier pause) {
        return cascadeEngine.settleResult(graph, scope, callback, pause);
    }

    @Override
    public NodePos solveProgressively(StructureGraph graph, Set<NodePos> region) {
        return stressSolver.solveProgressively(graph, region);
    }

    @Override
    public void solveScoped(StructureGraph graph, Set<NodePos> region) {
        stressSolver.solve(graph, region);
    }
}
