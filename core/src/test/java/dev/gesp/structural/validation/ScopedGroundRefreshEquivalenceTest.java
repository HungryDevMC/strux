package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.blast.BlastContext;
import dev.gesp.structural.blast.BlastResult;
import dev.gesp.structural.blast.StruxExplosionEngine;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.impact.ImpactCallback;
import dev.gesp.structural.impact.ImpactContext;
import dev.gesp.structural.impact.ImpactEngine;
import dev.gesp.structural.impact.ImpactResult;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code perf-scoped-ground-refresh} change: the adapter's post-impact /
 * post-blast ground refresh switches from a WHOLE-graph floating scan to a SCOPED
 * one over exactly the region the core engine settled ({@code result.affectedScope}).
 *
 * <p>Three properties:
 *
 * <ol>
 *   <li><b>Event-equivalence.</b> For many seeded random structures and impacts /
 *       blasts, applying the WHOLE-graph refresh and the SCOPED refresh to two
 *       fresh copies of the same post-core-settle graph ends in the IDENTICAL
 *       collapsed set and final graph — for collapses CAUSED by the event. The
 *       two refresh algorithms are reproduced here as pure {@link StructureGraph}
 *       functions (the same algorithms the two adapter methods run).
 *   <li><b>The intentional semantic change.</b> A pre-existing floater OUTSIDE the
 *       event scope is swept by the whole-graph refresh but NOT by the scoped one
 *       — it was never the event's effect. Documented and pinned so the change is
 *       deliberate, not a silent regression.
 *   <li><b>Low-1: one traversal, not N.</b> The multi-seed
 *       {@link StructureGraph#getDependentSubgraph(java.util.Collection)} returns
 *       exactly the union of the per-seed {@link
 *       StructureGraph#getDependentSubgraph(NodePos)} results.
 * </ol>
 *
 * <p>Seeded; the failing seed is printed in every assertion message.
 */
@DisplayName("Scoped ground refresh ≡ whole-graph refresh for event-caused collapse")
class ScopedGroundRefreshEquivalenceTest {

    private static final int SEEDS = 40;

    private static PhysicsConfig config() {
        PhysicsConfig config = new PhysicsConfig();
        config.setDebrisImpactScale(0.0); // order-independent outcomes → exact equality
        return config;
    }

    // ───────────────────────── the two refresh algorithms ─────────────────────────
    // Reproductions of StructureManager.refreshGroundAndCollapse(world) and
    // refreshGroundAndCollapseInScope(world, scope) as pure graph functions.

    /** Old WHOLE-graph refresh: remove every floater in the graph, repeatedly. */
    private static List<NodePos> wholeGraphRefresh(StructureGraph g) {
        List<NodePos> collapsed = new ArrayList<>();
        Set<NodePos> floating;
        while (!(floating = g.getFloatingBlocks()).isEmpty()) {
            for (NodePos pos : floating) {
                if (g.getNode(pos) == null) {
                    continue;
                }
                g.removeBlock(pos);
                collapsed.add(pos);
            }
        }
        return collapsed;
    }

    /** New SCOPED refresh: mirror of StructureManager.refreshGroundAndCollapseInScope. */
    private static List<NodePos> scopedRefresh(StructureGraph g, Set<NodePos> scope) {
        if (scope == null || scope.isEmpty()) {
            return List.of();
        }
        List<NodePos> collapsed = new ArrayList<>();
        Set<NodePos> workingScope = new HashSet<>(scope);
        Set<NodePos> floating;
        while (!(floating = g.findFloatingInScope(workingScope)).isEmpty()) {
            for (NodePos pos : floating) {
                Node node = g.getNode(pos);
                if (node == null) {
                    continue;
                }
                for (NodePos neighbor : g.getNeighbors(pos)) {
                    Node neighborNode = g.getNode(neighbor);
                    if (neighborNode != null && !neighborNode.isGrounded()) {
                        workingScope.add(neighbor);
                    }
                }
                g.removeBlock(pos);
                collapsed.add(pos);
            }
        }
        return collapsed;
    }

    // ───────────────────────── structure generation ─────────────────────────

    /**
     * A deterministic world: a shared ground strip carrying towers, beams off the
     * tops, and hanging chains — the shapes whose support can be cut so a refresh
     * actually has floaters to find.
     */
    private static StructureGraph generate(long seed) {
        Random rng = new Random(seed);
        StructureGraph g = new StructureGraph();
        int lanes = 3 + rng.nextInt(3);
        int laneWidth = 8;
        for (int x = 0; x < lanes * laneWidth; x++) {
            g.addGroundBlock(new NodePos(x, 0, 0));
        }
        for (int lane = 0; lane < lanes; lane++) {
            int baseX = lane * laneWidth;
            int height = 2 + rng.nextInt(4);
            for (int y = 1; y <= height; y++) {
                g.addBlock(new NodePos(baseX, y, 0), TestMaterials.LIGHT, false);
            }
            if (rng.nextBoolean()) {
                int armLength = 1 + rng.nextInt(3);
                for (int i = 1; i <= armLength; i++) {
                    g.addBlock(new NodePos(baseX + i, height, 0), TestMaterials.LIGHT, false);
                }
                if (armLength >= 2 && rng.nextBoolean() && height >= 2) {
                    int hang = 1 + rng.nextInt(Math.min(2, height - 1));
                    for (int j = 1; j <= hang; j++) {
                        g.addBlock(new NodePos(baseX + armLength, height - j, 0), TestMaterials.LIGHT, false);
                    }
                }
            }
        }
        return g;
    }

    private static List<NodePos> standingTargets(StructureGraph g) {
        List<NodePos> targets = new ArrayList<>();
        for (Node node : g.getAllNodes()) {
            if (!node.isGrounded()) {
                targets.add(node.pos());
            }
        }
        targets.sort(NodePos.CANONICAL_ORDER);
        return targets;
    }

    // ───────────────────────── property 1: event-equivalence ─────────────────────────

    @Test
    @DisplayName("Impact: whole-graph refresh ≡ scoped refresh after the core settle")
    void impactRefreshEquivalence() {
        ImpactEngine engine = new ImpactEngine(config());
        int exercised = 0;
        for (long seed = 1; seed <= SEEDS; seed++) {
            StructureGraph base = generate(seed);
            List<NodePos> targets = standingTargets(base);
            if (targets.isEmpty()) {
                continue;
            }
            NodePos hit = targets.get((int) (seed % targets.size()));

            // Run the core impact on a working copy with a high-energy downward
            // shot so it punches through and the cascade scope is non-trivial.
            StructureGraph settled = base.copy();
            ImpactResult result = engine.process(
                    settled,
                    ImpactContext.builder()
                            .origin(hit)
                            .direction(0, -1, 0)
                            .energy(50.0)
                            .build());

            // Apply each refresh to a fresh copy of the SAME post-settle graph.
            StructureGraph whole = settled.copy();
            StructureGraph scoped = settled.copy();
            Set<NodePos> wholeCollapsed = new HashSet<>(wholeGraphRefresh(whole));
            Set<NodePos> scopedCollapsed = new HashSet<>(scopedRefresh(scoped, result.affectedScope()));

            assertEquals(
                    wholeCollapsed,
                    scopedCollapsed,
                    "seed " + seed + ", hit " + hit + ": scoped refresh must collapse the same event blocks");
            assertEquals(
                    new HashSet<>(whole.getAllPositions()),
                    new HashSet<>(scoped.getAllPositions()),
                    "seed " + seed + ", hit " + hit + ": both refreshes must leave the identical world");
            exercised++;
        }
        assertTrue(exercised > 0, "the property must actually run on at least one structure");
    }

    @Test
    @DisplayName("Blast: whole-graph refresh ≡ scoped refresh after the core settle")
    void blastRefreshEquivalence() {
        StruxExplosionEngine engine = new StruxExplosionEngine(config());
        int exercised = 0;
        for (long seed = 1; seed <= SEEDS; seed++) {
            StructureGraph base = generate(seed);
            List<NodePos> targets = standingTargets(base);
            if (targets.isEmpty()) {
                continue;
            }
            NodePos center = targets.get((int) (seed % targets.size()));

            StructureGraph settled = base.copy();
            BlastResult result = engine.process(
                    settled, BlastContext.builder().center(center).power(4.0).build());

            StructureGraph whole = settled.copy();
            StructureGraph scoped = settled.copy();
            Set<NodePos> wholeCollapsed = new HashSet<>(wholeGraphRefresh(whole));
            Set<NodePos> scopedCollapsed = new HashSet<>(scopedRefresh(scoped, result.affectedScope()));

            assertEquals(
                    wholeCollapsed,
                    scopedCollapsed,
                    "seed " + seed + ", center " + center + ": scoped refresh must collapse the same event blocks");
            assertEquals(
                    new HashSet<>(whole.getAllPositions()),
                    new HashSet<>(scoped.getAllPositions()),
                    "seed " + seed + ", center " + center + ": both refreshes must leave the identical world");
            exercised++;
        }
        assertTrue(exercised > 0, "the property must actually run on at least one structure");
    }

    /**
     * An adapter-side ground change can leave a fresh floater INSIDE the affected
     * region after the core settle (the case the refresh exists to catch). Both
     * refreshes must collapse it identically.
     */
    @Test
    @DisplayName("In-scope floater introduced after settle is collapsed by both refreshes")
    void inScopeFloaterCollapsedByBoth() {
        // tower of 3 on ground; the dependent scope of the base block covers the
        // whole tower. Cut the base out from under the graph (an external ground
        // change), leaving the top two floating WITHIN the scope.
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 3; y++) {
            g.addBlock(new NodePos(0, y, 0), TestMaterials.LIGHT, false);
        }
        Set<NodePos> scope = g.getDependentSubgraph(new NodePos(0, 1, 0));

        // Simulate the adapter-side removal of the base support AFTER the core settle.
        g.removeBlock(new NodePos(0, 1, 0));

        StructureGraph whole = g.copy();
        StructureGraph scoped = g.copy();
        Set<NodePos> wholeCollapsed = new HashSet<>(wholeGraphRefresh(whole));
        Set<NodePos> scopedCollapsed = new HashSet<>(scopedRefresh(scoped, scope));

        assertEquals(Set.of(new NodePos(0, 2, 0), new NodePos(0, 3, 0)), wholeCollapsed);
        assertEquals(wholeCollapsed, scopedCollapsed, "scoped refresh must catch in-scope floaters");
        assertEquals(new HashSet<>(whole.getAllPositions()), new HashSet<>(scoped.getAllPositions()));
    }

    // ───────────────── property 2: the intentional semantic change ─────────────────

    @Test
    @DisplayName("Pre-existing OUT-OF-scope floater: swept by whole-graph, left by scoped (intended)")
    void outOfScopeFloaterNotSweptByScoped() {
        StructureGraph g = new StructureGraph();
        // Lane A: the event region — a grounded standing block, nothing to collapse.
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), TestMaterials.LIGHT, false);
        // Lane B: a pre-existing floater far away, unrelated to the event.
        NodePos strayFloater = new NodePos(50, 5, 0);
        g.addBlock(strayFloater, TestMaterials.LIGHT, false);

        // The event's scope only covers lane A.
        Set<NodePos> scope = g.getDependentSubgraph(new NodePos(0, 1, 0));

        StructureGraph whole = g.copy();
        StructureGraph scoped = g.copy();
        Set<NodePos> wholeCollapsed = new HashSet<>(wholeGraphRefresh(whole));
        Set<NodePos> scopedCollapsed = new HashSet<>(scopedRefresh(scoped, scope));

        assertTrue(
                wholeCollapsed.contains(strayFloater),
                "the whole-graph sweep incidentally removes the unrelated floater");
        assertFalse(
                scopedCollapsed.contains(strayFloater),
                "the scoped refresh leaves an out-of-scope floater — it was never this event's effect");
        assertTrue(scoped.hasBlock(strayFloater), "the stray floater survives the scoped refresh");
    }

    // ───────────────── property 3: Low-1 multi-seed == union of per-seed ─────────────────

    @Test
    @DisplayName("Multi-seed getDependentSubgraph equals the union of per-seed results")
    void multiSeedScopeEqualsUnionOfPerSeed() {
        for (long seed = 1; seed <= SEEDS; seed++) {
            StructureGraph g = generate(seed);
            List<NodePos> all = standingTargets(g);
            if (all.size() < 2) {
                continue;
            }
            // Choose a spread of seeds whose dependent subgraphs overlap.
            Set<NodePos> seeds = new LinkedHashSet<>();
            for (int i = 0; i < all.size(); i += 3) {
                seeds.add(all.get(i));
            }
            seeds.add(all.get(0));

            Set<NodePos> union = new HashSet<>();
            for (NodePos s : seeds) {
                union.addAll(g.getDependentSubgraph(s));
            }
            Set<NodePos> multi = g.getDependentSubgraph(seeds);

            assertEquals(
                    union,
                    multi,
                    "seed " + seed + ": one multi-seed BFS must equal the union of per-seed dependent subgraphs");
        }
    }

    @Test
    @DisplayName("A vetoed impact reports an empty result with an empty affected scope")
    void vetoedImpactHasEmptyScope() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), TestMaterials.LIGHT, false);

        ImpactResult result = new ImpactEngine(config())
                .process(
                        g,
                        ImpactContext.builder()
                                .origin(new NodePos(0, 1, 0))
                                .energy(50.0)
                                .build(),
                        new ImpactCallback() {
                            @Override
                            public boolean onImpact(ImpactContext ctx) {
                                return false; // veto: nothing should happen
                            }
                        });

        assertTrue(result.penetrated().isEmpty(), "a vetoed impact penetrates nothing");
        assertTrue(result.collapsed().isEmpty(), "a vetoed impact collapses nothing");
        assertTrue(result.affectedScope().isEmpty(), "a vetoed impact touches no scope");
        assertTrue(g.hasBlock(new NodePos(0, 1, 0)), "the block survives a vetoed impact");
    }

    @Test
    @DisplayName("Multi-seed getDependentSubgraph skips seeds not in the graph")
    void multiSeedSkipsMissingSeeds() {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), TestMaterials.LIGHT, false);

        NodePos present = new NodePos(0, 1, 0);
        NodePos missing = new NodePos(99, 99, 0);

        Set<NodePos> withMissing = g.getDependentSubgraph(List.of(present, missing));
        Set<NodePos> presentOnly = g.getDependentSubgraph(present);

        assertEquals(presentOnly, withMissing, "absent seeds contribute nothing");
        assertFalse(withMissing.contains(missing), "an absent seed is never in the result");
    }
}
