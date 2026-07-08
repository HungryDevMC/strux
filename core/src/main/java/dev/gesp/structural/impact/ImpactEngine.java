package dev.gesp.structural.impact;

import dev.gesp.structural.api.CollapseReason;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.metrics.StruxMetrics;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import dev.gesp.structural.solver.CascadeEngine.SettleOutcome;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Turns a kinetic impact — an arrow, a catapult stone, a ram strike — into
 * structural consequences, by spending the projectile's energy along its path:
 *
 * <pre>
 *   Phase 1 — penetration: ray-cast from the struck block along the travel
 *             direction. Each solid block in the path absorbs energy up to its
 *             toughness (blastResistance × penetrationCost); the absorbed share
 *             becomes persistent damage. A block that can pay its full toughness
 *             is punched through (destroyed); the projectile carries the
 *             remainder onward to the next block until the energy runs out, the
 *             ground stops it, or it has bored through {@code impactMaxPenetration}
 *             blocks.
 *
 *   Phase 2 — gravity: hand the now-holed, weakened graph to CascadeEngine.settle,
 *             which collapses whatever can no longer hold itself up.
 * </pre>
 *
 * <p>This single energy budget unifies what would otherwise be three features:
 * a low-energy hit only cracks the surface block (impact fatigue — ten arrows
 * accumulate because {@code damage} persists), while a high-energy hit punches
 * deep into the interior (penetration). Because damage persists across solves,
 * coordinated strikes are rewarded for free — no timing bonus needed.
 *
 * <p>Pure physics, no game dependencies. The caller supplies the energy scalar
 * ({@code ½·m·v²}, computed where real velocity exists); the engine, which has
 * no length or time unit, only spends it.
 */
public final class ImpactEngine {

    private final PhysicsConfig config;

    /** Reused across calls; stateless between invocations (graph + config only). */
    private final CascadeEngine cascade;

    /** Optional work-counter; null on the production path. See {@link StruxMetrics}. */
    private StruxMetrics metrics;

    public ImpactEngine() {
        this(new PhysicsConfig());
    }

    public ImpactEngine(PhysicsConfig config) {
        this.config = config;
        this.cascade = new CascadeEngine(config);
    }

    /**
     * Attach (or detach, with {@code null}) a work-counter. Optional; only
     * tests/benchmarks need this. Returns {@code this} for chaining.
     */
    public ImpactEngine setMetrics(StruxMetrics metrics) {
        this.metrics = metrics;
        return this;
    }

    public ImpactResult process(StructureGraph graph, ImpactContext ctx) {
        return process(graph, ctx, ImpactCallback.NONE);
    }

    public ImpactResult process(StructureGraph graph, ImpactContext ctx, ImpactCallback cb) {
        return process(graph, ctx, cb, () -> false);
    }

    /**
     * Like {@link #process(StructureGraph, ImpactContext, ImpactCallback)} but the
     * phase-2 gravity settle is cooperatively pausable — see
     * {@link CascadeEngine#settleResult(StructureGraph, Set, SolverCallback, BooleanSupplier)}.
     * When the budget pauses it, the result reports {@code truncated()} and the
     * caller resumes the remaining collapse on later ticks; penetration (phase 1)
     * is always completed in full — it is bounded by {@code impactMaxPenetration},
     * never by structure size.
     */
    public ImpactResult process(StructureGraph graph, ImpactContext ctx, ImpactCallback cb, BooleanSupplier pause) {
        if (!cb.onImpact(ctx)) {
            return ImpactResult.empty();
        }

        // ── Phase 1: spend the energy budget along the projectile path ────────
        List<NodePos> penetrated = new ArrayList<>();
        Map<NodePos, Double> damaged = new HashMap<>();

        double remaining = ctx.energy();
        double cost = config.getImpactPenetrationCost();
        double scale = config.getImpactDamageScale();
        int bored = 0;

        for (NodePos pos : pathBlocks(graph, ctx)) {
            if (remaining <= 0.0 || bored >= config.getImpactMaxPenetration()) {
                break;
            }
            Node node = graph.getNode(pos);
            if (node == null) {
                continue; // empty space — the projectile loses no energy in air
            }
            if (node.isGrounded()) {
                break; // the earth stops it
            }

            double capacity = node.spec().blastResistance() * cost;
            double absorbed = Math.min(remaining, capacity);
            node.addDamage((absorbed / capacity) * scale);
            remaining -= absorbed;
            bored++;

            if (node.isDestroyed()) {
                penetrated.add(pos);
            } else {
                damaged.put(pos, node.damage());
                cb.onDamaged(pos, node.damage());
            }
        }

        // ── Does gravity even need to run? ───────────────────────────────────
        // Penetration removes blocks, which can orphan a whole region, so a
        // penetrating hit ALWAYS settles. A damage-only hit is different: damage
        // lowers a block's own capacity (effectiveMaxLoad) but changes no load, no
        // geometry, and no connectivity — so the ONLY block that can newly fail is
        // a block this hit just damaged. If none of them crossed their (now-lower)
        // failure line, nothing in the structure can move, and the dependent-subgraph
        // build + full settle below would be pure wasted work over everything resting
        // on the struck block (the whole wall above an arrow, re-solved per arrow).
        //
        // So short-circuit exactly as the fire / weather / entity-weight tick paths
        // already do (see FireScorchTask: "only pay for a full settle once a block
        // has actually crossed the line"). The struck block's stress is current: the
        // adapter keeps placed structures solved, and damage never changes stress.
        if (penetrated.isEmpty()) {
            boolean anyOverloaded = false;
            for (NodePos pos : damaged.keySet()) {
                Node node = graph.getNode(pos);
                if (node != null && node.isOverloaded()) {
                    anyOverloaded = true;
                    break;
                }
            }
            if (!anyOverloaded) {
                // Surface chipping only — record the damage, skip the settle.
                ImpactResult result = new ImpactResult(penetrated, List.of(), damaged, Set.of(), false);
                cb.onComplete(result);
                return result;
            }
        }

        // Build the affected scope: penetrated + damaged + their dependents.
        // This bounds settle operations to the impact-affected region instead of
        // the entire graph (critical for large terrain). All seeds go into ONE
        // multi-seed BFS rather than one BFS per seed: the union is identical, but
        // a block reachable from several seeds is visited once, not once per seed.
        Set<NodePos> seeds = new HashSet<>(penetrated);
        seeds.addAll(damaged.keySet());
        Set<NodePos> affectedScope = graph.getDependentSubgraph(seeds);

        // Removing a block can ORPHAN a region that was reachable to ground only
        // THROUGH it — and that region is, by definition, no longer reachable
        // from the removed block once it is gone, so the dependent-subgraph
        // (computed before removal) can miss it. Seed the settle from each
        // penetrated block's surviving neighbors: those are the severed stubs on
        // every side of the hole, so the floating BFS in settle starts from the
        // edge of any newly-disconnected region and proves it groundless.
        // seeds already contains every penetrated position (it was seeded from
        // `penetrated` above), so reuse it for the O(1) membership test instead
        // of ArrayList.contains — fixes O(P²) to O(P·neighbors).
        for (NodePos pos : penetrated) {
            for (NodePos neighbor : graph.getNeighbors(pos)) {
                if (!seeds.contains(neighbor)) {
                    affectedScope.add(neighbor);
                }
            }
        }

        // Remove penetrated blocks.
        // affectedScope is passed directly to ImpactResult (whose constructor calls
        // Set.copyOf). The ImpactResult javadoc explicitly permits removed/collapsed
        // positions in the scope ("a scoped refresh simply skips ones no longer in
        // the graph"), so we no longer need a defensive snapshot copy here — the
        // Set.copyOf in the constructor is the one required copy.
        for (NodePos pos : penetrated) {
            graph.removeBlock(pos);
            cb.onPenetrate(pos);
        }
        if (metrics != null) {
            metrics.blocksRemoved += penetrated.size();
        }

        // ── Phase 2: let gravity settle the weakened, holed structure ─────────
        List<NodePos> collapsed = new ArrayList<>();
        cascade.setMetrics(metrics);
        SettleOutcome outcome = cascade.settleResult(
                graph,
                affectedScope,
                new SolverCallback() {
                    @Override
                    public void onStressUpdated(Map<NodePos, Double> stressMap) {}

                    @Override
                    public void onCascadeStep(CollapsedNode node, int stepNumber, CollapseReason reason) {
                        collapsed.add(node.pos());
                        cb.onCollapse(node.pos());
                    }

                    @Override
                    public void onCascadeComplete(List<CollapsedNode> allCollapsed) {}
                },
                pause);
        // settleResult returns the same nodes it reported; the callback already captured them.
        assert outcome.collapsed().size() == collapsed.size();

        ImpactResult result = new ImpactResult(penetrated, collapsed, damaged, affectedScope, outcome.truncated());
        cb.onComplete(result);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────

    /**
     * The ordered, distinct voxels the projectile passes through, from the
     * struck block along the travel direction. A point impact (zero direction)
     * yields just the origin. The march is bounded so a stray ray can never walk
     * the whole world.
     */
    private List<NodePos> pathBlocks(StructureGraph graph, ImpactContext ctx) {
        NodePos origin = ctx.origin();
        double len = ctx.directionLength();
        if (len == 0.0) {
            return List.of(origin); // point impact: only the surface block
        }

        // Travel far enough to bore through the penetration limit even across a
        // few air gaps, but no further — keeps the march cheap and bounded.
        double reach = config.getImpactMaxPenetration() + 4.0;
        double nx = ctx.dirX() / len;
        double ny = ctx.dirY() / len;
        double nz = ctx.dirZ() / len;

        List<NodePos> path = new ArrayList<>();
        Set<NodePos> seen = new HashSet<>();
        path.add(origin);
        seen.add(origin);

        int steps = (int) Math.ceil(reach * 2.0); // ~2 samples per block
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / 2.0;
            NodePos p = new NodePos((int) Math.round(origin.x() + nx * t), (int) Math.round(origin.y() + ny * t), (int)
                    Math.round(origin.z() + nz * t));
            if (seen.add(p)) {
                path.add(p);
            }
        }
        return path;
    }
}
