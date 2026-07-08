package dev.gesp.structural.minecraft.physics;

import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import dev.gesp.structural.solver.CascadeResult;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The one seam that keeps <b>Pro</b> (strux embedded in the game JVM) and <b>Enterprise</b>
 * (collapse compute moved to a sharded worker tier) a single codebase instead of a fork.
 *
 * <p>This is the adapter's {@code StructureManager} → engine boundary described in
 * <a href="../../../../../../../../../SCALING.md">SCALING.md §2</a>. The manager used to
 * {@code new} the core engines ({@link CascadeEngine}, {@link
 * dev.gesp.structural.solver.StressSolver}) and call them inline; instead it calls this
 * interface. The method set is exactly the collapse-path interactions the manager has with
 * those engines — nothing invented.
 *
 * <ul>
 *   <li><b>Pro</b> binds {@link LocalPhysicsService} — the core engines in-process, exactly
 *       today's path.
 *   <li><b>Enterprise</b> will later bind a {@code RemotePhysicsService} — an RPC to the
 *       worker tier. Same adapter, same core, same contract; only the binding differs.
 * </ul>
 *
 * <p><b>This interface is adapter-side, never in core.</b> Core owns no long-lived state
 * and stays byte-for-byte the same across both editions.
 *
 * <h2>Threading &amp; graph-ownership contract</h2>
 *
 * Every method here inherits the ownership rule in
 * <a href="../../../../../../../../../DESIGN.md">DESIGN.md § Threading &amp; Graph
 * Ownership</a>. An implementation MUST honour all of it — a remote implementation cannot
 * relax it:
 *
 * <ol>
 *   <li><b>Single owner thread.</b> Every method is invoked on the graph's one owning
 *       thread (the host's main/world thread). The engines and the {@code denomCache}-style
 *       scratch they keep are not thread-safe.
 *   <li><b>In-place, synchronous mutation.</b> The passed {@link StructureGraph} is read
 *       and mutated in place; when the method returns, the graph and the returned result
 *       are fully consistent. Callers apply the result to the world immediately after.
 *   <li><b>No off-thread graph access.</b> An implementation MUST NOT retain the graph
 *       reference nor touch it from another thread. A {@code RemotePhysicsService} must
 *       therefore <em>gather</em> the affected subgraph, solve it remotely, and
 *       <em>scatter</em> the removals/state back into this graph <b>on the owner thread</b>,
 *       blocking until done — it may not hand the live graph across the wire.
 *   <li><b>Deterministic.</b> Same graph + same {@link
 *       dev.gesp.structural.config.PhysicsConfig} → byte-identical result on any machine
 *       ({@link NodePos#CANONICAL_ORDER} makes simultaneous collapses order-stable). This
 *       is what makes a remote worker interchangeable and its result verifiable against a
 *       local solve.
 * </ol>
 *
 * <p>The {@code pause} {@link BooleanSupplier} on the settle methods is the caller's
 * main-thread wall-clock settle budget: it trips when the tick's time is spent so the core
 * pauses cooperatively and reports {@code truncated()}. A remote implementation, running
 * off the game thread, needs its own budgeting and would surface truncation the same way.
 */
public interface PhysicsService {

    /**
     * Run a block-break cascade from {@code triggerPos} over {@code graph}, pausing when
     * {@code pause} trips. Mirrors {@link CascadeEngine#cascade(StructureGraph, NodePos,
     * SolverCallback, BooleanSupplier)}.
     *
     * @return what collapsed (and whether the per-tick budget truncated the settle)
     */
    CascadeResult onBreak(StructureGraph graph, NodePos triggerPos, SolverCallback callback, BooleanSupplier pause);

    /**
     * Settle {@code graph} over the disturbed {@code scope} — the resume path for a
     * previously truncated cascade. Mirrors {@link CascadeEngine#settleResult(StructureGraph,
     * Set, SolverCallback, BooleanSupplier)}.
     *
     * @return the collapsed nodes, whether this pass truncated, and the remaining scope
     */
    CascadeEngine.SettleOutcome settle(
            StructureGraph graph, Set<NodePos> scope, SolverCallback callback, BooleanSupplier pause);

    /**
     * Find the next overloaded block within {@code region} using the progressive solver
     * (farthest-from-ground first), or {@code null} if the region is stable. The placement
     * overload path calls this repeatedly. Mirrors {@link
     * dev.gesp.structural.solver.StressSolver#solveProgressively(StructureGraph, Set)}.
     */
    NodePos solveProgressively(StructureGraph graph, Set<NodePos> region);

    /**
     * Re-solve stress over the bounded {@code region} (after a reinforce, repair, or a
     * targeted removal) so the structure stabilizes immediately. Mirrors {@link
     * dev.gesp.structural.solver.StressSolver#solve(StructureGraph, Set)}.
     */
    void solveScoped(StructureGraph graph, Set<NodePos> region);
}
