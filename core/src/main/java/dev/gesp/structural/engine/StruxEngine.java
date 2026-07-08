package dev.gesp.structural.engine;

import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.assess.CollapseAtlas;
import dev.gesp.structural.assess.StructureGrader;
import dev.gesp.structural.assess.StructureReport;
import dev.gesp.structural.assess.TipResult;
import dev.gesp.structural.assess.TippingAnalyzer;
import dev.gesp.structural.blast.BlastCallback;
import dev.gesp.structural.blast.BlastContext;
import dev.gesp.structural.blast.BlastResult;
import dev.gesp.structural.blast.StruxExplosionEngine;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.metrics.StruxMetrics;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import dev.gesp.structural.solver.CascadeResult;
import dev.gesp.structural.solver.StressSolver;

/**
 * The one-object embedding surface for the Strux physics engine — the public
 * "SDK" facade.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                         STRUX ENGINE                               │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Internally strux is a graph + a stress solver + cascade/blast      │
 *   │  engines + a grader. Adapters wire those together themselves. This  │
 *   │  facade bundles them behind one fluent object so a NEW host (a      │
 *   │  Hytale server, a standalone game, a server you license to a        │
 *   │  network) can embed the engine in a dozen lines and never touch     │
 *   │  the internals:                                                     │
 *   │                                                                     │
 *   │      StruxEngine e = new StruxEngine();                             │
 *   │      e.addGround(0, 0, 0).addBlock(0, 1, 0, stone);                 │
 *   │      e.solve();                                                     │
 *   │      CascadeResult fell = e.breakBlock(0, 1, 0);                    │
 *   │      StructureReport grade = e.assess();                           │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>Pure physics, zero game types — the same hard rule as the rest of {@code
 * core}. Host code maps its own block/material concept onto {@link NodePos} +
 * {@link MaterialSpec}; the engine never knows what a node "is".
 *
 * <p>Not thread-safe: drive one engine from one thread (typically a server's
 * main/tick thread), as the Minecraft adapter does.
 */
public final class StruxEngine {

    private final PhysicsConfig config;
    private final StructureGraph graph;
    private final StressSolver solver;
    private final CascadeEngine cascade;
    private final StruxExplosionEngine blast;
    private final StruxMetrics metrics = new StruxMetrics();
    private final CollapseAtlas atlas;

    /** Create an engine with default physics tuning. */
    public StruxEngine() {
        this(new PhysicsConfig());
    }

    /** Create an engine with custom physics tuning. */
    public StruxEngine(PhysicsConfig config) {
        this.config = config;
        this.graph = new StructureGraph();
        this.solver = new StressSolver(config).setMetrics(metrics);
        this.cascade = new CascadeEngine(config).setMetrics(metrics);
        this.blast = new StruxExplosionEngine(config).setMetrics(metrics);
        this.atlas = new CollapseAtlas(graph);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  BUILDING
    // ─────────────────────────────────────────────────────────────────────

    /** Add a load-bearing block (grid-connected to existing neighbors). */
    public StruxEngine addBlock(NodePos pos, MaterialSpec spec) {
        graph.addBlock(pos, spec, false);
        return this;
    }

    /** Add a load-bearing block at integer coordinates. */
    public StruxEngine addBlock(int x, int y, int z, MaterialSpec spec) {
        return addBlock(new NodePos(x, y, z), spec);
    }

    /** Add a foundation/anchor block (infinite capacity, never falls). */
    public StruxEngine addGround(NodePos pos) {
        graph.addGroundBlock(pos);
        return this;
    }

    /** Add a foundation/anchor block at integer coordinates. */
    public StruxEngine addGround(int x, int y, int z) {
        return addGround(new NodePos(x, y, z));
    }

    /** Remove a block without running physics (e.g. host-side edit). */
    public StruxEngine removeBlock(NodePos pos) {
        graph.removeBlock(pos);
        return this;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SIMULATION
    // ─────────────────────────────────────────────────────────────────────

    /** Recompute stress across the whole structure. */
    public void solve() {
        solver.solveAll(graph);
    }

    /** Break a block and cascade-collapse whatever can no longer hold. */
    public CascadeResult breakBlock(NodePos pos) {
        return cascade.cascade(graph, pos, SolverCallback.NONE);
    }

    /** Break a block at integer coordinates and cascade. */
    public CascadeResult breakBlock(int x, int y, int z) {
        return breakBlock(new NodePos(x, y, z));
    }

    /** Break a block and cascade, receiving per-step events (for animation/FX). */
    public CascadeResult breakBlock(NodePos pos, SolverCallback callback) {
        return cascade.cascade(graph, pos, callback);
    }

    /** Detonate an explosion: crater + cumulative damage + gravity cascade. */
    public BlastResult detonate(BlastContext ctx) {
        return blast.process(graph, ctx);
    }

    /** Detonate an explosion, receiving per-block events (for animation/FX). */
    public BlastResult detonate(BlastContext ctx, BlastCallback callback) {
        return blast.process(graph, ctx, callback);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  ANALYSIS / TUNING
    // ─────────────────────────────────────────────────────────────────────

    /** Solve, then grade the structure (S/A/B/C/F + peak/avg stress). */
    public StructureReport assess() {
        solve();
        return StructureGrader.assess(graph);
    }

    /** Current stress fraction at a position (1.0 = at capacity), or -1 if absent. */
    public double stressAt(NodePos pos) {
        Node node = graph.getNode(pos);
        return node == null ? -1.0 : node.stressPercent();
    }

    /**
     * Predict, without simulating, how many blocks lose all support if {@code pos}
     * is removed — the connectivity collapse. Backed by a cached {@link CollapseAtlas}
     * that survives damage-only changes and only rebuilds when topology changes.
     */
    public int predictCollapse(NodePos pos) {
        return atlas.collapseSize(pos);
    }

    /** The collapse atlas, for richer what-if queries (dependents, weak-point ranking). */
    public CollapseAtlas collapseAtlas() {
        return atlas;
    }

    /**
     * Would the structure containing {@code pos} topple as a rigid body — its
     * center of mass projecting outside the footprint it stands on? This is a
     * pure read-only statics query (CoM vs. support polygon); it never solves,
     * mutates the graph, or runs a cascade. The component of {@code pos} is
     * computed, then its tip verdict returned. A position not in the graph (or a
     * component with no non-ground mass) reads as stable.
     */
    public TipResult wouldTip(NodePos pos) {
        if (!graph.hasBlock(pos)) {
            return TipResult.stable();
        }
        var component = graph.componentOf(pos);
        var centroid = TippingAnalyzer.centerOfMass(graph, component);
        var polygon = TippingAnalyzer.supportPolygon(graph, component);
        return TippingAnalyzer.tips(centroid, polygon);
    }

    /** Would the structure at integer coordinates topple? See {@link #wouldTip(NodePos)}. */
    public TipResult wouldTip(int x, int y, int z) {
        return wouldTip(new NodePos(x, y, z));
    }

    /** Set a block's reinforcement multiplier (>= 1.0; raises load capacity). */
    public StruxEngine reinforce(NodePos pos, double multiplier) {
        Node node = graph.getNode(pos);
        if (node != null) {
            node.setReinforcement(multiplier);
        }
        return this;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  ACCESS (for hosts that need the internals)
    // ─────────────────────────────────────────────────────────────────────

    /** The underlying graph, for hosts that need direct node access. */
    public StructureGraph graph() {
        return graph;
    }

    /** Cumulative engine work-counters since creation. */
    public StruxMetrics metrics() {
        return metrics;
    }

    /** The physics tuning in effect. */
    public PhysicsConfig config() {
        return config;
    }

    /** How many blocks are currently tracked. */
    public int size() {
        return graph.size();
    }
}
