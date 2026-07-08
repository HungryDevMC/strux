package dev.gesp.structural.minecraft.manager;

import dev.gesp.structural.api.CollapseReason;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.assess.CollapseAtlas;
import dev.gesp.structural.assess.StructureReport;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.metrics.StruxMetrics;
import dev.gesp.structural.minecraft.config.FoundationConfig;
import dev.gesp.structural.minecraft.listener.BlastProcessor;
import dev.gesp.structural.minecraft.listener.QueuedBlast;
import dev.gesp.structural.minecraft.material.MaterialRegistry;
import dev.gesp.structural.minecraft.material.TerrainGrounding;
import dev.gesp.structural.minecraft.perf.PerfTracker;
import dev.gesp.structural.minecraft.physics.LocalPhysicsService;
import dev.gesp.structural.minecraft.physics.PhysicsService;
import dev.gesp.structural.minecraft.protect.CollapseGuard;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.persistence.PersistenceAdapter;
import dev.gesp.structural.recording.EventRecorder;
import dev.gesp.structural.solver.CascadeEngine;
import dev.gesp.structural.solver.CascadeResult;
import dev.gesp.structural.solver.StressSolver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

/**
 * Manages structural graphs for each world.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                     STRUCTURE MANAGER                              │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Each Minecraft world has its own StructureGraph.                  │
 *   │                                                                     │
 *   │  WORLD "overworld"                WORLD "nether"                   │
 *   │  ┌─────────────────┐              ┌─────────────────┐              │
 *   │  │  StructureGraph │              │  StructureGraph │              │
 *   │  │                 │              │                 │              │
 *   │  │  [blocks...]    │              │  [blocks...]    │              │
 *   │  └─────────────────┘              └─────────────────┘              │
 *   │                                                                     │
 *   │  When a block breaks:                                              │
 *   │  1. Find the world's graph                                         │
 *   │  2. Convert Location → NodePos                                    │
 *   │  3. Run cascade simulation                                         │
 *   │  4. Remove collapsed blocks from Minecraft world                   │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>This class is a thin orchestrator: it routes block events through the core
 * engines and delegates its other responsibilities to focused collaborators —
 * {@link WorldGraphStore} (the per-world graph map + revision counter),
 * {@link StructurePersistenceCoordinator} (async load/save), and
 * {@link WorldAssessmentCache} (the revision-keyed grade/atlas caches).
 */
public class StructureManager {

    private final MaterialRegistry materials;
    private final PhysicsConfig config;

    // The one scaling seam (SCALING.md §2): all collapse-path engine work goes through this
    // interface. Pro binds LocalPhysicsService (in-process core); Enterprise will later bind
    // a RemotePhysicsService. Defaults to Local so existing wiring/tests are unchanged.
    private final PhysicsService physics;

    // Read-side solver, used ONLY for grading (WorldAssessmentCache). The collapse path uses
    // `physics`; kept separate so a remote physics binding doesn't remove local grading.
    // Shares the same StruxMetrics, so metrics attribution is identical to before the seam.
    private final StressSolver stressSolver;

    // Perf instrumentation: deterministic work-counters (core) + real wall-clock
    // samples (adapter), both surfaced by /strux perf.
    private final StruxMetrics metrics = new StruxMetrics();
    private final PerfTracker perf = new PerfTracker();

    // Focused collaborators (this class delegates inward to them).
    private final WorldGraphStore graphStore = new WorldGraphStore();
    private final StructurePersistenceCoordinator persistence;
    private final WorldAssessmentCache assessmentCache;

    // Region/world protection + collapse logging (nullable: allow-all when unset, e.g. in tests)
    private CollapseGuard collapseGuard;

    // Budgeted blast queue (nullable: when unset, blast() is a no-op — e.g. in unit tests)
    private BlastProcessor blastProcessor;
    // Foundation/terrain grounding policy. Defaults preserve legacy behaviour
    // (depth grounding off, no foundation block) so an unset manager is unchanged.
    private FoundationConfig foundationConfig = new FoundationConfig();

    // Event recording (nullable: NOOP when unset)
    private EventRecorder eventRecorder = EventRecorder.NOOP;

    // Residency guard for component memory eviction (nullable: no-op when unset). When
    // eviction is enabled the manager routes each addressed position through this so an
    // evicted structure is re-materialized before it is touched. Defensive: an evicted
    // component has all its chunks unloaded, so events rarely reach it — but a boundary
    // interaction must never see a half-present structure.
    private ResidencyGuard residencyGuard = (world, pos) -> {};

    /** Re-materializes an evicted structure covering a position before it is addressed. */
    @FunctionalInterface
    public interface ResidencyGuard {
        void ensureResident(World world, NodePos pos);
    }

    public StructureManager(MaterialRegistry materials) {
        this(materials, new PhysicsConfig());
    }

    public StructureManager(MaterialRegistry materials, PhysicsConfig config) {
        this(materials, config, null);
    }

    /**
     * Primary constructor with an injectable {@link PhysicsService} — the seam that lets
     * Enterprise bind a remote physics tier without touching this class (SCALING.md §2).
     * Pass {@code null} to bind the default {@link LocalPhysicsService} (Pro / in-process
     * core), which is exactly the pre-seam behaviour; every existing caller does this via
     * the shorter constructors.
     */
    public StructureManager(MaterialRegistry materials, PhysicsConfig config, PhysicsService physics) {
        this.materials = materials;
        this.config = config;
        // Share one work-counter across the physics engines AND the read-side grading
        // solver (CascadeEngine propagates it to its own solver) so /strux perf reports
        // cumulative engine work — identical attribution to before the seam.
        this.physics = physics != null ? physics : new LocalPhysicsService(config, metrics);
        this.stressSolver = new StressSolver(config);
        this.stressSolver.setMetrics(metrics);
        this.persistence = new StructurePersistenceCoordinator(graphStore, config);
        this.assessmentCache = new WorldAssessmentCache(graphStore, stressSolver);
    }

    /**
     * Per-call wall-clock budget for main-thread settles (block-break cascades and
     * cascade resumes), in nanoseconds. When a settle exceeds it, the core pauses
     * cooperatively and reports {@code truncated()} so the resume manager finishes
     * the collapse over later ticks — a too-big cascade becomes a delayed collapse,
     * never a frozen server. Zero or negative disables the budget (legacy behavior).
     */
    private volatile long settleBudgetNanos = 30_000_000L; // 30 ms default

    /** Set the main-thread settle budget in milliseconds; ≤ 0 disables it. */
    public void setSettleBudgetMs(double settleBudgetMs) {
        this.settleBudgetNanos = (long) (settleBudgetMs * 1_000_000.0);
    }

    /**
     * A pause supplier that trips once the settle budget (measured from now) is
     * spent. With the budget disabled it never trips.
     */
    private BooleanSupplier settleDeadline() {
        long budget = settleBudgetNanos;
        if (budget <= 0) {
            return () -> false;
        }
        long deadline = System.nanoTime() + budget;
        return () -> System.nanoTime() >= deadline;
    }

    /** Deterministic core work-counters (solves, node visits, blocks removed). */
    public StruxMetrics getMetrics() {
        return metrics;
    }

    /** Real wall-clock solve samples for the perf readout. */
    public PerfTracker getPerf() {
        return perf;
    }

    /**
     * Mark a world's structures as changed, bumping its revision. Read-only
     * caches (grade, visualizer scan) compare against this to know when to
     * refresh. Call after any structural mutation.
     */
    public void markDirty(World world) {
        graphStore.markDirty(world);
    }

    /** Current change-revision for a world (0 if never touched). */
    public long revision(World world) {
        return graphStore.revision(world);
    }

    /** Total blocks tracked across all worlds. */
    public int totalTrackedBlocks() {
        return graphStore.totalTrackedBlocks();
    }

    /**
     * Set the persistence adapter for saving/loading structures.
     */
    public void setPersistenceAdapter(PersistenceAdapter adapter) {
        persistence.setPersistenceAdapter(adapter);
    }

    /**
     * Set the logger for persistence messages.
     */
    public void setLogger(Logger logger) {
        persistence.setLogger(logger);
    }

    /**
     * Set the collapse guard used to protect regions/worlds and log removals.
     * When null (e.g. in unit tests), all removals are allowed and unlogged.
     */
    public void setCollapseGuard(CollapseGuard collapseGuard) {
        this.collapseGuard = collapseGuard;
    }

    /**
     * Set the budgeted blast queue, so callers can route a custom explosion through the
     * SAME blast model that handles TNT (intensity ÷ blast resistance, crater + cascade,
     * settled across ticks under budget). Wired by the plugin; null in unit tests.
     */
    public void setBlastProcessor(BlastProcessor blastProcessor) {
        this.blastProcessor = blastProcessor;
    }

    /**
     * Detonate a blast of the given {@code power} at {@code center}, processed by the strux
     * blast model exactly like a TNT explosion — so {@link
     * dev.gesp.structural.model.MaterialSpec#blastResistance() blast resistance} decides what
     * craters vs. merely cracks, damage accumulates across repeated hits, and the crater
     * triggers a cascade. Use this for siege engines (trebuchet boulders, etc.) instead of
     * removing blocks directly, so wall material actually matters.
     *
     * <p>No-op (returns false) when no blast processor is wired, the world has no tracked
     * structure, or the location is in a protected/disabled region. The solve happens a later
     * tick under the processor's budget, not synchronously.
     *
     * @return true if the blast was queued; false if it was skipped.
     */
    public boolean blast(World world, Location center, double power) {
        return blast(world, center, power, null);
    }

    /**
     * Detonate an attributed blast: same as {@link #blast(World, Location, double)} but tagging
     * the recorded {@link dev.gesp.structural.recording.BlastEvent} with {@code actorId} (e.g. the
     * trebuchet operator's UUID), so a replay can group destruction by who caused it. Pass
     * {@code null} for an anonymous blast.
     *
     * @return true if the blast was queued; false if it was skipped.
     */
    public boolean blast(World world, Location center, double power, String actorId) {
        if (blastProcessor == null || world == null || center == null || power <= 0) {
            return false;
        }
        StructureGraph graph = getGraph(world);
        if (graph == null || graph.isEmpty()) {
            return false;
        }
        if (collapseGuard != null && !collapseGuard.physicsAllowed(center)) {
            return false;
        }
        blastProcessor.enqueue(new QueuedBlast(world, center, power, actorId));
        return true;
    }

    /**
     * Set the foundation/terrain-grounding policy (depth grounding + foundation
     * block). When unset, defaults preserve legacy behaviour: depth grounding off
     * and no foundation block, so only bedrock / world-floor anchors a build.
     */
    public void setFoundationConfig(FoundationConfig foundationConfig) {
        this.foundationConfig = foundationConfig != null ? foundationConfig : new FoundationConfig();
    }

    /** The foundation/terrain-grounding policy (never null). */
    public FoundationConfig getFoundationConfig() {
        return foundationConfig;
    }

    /**
     * Get the persistence adapter.
     */
    public PersistenceAdapter getPersistenceAdapter() {
        return persistence.getPersistenceAdapter();
    }

    /**
     * Set the event recorder for capturing physics events.
     * When null, uses the NOOP recorder (zero overhead).
     */
    public void setEventRecorder(EventRecorder recorder) {
        this.eventRecorder = recorder != null ? recorder : EventRecorder.NOOP;
    }

    /**
     * Get the event recorder (never null; NOOP when recording is disabled).
     */
    public EventRecorder getEventRecorder() {
        return eventRecorder;
    }

    /**
     * Install the residency guard for component memory eviction. When set, every block
     * operation first re-materializes any evicted structure covering the addressed
     * position. Passing null restores the no-op default.
     */
    public void setResidencyGuard(ResidencyGuard guard) {
        this.residencyGuard = guard != null ? guard : (world, pos) -> {};
    }

    /**
     * Get the physics config.
     */
    public PhysicsConfig getConfig() {
        return config;
    }

    /**
     * Get the material registry for material lookups.
     */
    public MaterialRegistry getMaterialRegistry() {
        return materials;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  BLOCK OPERATIONS
    // ─────────────────────────────────────────────────────────────────────

    // Ground detection: only bedrock and blocks at world minimum height are ground.
    // This prevents marking entire terrain layers as ground, which breaks floating detection.

    /**
     * Register a block placement and check for overload collapse.
     *
     * @param block the Minecraft block that was placed
     * @return list of positions that collapsed due to overload (may be empty)
     */
    public List<NodePos> onBlockPlaced(Block block) {
        return onBlockPlaced(block, SolverCallback.NONE);
    }

    /**
     * Register a block placement and check for overload collapse.
     *
     * @param block    the Minecraft block that was placed
     * @param callback receives cascade events if collapse occurs
     * @return list of positions that collapsed due to overload (may be empty)
     */
    public List<NodePos> onBlockPlaced(Block block, SolverCallback callback) {
        // Non-solid blocks (fire, torches, flowers...) are not structural: they
        // carry no load and must not add any. Same rule as RegionScanner.
        if (block.getType().isAir() || !block.getType().isSolid()) {
            return Collections.emptyList();
        }
        StructureGraph graph = getOrCreateGraph(block.getWorld());
        NodePos pos = toBlockPos(block);
        residencyGuard.ensureResident(block.getWorld(), pos);
        MaterialSpec spec = materials.getSpec(block.getType());

        // Clear accumulated damage if a node already exists at this position.
        // This handles CoreProtect rollback: when a block is restored, any
        // damage from before the rollback should be cleared since it's a "fresh"
        // block. The next DamageVisualizer pass will clear crack overlays.
        Node existing = graph.getNode(pos);
        if (existing != null && existing.damage() > 0) {
            existing.repair();
        }

        // First, auto-detect ground from adjacent world blocks
        autoDetectGround(block, graph);

        addAsGroundOrBlock(block, pos, spec, graph);

        // Check for overloaded blocks and collapse them using progressive solver
        // (progressive solver calculates stress internally, processing farthest blocks first).
        // Scope to the placed block's support chain: when adding a block, load flows DOWN
        // through the support structure. Use getSupportAncestors (not getDependentSubgraph)
        // since we need blocks below that now bear more weight, not blocks above.
        //
        // NOTE: this scope is DELIBERATELY the support chain, not the full structure. The
        // scoped stress solve is scope-honest — it integrates load only over neighbours
        // inside this set (see StressSolver's in-scope guard), so a partial window on a
        // closed-loop topology (a curtain-wall ring) can no longer misread its excluded
        // lateral neighbours as phantom load sources and collapse members a whole-structure
        // solve says are fine.
        List<NodePos> collapsed = new ArrayList<>();
        Set<NodePos> region = graph.getSupportAncestors(pos);
        int scopeSize = graph.size();
        long start = System.nanoTime();
        collapseOverloadedBlocks(graph, region, collapsed, callback);
        perf.record(System.nanoTime() - start, scopeSize);

        markDirty(block.getWorld());
        return collapsed;
    }

    /**
     * Iteratively collapse overloaded blocks until structure is stable.
     * Uses progressive stress calculation to ensure blocks fail from farthest-from-ground first,
     * creating natural break points in structures like bridges.
     */
    private void collapseOverloadedBlocks(
            StructureGraph graph, Set<NodePos> region, List<NodePos> collapsed, SolverCallback callback) {
        int maxIterations = config.getMaxCascadeSteps();
        int iteration = 0;
        List<CollapsedNode> collapsedNodes = new ArrayList<>();

        // Debug: log stress before collapse
        if (config.isDebugLogging()) {
            System.out.println("[StructuralIntegrity] Checking for overloaded blocks (progressive mode)...");
            for (Node node : graph.getAllNodes()) {
                if (!node.isGrounded() && node.stressPercent() > 0.5) {
                    System.out.println("  " + node.pos() + " stress="
                            + String.format("%.1f%%", node.stressPercent() * 100)
                            + " (load="
                            + node.stressValue() + ", cap=" + node.spec().maxLoad() + ")");
                }
            }
        }

        while (iteration < maxIterations) {
            // Use progressive solver: finds the first overloaded block starting from farthest distance
            // This ensures blocks far from support fail BEFORE their load propagates to nearer blocks.
            // Scoped to the affected component (dead entries are skipped by the solver).
            NodePos overloadedPos = physics.solveProgressively(graph, region);

            if (overloadedPos == null) {
                break; // No overloaded blocks
            }

            Node node = graph.getNode(overloadedPos);
            double stress = node != null ? node.stressPercent() : 0;

            if (config.isDebugLogging() && node != null) {
                System.out.println("[StructuralIntegrity] Collapsing overloaded block: " + overloadedPos + " at "
                        + String.format("%.1f%%", stress * 100) + " (vertical="
                        + String.format("%.1f", node.verticalStress()) + ", moment="
                        + String.format("%.1f", node.momentStress()) + ", cap="
                        + node.spec().maxLoad() + ")");
            }

            // Capture node info before removal. Overloaded path keeps how loaded the
            // node was (stressAtCollapse) so the adapter can flag a near miss.
            CollapsedNode collapsedNode = node != null
                    ? CollapsedNode.fromOverloaded(node)
                    : new CollapsedNode(overloadedPos, new MaterialSpec(1.0, 100.0));

            // Collapse this block
            graph.removeBlock(overloadedPos);
            collapsed.add(overloadedPos);
            collapsedNodes.add(collapsedNode);
            callback.onCascadeStep(collapsedNode, ++iteration, CollapseReason.OVERLOADED);

            // Collapse any floating blocks
            Set<NodePos> floating = graph.getFloatingBlocks();
            if (!floating.isEmpty() && config.isDebugLogging()) {
                System.out.println("[StructuralIntegrity] Collapsing " + floating.size() + " floating blocks");
            }
            for (NodePos floatingPos : floating) {
                Node floatingNode = graph.getNode(floatingPos);
                CollapsedNode floatingCollapsed = floatingNode != null
                        ? CollapsedNode.from(floatingNode)
                        : new CollapsedNode(floatingPos, new MaterialSpec(1.0, 100.0));

                graph.removeBlock(floatingPos);
                collapsed.add(floatingPos);
                collapsedNodes.add(floatingCollapsed);
                callback.onCascadeStep(floatingCollapsed, ++iteration, CollapseReason.FLOATING);
            }

            // Note: solveProgressively is called at the start of each iteration,
            // so we don't need to call it again here
        }

        // Report the affected region's settled stress so a caller can warn about a block left
        // critically — but not fatally — loaded by this placement (the "⚠ CRITICAL STRESS"
        // action bar). Only built when the callback asks, since breaks/cascades don't want it.
        // The last solveProgressively (which returned null to end the loop) left the region's
        // stress current.
        if (callback.wantsStressUpdates()) {
            Map<NodePos, Double> stressMap = new HashMap<>();
            for (NodePos pos : region) {
                Node n = graph.getNode(pos);
                if (n != null && !n.isGrounded()) {
                    stressMap.put(pos, n.stressPercent());
                }
            }
            callback.onStressUpdated(stressMap);
        }

        if (!collapsedNodes.isEmpty()) {
            callback.onCascadeComplete(collapsedNodes);
        }
    }

    /**
     * Refresh ground detection for all tracked blocks in a world and collapse any
     * newly-overloaded blocks. Call this after explosions to ensure the graph has
     * accurate ground connections before stress calculations.
     *
     * @param world the world to refresh
     * @return list of positions that collapsed (may be empty)
     */
    public List<NodePos> refreshGroundAndCollapse(World world) {
        return refreshGroundAndCollapse(world, SolverCallback.NONE);
    }

    /**
     * Refresh ground detection for all tracked blocks in a world and collapse any
     * floating blocks. Uses fast floating-only detection (no stress recalc).
     *
     * @param world    the world to refresh
     * @param callback receives cascade events if collapse occurs
     * @return list of positions that collapsed (may be empty)
     */
    public List<NodePos> refreshGroundAndCollapse(World world, SolverCallback callback) {
        StructureGraph graph = getGraph(world);
        if (graph == null || graph.isEmpty()) {
            return Collections.emptyList();
        }

        // Fast path: collapse ALL floating blocks (no stress recalc needed)
        List<NodePos> collapsedPositions = new ArrayList<>();
        List<CollapsedNode> collapsedNodes = new ArrayList<>();
        Set<NodePos> floating;

        while (!(floating = graph.getFloatingBlocks()).isEmpty()) {
            for (NodePos pos : floating) {
                Node node = graph.getNode(pos);
                CollapsedNode collapsed =
                        node != null ? CollapsedNode.from(node) : new CollapsedNode(pos, new MaterialSpec(1.0, 100.0));

                graph.removeBlock(pos);
                collapsedPositions.add(pos);
                collapsedNodes.add(collapsed);
                callback.onCascadeStep(collapsed, collapsedPositions.size(), CollapseReason.FLOATING);
            }
        }

        if (!collapsedNodes.isEmpty()) {
            callback.onCascadeComplete(collapsedNodes);
            markDirty(world);
        }
        return collapsedPositions;
    }

    /**
     * Like {@link #refreshGroundAndCollapse(World)} but bounded to {@code scope} —
     * the region a core impact/blast settle already considered (its {@code
     * affectedScope}). Uses {@link StructureGraph#findFloatingInScope} instead of
     * the whole-graph {@link StructureGraph#getFloatingBlocks}, so the cost is
     * O(scope) rather than O(whole terrain). This is the per-impact / per-blast hot
     * path; the whole-graph overload stays for any caller that genuinely needs a
     * world-wide sweep.
     *
     * @param world the world to refresh
     * @param scope the affected region from the core result (may be empty)
     * @return list of positions that collapsed (may be empty)
     */
    public List<NodePos> refreshGroundAndCollapseInScope(World world, Set<NodePos> scope) {
        return refreshGroundAndCollapseInScope(world, scope, SolverCallback.NONE);
    }

    /**
     * Scoped variant of {@link #refreshGroundAndCollapse(World, SolverCallback)};
     * see {@link #refreshGroundAndCollapseInScope(World, Set)}.
     *
     * @param world    the world to refresh
     * @param scope    the affected region from the core result (may be empty)
     * @param callback receives cascade events if collapse occurs
     * @return list of positions that collapsed (may be empty)
     */
    public List<NodePos> refreshGroundAndCollapseInScope(World world, Set<NodePos> scope, SolverCallback callback) {
        StructureGraph graph = getGraph(world);
        if (graph == null || graph.isEmpty() || scope == null || scope.isEmpty()) {
            return Collections.emptyList();
        }

        List<NodePos> collapsedPositions = new ArrayList<>();
        List<CollapsedNode> collapsedNodes = new ArrayList<>();

        // Working scope: starts as the core's affected region and grows by the
        // surviving neighbours of each removed floater. Removing a floater can
        // orphan a region that reached ground only through it, so seeding the next
        // pass from the severed stubs keeps the scoped check complete — exactly the
        // severing the core settle does. Positions no longer in the graph are
        // skipped by findFloatingInScope, so removed blocks staying in the set is
        // harmless.
        Set<NodePos> workingScope = new HashSet<>(scope);

        Set<NodePos> floating;
        while (!(floating = graph.findFloatingInScope(workingScope)).isEmpty()) {
            for (NodePos pos : floating) {
                Node node = graph.getNode(pos);
                if (node == null) {
                    continue; // already gone (e.g. listed once, removed earlier this pass)
                }
                CollapsedNode collapsed = CollapsedNode.from(node);

                // Seed the next pass from this floater's surviving neighbours before
                // it is removed, so a region orphaned by its removal is re-examined.
                for (NodePos neighbor : graph.getNeighbors(pos)) {
                    Node neighborNode = graph.getNode(neighbor);
                    if (neighborNode != null && !neighborNode.isGrounded()) {
                        workingScope.add(neighbor);
                    }
                }

                graph.removeBlock(pos);
                collapsedPositions.add(pos);
                collapsedNodes.add(collapsed);
                callback.onCascadeStep(collapsed, collapsedPositions.size(), CollapseReason.FLOATING);
            }
        }

        if (!collapsedNodes.isEmpty()) {
            callback.onCascadeComplete(collapsedNodes);
            markDirty(world);
        }
        return collapsedPositions;
    }

    /**
     * Auto-detect ground blocks from the world.
     * If the placed block is adjacent to a solid world block at y <= GROUND_LEVEL,
     * or adjacent to bedrock, register that as ground.
     */
    private void autoDetectGround(Block placedBlock, StructureGraph graph) {
        autoDetectGroundAt(placedBlock, graph);
    }

    /**
     * Auto-detect ground blocks adjacent to the given block.
     * @return number of ground blocks added
     */
    private int autoDetectGroundAt(Block block, StructureGraph graph) {
        World world = block.getWorld();
        int added = 0;

        // Check all 6 adjacent positions
        int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

        for (int[] offset : offsets) {
            int adjY = block.getY() + offset[1];

            // Skip if out of world bounds
            if (adjY < world.getMinHeight() || adjY > world.getMaxHeight()) {
                continue;
            }

            Block adjacent = world.getBlockAt(block.getX() + offset[0], adjY, block.getZ() + offset[2]);

            NodePos adjacentPos = toBlockPos(adjacent);

            // Skip if already in graph
            if (graph.hasBlock(adjacentPos)) {
                continue;
            }

            // Only bedrock and blocks at world minimum height are ground.
            // This prevents marking entire terrain layers as ground.
            boolean isGround = adjacent.getType() == Material.BEDROCK
                    || (adjacent.getY() == world.getMinHeight()
                            && adjacent.getType().isSolid());

            if (isGround) {
                graph.addGroundBlock(adjacentPos);
                added++;
            }
        }
        return added;
    }

    /**
     * Process a block break and return cascade result.
     *
     * @param block    the block being broken
     * @param callback receives cascade events
     * @return what collapsed (may be empty)
     */
    public CascadeResult onBlockBroken(Block block, SolverCallback callback) {
        NodePos pos = toBlockPos(block);
        residencyGuard.ensureResident(block.getWorld(), pos);
        StructureGraph graph = getGraph(block.getWorld());
        if (graph == null) {
            return new CascadeResult(Collections.emptyList(), 0);
        }

        if (!graph.hasBlock(pos)) {
            return new CascadeResult(Collections.emptyList(), 0);
        }

        int scope = graph.size();
        long start = System.nanoTime();
        CascadeResult result = physics.onBreak(graph, pos, callback, settleDeadline());
        perf.record(System.nanoTime() - start, scope);
        markDirty(block.getWorld());
        return result;
    }

    /**
     * Process a block break with no callback.
     */
    public CascadeResult onBlockBroken(Block block) {
        return onBlockBroken(block, SolverCallback.NONE);
    }

    /**
     * Resume a previously TRUNCATED cascade by running one more capped settle
     * pass over the world's graph. A huge collapse can exceed {@code
     * maxCascadeSteps} on the break tick; that per-event cap leaves the structure
     * mid-collapse (overloaded or newly-floating leftovers). Calling this once per
     * tick — driven by {@link dev.gesp.structural.minecraft.listener.CascadeResumeManager}
     * — finishes the job over several ticks, each tick's work still bounded by the
     * cap so a single tick is never frozen by a worst-case structure.
     *
     * <p>The collapsed nodes flow back to the caller so they take the SAME
     * world-removal + effects + recording path as the original cascade's. This
     * does NOT remove a trigger block (there is none on a resume); it only
     * collapses what the disturbance already left in the graph.
     *
     * @param world the world whose graph to settle further
     * @return the collapsed nodes and whether the cap truncated this pass too
     */
    public CascadeEngine.SettleOutcome resumeCascade(World world) {
        return resumeCascade(world, java.util.Set.of());
    }

    /**
     * Resume a truncated cascade over a KNOWN disturbed region rather than the
     * whole graph. {@code seedScope} is the {@code remainingScope} the previous
     * (truncated) settle reported — or, for the first resume, the originating
     * cascade's {@code affectedScope()}. Settling on this bounded region keeps
     * the per-tick solver work proportional to the collapsing structure, not the
     * (terrain-sized) world graph: re-seeding {@code getAllPositions()} forced a
     * full-graph {@code MomentArmIndex} rebuild on every settle step and tripped
     * the Paper watchdog on large arenas.
     *
     * <p>An empty {@code seedScope} falls back to the whole graph — correct but
     * slow, used only by callers that have no scope to offer.
     */
    public CascadeEngine.SettleOutcome resumeCascade(World world, java.util.Set<NodePos> seedScope) {
        StructureGraph graph = getGraph(world);
        if (graph == null || graph.isEmpty()) {
            return new CascadeEngine.SettleOutcome(List.of(), false);
        }
        java.util.Set<NodePos> scope = (seedScope == null || seedScope.isEmpty())
                ? new java.util.HashSet<>(graph.getAllPositions())
                : seedScope;
        long start = System.nanoTime();
        CascadeEngine.SettleOutcome outcome = physics.settle(graph, scope, SolverCallback.NONE, settleDeadline());
        perf.record(System.nanoTime() - start, scope.size());
        if (!outcome.collapsed().isEmpty()) {
            markDirty(world);
        }
        return outcome;
    }

    /**
     * Apply a pre-computed collapse to the live graph by removing the collapsed
     * nodes. Used by the async settle path: the solve ran on a {@code copySubgraph}
     * snapshot (so the live graph was NOT mutated), and once the answer is accepted
     * the same nodes must be removed from the live graph before the world blocks are
     * streamed to air. Returns the number of nodes actually removed and marks the
     * world dirty if anything changed.
     */
    public int applyCollapsedToGraph(World world, List<CollapsedNode> collapsed) {
        StructureGraph graph = getGraph(world);
        if (graph == null || collapsed.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (CollapsedNode node : collapsed) {
            if (graph.removeBlock(node.pos()) != null) {
                removed++;
            }
        }
        if (removed > 0) {
            markDirty(world);
        }
        return removed;
    }

    /**
     * Check if placing a block would cause any collapse.
     * Useful for preventing "impossible" placements.
     *
     * @param block the block to check (already placed in world)
     * @return true if the structure is stable after this placement
     */
    public boolean wouldBeStable(Block block) {
        StructureGraph testGraph = getOrCreateGraph(block.getWorld()).copy();
        NodePos pos = toBlockPos(block);
        MaterialSpec spec = materials.getSpec(block.getType());

        testGraph.addBlock(pos, spec, false);

        // Check if any blocks would be overloaded
        StressSolver solver = new StressSolver(config);
        solver.solveAll(testGraph);

        for (Node node : testGraph.getAllNodes()) {
            if (node.isOverloaded()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Check if a block is part of a tracked structure.
     */
    public boolean isTracked(Block block) {
        StructureGraph graph = getGraph(block.getWorld());
        if (graph == null) {
            return false;
        }
        return graph.hasBlock(toBlockPos(block));
    }

    /**
     * Get stress percentage for a block (0.0 to 1.0+).
     *
     * @return stress percent, or -1 if block is not tracked
     */
    public double getStress(Block block) {
        StructureGraph graph = getGraph(block.getWorld());
        if (graph == null) {
            return -1;
        }

        NodePos pos = toBlockPos(block);
        var node = graph.getNode(pos);
        if (node == null) {
            return -1;
        }

        return node.stressPercent();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  REINFORCEMENT / REPAIR / GRADING
    // ─────────────────────────────────────────────────────────────────────

    /** Outcome of a reinforcement attempt. */
    public enum ReinforceResult {
        /** The block isn't tracked yet — scan or place it first. */
        NOT_TRACKED,
        /** Ground/foundation blocks can't be reinforced (already infinite). */
        IS_GROUND,
        /** Already at the configured maximum reinforcement. */
        AT_MAX,
        /** Reinforcement applied. */
        OK
    }

    /**
     * Check whether a block can be reinforced, without mutating anything. Lets
     * callers charge currency / consume an item only once they know the action
     * will take effect.
     */
    public ReinforceResult canReinforce(Block block, double max) {
        StructureGraph graph = getGraph(block.getWorld());
        if (graph == null) {
            return ReinforceResult.NOT_TRACKED;
        }
        Node node = graph.getNode(toBlockPos(block));
        if (node == null) {
            return ReinforceResult.NOT_TRACKED;
        }
        if (node.isGrounded()) {
            return ReinforceResult.IS_GROUND;
        }
        if (node.reinforcement() >= max) {
            return ReinforceResult.AT_MAX;
        }
        return ReinforceResult.OK;
    }

    /**
     * Raise a block's reinforcement multiplier, then re-solve so the structure
     * stabilizes immediately. The new value is clamped to {@code max}. Callers
     * should gate this behind {@link #canReinforce} so payment happens first.
     *
     * @return result + the (new) multiplier via {@link Reinforced}
     */
    public Reinforced reinforce(Block block, double add, double max) {
        ReinforceResult check = canReinforce(block, max);
        StructureGraph graph = getGraph(block.getWorld());
        Node node = graph != null ? graph.getNode(toBlockPos(block)) : null;
        if (check != ReinforceResult.OK || node == null) {
            return new Reinforced(check, node != null ? node.reinforcement() : 0.0);
        }
        double next = Math.min(max, node.reinforcement() + add);
        // Route through the graph so modCount moves: a reinforcement mid-siege must
        // invalidate any in-flight async solve over this block (it would otherwise
        // collapse a block the player just saved).
        graph.reinforceNode(toBlockPos(block), next);
        // Only blocks that depend on this one for support can change — use scoped solve.
        physics.solveScoped(graph, graph.getDependentSubgraph(toBlockPos(block)));
        markDirty(block.getWorld());
        return new Reinforced(ReinforceResult.OK, next);
    }

    /** Reinforcement attempt result + resulting multiplier. */
    public record Reinforced(ReinforceResult result, double multiplier) {}

    /**
     * Whether a block is a tracked, non-ground node with damage to clear.
     */
    public boolean isRepairable(Block block) {
        StructureGraph graph = getGraph(block.getWorld());
        if (graph == null) {
            return false;
        }
        Node node = graph.getNode(toBlockPos(block));
        return node != null && !node.isGrounded() && node.damage() > 0.0;
    }

    /**
     * Clear accumulated damage on a tracked block (a repair), then re-solve.
     *
     * @return true if a damaged, tracked, non-ground block was repaired.
     */
    public boolean repair(Block block) {
        StructureGraph graph = getGraph(block.getWorld());
        if (graph == null) {
            return false;
        }
        Node node = graph.getNode(toBlockPos(block));
        if (node == null || node.isGrounded() || node.damage() <= 0.0) {
            return false;
        }
        graph.repairNode(toBlockPos(block));
        // Only blocks that depend on this one for support can change — use scoped solve.
        physics.solveScoped(graph, graph.getDependentSubgraph(toBlockPos(block)));
        markDirty(block.getWorld());
        return true;
    }

    /**
     * The collapse atlas for a world, bound to its current graph. Rebuilt only if
     * the world's graph instance was swapped (e.g. reload); the atlas itself
     * self-invalidates on topology change.
     */
    public CollapseAtlas atlasFor(World world) {
        return assessmentCache.atlasFor(world);
    }

    /**
     * Predict how many tracked blocks lose support if this block is removed,
     * without simulating. -1 if the block isn't tracked.
     */
    public int predictCollapse(Block block) {
        CollapseAtlas atlas = atlasFor(block.getWorld());
        if (atlas == null) {
            return -1;
        }
        NodePos pos = toBlockPos(block);
        StructureGraph graph = getGraph(block.getWorld());
        if (graph == null || graph.getNode(pos) == null) {
            return -1;
        }
        return atlas.collapseSize(pos);
    }

    /**
     * Solve and grade every tracked structure in a world.
     *
     * @return the structural grade report (S grade / zeros if nothing tracked)
     */
    public StructureReport assessWorld(World world) {
        return assessmentCache.assessWorld(world);
    }

    /**
     * Remove a block directly from the structure graph without triggering cascade.
     * Used by DelayedCollapseManager after a block has finished its failure animation.
     */
    public void removeBlockDirect(World world, NodePos pos) {
        StructureGraph graph = getGraph(world);
        if (graph != null) {
            graph.removeBlock(pos);
            markDirty(world);
        }
    }

    /**
     * Register a placed block directly in the structure graph WITHOUT running the
     * overload solver. Symmetric to {@link #removeBlockDirect}; for bulk restoration
     * (e.g. a portcullis dropping shut) where each block only restores prior support
     * and cannot introduce a new overload, so the per-block cascade check that
     * {@link #onBlockPlaced(Block)} performs is wasted work. Callers placing dozens
     * of blocks at once avoid an O(n) explosion of solver passes.
     */
    public void addBlockDirect(Block block) {
        StructureGraph graph = getOrCreateGraph(block.getWorld());
        NodePos pos = toBlockPos(block);
        autoDetectGround(block, graph);
        addAsGroundOrBlock(block, pos, materials.getSpec(block.getType()), graph);
        markDirty(block.getWorld());
    }

    /**
     * Add {@code block} to the graph either as a ground anchor or as a normal
     * load-bearing node, applying the foundation grounding policy:
     *
     * <ul>
     *   <li>a {@link MaterialRegistry#isGround ground material} (bedrock/barrier)
     *       is always an anchor — unchanged legacy behaviour;
     *   <li>the configured {@link FoundationConfig#isFoundationBlock foundation
     *       block} anchors when it rests on solid terrain (a single solid block
     *       directly below);
     *   <li>any block with {@link FoundationConfig#getMinDepth N} contiguous solid
     *       natural-terrain blocks straight below it anchors (depth grounding),
     *       when that feature is enabled.
     * </ul>
     *
     * <p>With the default config (depth grounding off, no foundation block) this
     * collapses to the legacy "ground material → anchor, else normal node" rule.
     */
    private void addAsGroundOrBlock(Block block, NodePos pos, MaterialSpec spec, StructureGraph graph) {
        if (materials.isGround(block.getType())
                || TerrainGrounding.groundsAsFoundation(block, foundationConfig, materials)) {
            graph.addGroundBlock(pos);
        } else {
            graph.addBlock(pos, spec, false);
        }
    }

    /**
     * Trigger a cascade check for neighbors of a removed block.
     * Used after delayed collapse to handle any secondary collapses.
     */
    public void triggerCascadeCheck(World world, NodePos removedPos) {
        StructureGraph graph = getGraph(world);
        if (graph == null) {
            return;
        }

        // Recalculate stress only for blocks that could be affected by this removal.
        // Use getDependentSubgraph (not componentOf) to avoid pulling in the entire
        // terrain component — only blocks that structurally depend on the removed
        // block need recalculation.
        Set<NodePos> region = new HashSet<>();
        for (NodePos adj : graph.getAdjacentPositions(removedPos)) {
            if (graph.hasBlock(adj)) {
                region.addAll(graph.getDependentSubgraph(adj));
            }
        }
        if (!region.isEmpty()) {
            physics.solveScoped(graph, region);
        }

        // Check for floating blocks scoped to the affected region: a single removal
        // can only orphan blocks structurally dependent on it, so seeding the floating
        // scan from `region` (already the dependent subgraph of the removed block's
        // neighbours) is complete — O(region) not O(whole graph).
        if (!region.isEmpty()) {
            Set<NodePos> floating = graph.findFloatingInScope(region);
            for (NodePos pos : floating) {
                Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
                // Skip removal if this block sits in a protected region (leave it standing);
                // still drop it from the graph so we stop tracking it.
                if (!block.getType().isAir() && (collapseGuard == null || collapseGuard.claimRemoval(block))) {
                    block.setType(Material.AIR);
                }
                graph.removeBlock(pos);
            }
        }
        markDirty(world);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  WORLD MANAGEMENT
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Get the graph for a world (may be null).
     */
    public StructureGraph getGraph(World world) {
        return graphStore.getGraph(world);
    }

    /**
     * Get or create a graph for a world.
     */
    public StructureGraph getOrCreateGraph(World world) {
        return graphStore.getOrCreateGraph(world);
    }

    /**
     * Clear all tracked structures (e.g., on plugin reload).
     */
    public void clearAll() {
        graphStore.clearAll();
    }

    /**
     * Clear structures for a specific world.
     */
    public void clearWorld(World world) {
        graphStore.clearWorld(world);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  COORDINATE CONVERSION
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Convert Minecraft block to our NodePos.
     */
    public static NodePos toBlockPos(Block block) {
        return new NodePos(block.getX(), block.getY(), block.getZ());
    }

    /**
     * Convert Minecraft location to our NodePos.
     */
    public static NodePos toBlockPos(Location location) {
        return new NodePos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /**
     * Convert our NodePos to Minecraft Location.
     */
    public static Location toLocation(NodePos pos, World world) {
        return new Location(world, pos.x(), pos.y(), pos.z());
    }

    // ─────────────────────────────────────────────────────────────────────
    //  PERSISTENCE
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Save all world structures to persistence.
     *
     * @return future that completes when all saves are done
     */
    public CompletableFuture<Void> saveAllAsync() {
        return persistence.saveAllAsync();
    }

    /**
     * Set how long the synchronous persistence waits ({@link #saveAll},
     * {@link #loadAllWorlds}) may block before giving up. Defaults to 30s.
     * Visible for testing the timeout behaviour without a 30s wait.
     */
    public void setPersistenceWaitTimeout(Duration timeout) {
        persistence.setPersistenceWaitTimeout(timeout);
    }

    /**
     * Save all world structures synchronously, waiting at most
     * {@link #setPersistenceWaitTimeout the persistence wait timeout}.
     * Use sparingly (e.g., during server shutdown).
     *
     * <p>The wait is bounded on purpose: a save future that never completes
     * (a wedged disk, a broken adapter) used to hang shutdown — and any test
     * teardown that joins it — forever. Now it logs SEVERE and moves on; the
     * latest changes may not be on disk, but the server gets to shut down.
     */
    public void saveAll() {
        persistence.saveAll();
    }

    /**
     * Load every world's structures, waiting at most
     * {@link #setPersistenceWaitTimeout the persistence wait timeout}.
     *
     * <p>If loading does not finish in time, persistence is <b>disabled</b>
     * (the adapter is cleared) and this returns {@code false}: the server runs
     * on without the saved structures, and — crucially — a later save can never
     * overwrite the good data on disk with the partial state we booted with.
     *
     * @return true if every world loaded in time
     */
    public boolean loadAllWorlds(Collection<World> worlds) {
        return persistence.loadAllWorlds(worlds);
    }

    /**
     * Load every world's structures <b>without blocking</b> the calling (main)
     * thread, then publish each finished graph back on the main thread.
     *
     * <p>This is the startup path: {@code onEnable} must return immediately so a
     * big saved world doesn't freeze the boot. See
     * {@link StructurePersistenceCoordinator#loadAllWorldsAsync} for the full
     * detached-deserialize then main-thread-publish contract.
     *
     * @param plugin the plugin used to schedule the main-thread publish
     * @param worlds the worlds to load
     * @return a future that completes when every world's off-thread
     *     deserialization has finished (its publish is then scheduled on the
     *     main thread and runs on the next tick); never blocks the caller
     */
    public CompletableFuture<Void> loadAllWorldsAsync(Plugin plugin, Collection<World> worlds) {
        return persistence.loadAllWorldsAsync(plugin, worlds);
    }

    /**
     * Save a specific world's structures.
     *
     * @param world the world to save
     * @return future that completes when save is done
     */
    public CompletableFuture<Void> saveWorldAsync(World world) {
        return persistence.saveWorldAsync(world);
    }

    /**
     * Load structures for a world from persistence.
     *
     * @param world the world to load
     * @return future with true if data was loaded, false if no saved data
     */
    public CompletableFuture<Boolean> loadWorldAsync(World world) {
        return persistence.loadWorldAsync(world);
    }

    /**
     * Load a world's structures synchronously.
     *
     * @param world the world to load
     * @return true if data was loaded
     */
    public boolean loadWorld(World world) {
        return persistence.loadWorld(world);
    }
}
