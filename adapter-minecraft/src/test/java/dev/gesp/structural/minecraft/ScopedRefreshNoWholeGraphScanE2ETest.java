package dev.gesp.structural.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.api.CollapseReason;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.listener.BlastProcessor;
import dev.gesp.structural.minecraft.listener.ImpactProcessor;
import dev.gesp.structural.minecraft.listener.QueuedBlast;
import dev.gesp.structural.minecraft.listener.QueuedImpact;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Pins the hot-path half of {@code perf-scoped-ground-refresh}: after an impact or
 * a blast, the processors do their ground-refresh over the core's affected SCOPE,
 * never a whole-graph floating scan. The check is direct — a {@link StructureGraph}
 * spy that counts {@code getFloatingBlocks()} calls is swapped into the manager, and
 * the count must stay at zero across a full processor drain while the event's floater
 * still collapses.
 *
 * <p>{@code getFloatingBlocks()} (the no-arg, whole-graph form) is the O(N) terrain
 * scan the change removed; the scoped path uses {@code findFloatingInScope(scope)}
 * instead, so it must never touch the spy's counter.
 */
@DisplayName("E2E: impact/blast refresh is scoped, never a whole-graph floating scan")
class ScopedRefreshNoWholeGraphScanE2ETest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        world = server.addSimpleWorld("scoped_refresh_world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Counts whole-graph floating scans; everything else delegates to the real graph behaviour. */
    private static final class CountingGraph extends StructureGraph {
        final AtomicInteger wholeGraphScans = new AtomicInteger();

        @Override
        public java.util.Set<NodePos> getFloatingBlocks() {
            wholeGraphScans.incrementAndGet();
            return super.getFloatingBlocks();
        }
    }

    /** Rebuild the manager's tracked graph for {@code world} as a CountingGraph spy. */
    @SuppressWarnings("unchecked")
    private CountingGraph installCountingGraph(StructureManager manager) throws Exception {
        StructureGraph original = manager.getGraph(world);
        CountingGraph spy = new CountingGraph();
        for (Node node : original.getAllNodes()) {
            if (node.isGrounded()) {
                spy.addGroundBlock(node.pos());
            } else {
                spy.addBlock(node.pos(), node.spec(), false);
            }
        }
        // The graph map moved into the WorldGraphStore collaborator; reach it via
        // the manager's private graphStore field, then that store's worldGraphs map.
        Field storeField = StructureManager.class.getDeclaredField("graphStore");
        storeField.setAccessible(true);
        Object graphStore = storeField.get(manager);
        Field field = graphStore.getClass().getDeclaredField("worldGraphs");
        field.setAccessible(true);
        Map<UUID, StructureGraph> graphs = (Map<UUID, StructureGraph>) field.get(graphStore);
        graphs.put(world.getUID(), spy);
        return spy;
    }

    private void place(int x, int y, int z, Material material) {
        var block = world.getBlockAt(x, y, z);
        block.setType(material);
        plugin.getStructureManager().onBlockPlaced(block);
    }

    @Test
    @DisplayName("Impact: support shot out — the block above falls, but no whole-graph scan runs")
    void impactRefreshIsScoped() throws Exception {
        StructureManager manager = plugin.getStructureManager();

        // BEDROCK ground; a glass support with a glass block riding on top. Shoot the
        // support out and the rider must fall — an event-caused floater.
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.GLASS); // the support we punch through
        place(0, 66, 0, Material.GLASS); // rides on the support → falls when it goes

        CountingGraph spy = installCountingGraph(manager);

        ImpactProcessor processor = plugin.getImpactProcessor();
        NodePos support = new NodePos(0, 65, 0);
        // Straight-down, high energy: punches the support clean through.
        processor.enqueue(
                new QueuedImpact(world, support, world.getBlockAt(0, 65, 0).getLocation(), 0, -1, 0, 50.0, "ARROW"));
        processor.run();

        assertFalse(spy.hasBlock(support), "the shot punched the support through");
        assertFalse(spy.hasBlock(new NodePos(0, 66, 0)), "the rider lost its support and must collapse");
        assertEquals(
                0,
                spy.wholeGraphScans.get(),
                "the impact refresh must be SCOPED — no whole-graph getFloatingBlocks() scan");
    }

    @Test
    @DisplayName("Blast: rider falls into the crater region, but no whole-graph scan runs")
    void blastRefreshIsScoped() throws Exception {
        StructureManager manager = plugin.getStructureManager();

        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.GLASS);
        place(0, 66, 0, Material.GLASS);

        CountingGraph spy = installCountingGraph(manager);

        BlastProcessor processor = plugin.getBlastProcessor();
        processor.enqueue(new QueuedBlast(world, world.getBlockAt(0, 65, 0).getLocation(), 4.0));
        processor.run();

        assertTrue(processor.queueSize() == 0 && !processor.hasActiveBlast(), "the blast drained fully");
        assertFalse(spy.hasBlock(new NodePos(0, 65, 0)), "the blast crater removed the support");
        assertEquals(
                0,
                spy.wholeGraphScans.get(),
                "the blast refresh must be SCOPED — no whole-graph getFloatingBlocks() scan");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  refreshGroundAndCollapseInScope directly: it actually collapses
    //  in-scope floaters, fires the callback, and never scans the whole graph.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Scoped refresh collapses an in-scope floater, severs onward, and reports it")
    void scopedRefreshCollapsesInScopeFloater() {
        StructureManager manager = plugin.getStructureManager();
        StructureGraph graph = manager.getOrCreateGraph(world);

        // A 3-tall tower on ground. Cut the base node out from under it (an
        // adapter-side ground change) so the top two now float WITHIN scope.
        graph.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 3; y++) {
            graph.addBlock(new NodePos(0, y, 0), new MaterialSpec(1.0, 100.0), false);
        }
        Set<NodePos> scope = graph.getDependentSubgraph(new NodePos(0, 1, 0));
        graph.removeBlock(new NodePos(0, 1, 0));

        List<CollapsedNode> reported = new ArrayList<>();
        boolean[] completed = {false};
        SolverCallback callback = new SolverCallback() {
            @Override
            public void onStressUpdated(Map<NodePos, Double> stressMap) {}

            @Override
            public void onCascadeStep(CollapsedNode node, int stepNumber, CollapseReason reason) {
                reported.add(node);
            }

            @Override
            public void onCascadeComplete(List<CollapsedNode> all) {
                completed[0] = true;
            }
        };

        long revisionBefore = manager.revision(world);
        List<NodePos> collapsed = manager.refreshGroundAndCollapseInScope(world, scope, callback);

        Set<NodePos> expected = new HashSet<>(List.of(new NodePos(0, 2, 0), new NodePos(0, 3, 0)));
        assertEquals(expected, new HashSet<>(collapsed), "both floaters above the cut base must collapse");
        assertEquals(expected.size(), reported.size(), "every collapse is reported once via the callback");
        assertTrue(completed[0], "onCascadeComplete fires when something collapsed");
        assertFalse(graph.hasBlock(new NodePos(0, 2, 0)), "floater removed from the graph");
        assertFalse(graph.hasBlock(new NodePos(0, 3, 0)), "floater removed from the graph");
        assertTrue(
                manager.revision(world) > revisionBefore,
                "a scoped collapse bumps the world revision (markDirty) so caches refresh");
    }

    @Test
    @DisplayName("Scoped refresh via the no-arg overload collapses the floater and reports it")
    void scopedRefreshNoArgOverloadCollapses() {
        StructureManager manager = plugin.getStructureManager();
        StructureGraph graph = manager.getOrCreateGraph(world);
        graph.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 2; y++) {
            graph.addBlock(new NodePos(0, y, 0), new MaterialSpec(1.0, 100.0), false);
        }
        Set<NodePos> scope = graph.getDependentSubgraph(new NodePos(0, 1, 0));
        graph.removeBlock(new NodePos(0, 1, 0)); // orphan the top block

        // The no-arg overload (SolverCallback.NONE) must collapse just the same.
        List<NodePos> collapsed = manager.refreshGroundAndCollapseInScope(world, scope);

        assertEquals(List.of(new NodePos(0, 2, 0)), collapsed, "the orphaned top block collapses");
        assertFalse(graph.hasBlock(new NodePos(0, 2, 0)), "and is removed from the graph");
    }

    @Test
    @DisplayName("Scoped refresh severs onward across passes: a chain orphaned step-by-step fully collapses")
    void scopedRefreshSeversOnwardAcrossPasses() {
        StructureManager manager = plugin.getStructureManager();
        StructureGraph graph = manager.getOrCreateGraph(world);

        // A hanging chain: a beam off a tower tip, with a chain dangling from the
        // beam END. The beam tip's only ground path is back through the tower. Scope
        // starts at the tower base only — the severing-onward is what walks the hole
        // outward so the WHOLE hanging structure is proven groundless and collapses.
        graph.addGroundBlock(new NodePos(0, 0, 0));
        graph.addBlock(new NodePos(0, 1, 0), new MaterialSpec(1.0, 100.0), false); // tower base
        graph.addBlock(new NodePos(0, 2, 0), new MaterialSpec(1.0, 100.0), false); // tower top
        graph.addBlock(new NodePos(1, 2, 0), new MaterialSpec(1.0, 100.0), false); // beam
        graph.addBlock(new NodePos(2, 2, 0), new MaterialSpec(1.0, 100.0), false); // beam tip
        graph.addBlock(new NodePos(2, 1, 0), new MaterialSpec(1.0, 100.0), false); // chain hanging from tip

        // Scope: only the tower base's dependents (it carries the whole thing).
        Set<NodePos> scope = graph.getDependentSubgraph(new NodePos(0, 1, 0));
        graph.removeBlock(new NodePos(0, 1, 0)); // cut the tower out from under everything

        List<NodePos> collapsed = manager.refreshGroundAndCollapseInScope(world, scope);

        Set<NodePos> expected = new HashSet<>(
                List.of(new NodePos(0, 2, 0), new NodePos(1, 2, 0), new NodePos(2, 2, 0), new NodePos(2, 1, 0)));
        assertEquals(expected, new HashSet<>(collapsed), "the entire orphaned hanging structure collapses");
        for (NodePos pos : expected) {
            assertFalse(graph.hasBlock(pos), "every orphaned block is removed: " + pos);
        }
    }

    @Test
    @DisplayName("triggerCascadeCheck collapses a block orphaned by a removal, scoped to the region")
    void triggerCascadeCheckCollapsesOrphan() {
        StructureManager manager = plugin.getStructureManager();
        StructureGraph graph = manager.getOrCreateGraph(world);

        // Ground + a 2-tall stack, placed in the world too so the floating-removal
        // loop's world reads / setType path actually runs.
        place(0, 64, 0, Material.BEDROCK); // ground
        place(0, 65, 0, Material.STONE); // support
        place(0, 66, 0, Material.STONE); // rides on the support → orphaned when it goes

        // Simulate the support having just been collapsed away (graph + world), then
        // ask for a scoped cascade check seeded from where it stood. The rider above
        // is now groundless and must be found and cleared — without a whole-graph scan.
        graph.removeBlock(new NodePos(0, 65, 0));
        world.getBlockAt(0, 65, 0).setType(Material.AIR);

        manager.triggerCascadeCheck(world, new NodePos(0, 65, 0));

        assertFalse(graph.hasBlock(new NodePos(0, 66, 0)), "the orphaned rider is dropped from the graph");
        assertEquals(Material.AIR, world.getBlockAt(0, 66, 0).getType(), "and the rider is cleared from the world");
        assertTrue(graph.hasBlock(new NodePos(0, 64, 0)), "the grounded block is left untouched");
    }

    @Test
    @DisplayName("triggerCascadeCheck does nothing when the removal orphans nobody")
    void triggerCascadeCheckNoOpWhenNothingOrphaned() {
        StructureManager manager = plugin.getStructureManager();
        StructureGraph graph = manager.getOrCreateGraph(world);

        // A lone grounded block with a free-standing neighbour that does not depend
        // on the removed cell — removing an isolated air-adjacent position orphans nobody.
        place(0, 64, 0, Material.BEDROCK);
        place(1, 64, 0, Material.BEDROCK);

        manager.triggerCascadeCheck(world, new NodePos(5, 70, 0)); // empty neighbourhood

        assertTrue(graph.hasBlock(new NodePos(0, 64, 0)), "an unrelated block is never touched");
        assertTrue(graph.hasBlock(new NodePos(1, 64, 0)), "nor its neighbour");
    }

    @Test
    @DisplayName("Scoped refresh is a no-op for an empty scope and a stable structure")
    void scopedRefreshNoOps() {
        StructureManager manager = plugin.getStructureManager();
        StructureGraph graph = manager.getOrCreateGraph(world);
        graph.addGroundBlock(new NodePos(0, 0, 0));
        graph.addBlock(new NodePos(0, 1, 0), new MaterialSpec(1.0, 100.0), false);

        // Empty scope → early return, no work.
        assertTrue(
                manager.refreshGroundAndCollapseInScope(world, Set.of()).isEmpty(),
                "an empty scope collapses nothing (the no-arg overload too)");
        // Non-empty scope over a fully-grounded structure → nothing floats.
        assertTrue(
                manager.refreshGroundAndCollapseInScope(world, Set.of(new NodePos(0, 1, 0)))
                        .isEmpty(),
                "a stable structure has no floaters to collapse");
        assertTrue(graph.hasBlock(new NodePos(0, 1, 0)), "the standing block is untouched");
    }
}
