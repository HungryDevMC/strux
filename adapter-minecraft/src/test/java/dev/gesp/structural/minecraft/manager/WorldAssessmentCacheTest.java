package dev.gesp.structural.minecraft.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.gesp.structural.assess.CollapseAtlas;
import dev.gesp.structural.assess.StructureReport;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.StressSolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Focused unit tests for {@link WorldAssessmentCache}: the revision-keyed grade
 * cache and the per-world collapse atlas.
 *
 * <p>The point of the cache is that a world's grade can only change when the world
 * changes, so a poll while the revision is unchanged must NOT re-solve. We prove
 * that with a spy solver that counts {@code solveAll} calls: assessing twice at the
 * same revision solves once; a markDirty between assessments forces exactly one more
 * solve.
 */
@DisplayName("WorldAssessmentCache: revision-keyed grade + atlas caches")
class WorldAssessmentCacheTest {

    /** A solver that counts how many times {@code solveAll} runs. */
    private static final class CountingSolver extends StressSolver {
        int solveAllCalls = 0;

        CountingSolver(PhysicsConfig config) {
            super(config);
        }

        @Override
        public void solveAll(StructureGraph graph) {
            solveAllCalls++;
            super.solveAll(graph);
        }
    }

    private ServerMock server;
    private WorldMock world;
    private WorldGraphStore store;
    private CountingSolver solver;
    private WorldAssessmentCache cache;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("assess-world");
        store = new WorldGraphStore();
        solver = new CountingSolver(new PhysicsConfig());
        // Use 0ms rate limit so tests can verify revision-based caching without waiting
        cache = new WorldAssessmentCache(store, solver, 0);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** A small grounded tower so the graph is non-empty and assessable. */
    private void buildTower() {
        StructureGraph graph = store.getOrCreateGraph(world);
        graph.addGroundBlock(new NodePos(0, world.getMinHeight(), 0));
        graph.addBlock(new NodePos(0, world.getMinHeight() + 1, 0), new MaterialSpec(2.0, 30.0), false);
        store.markDirty(world);
    }

    @Test
    @DisplayName("an empty world returns the empty report without solving")
    void emptyWorldNoSolve() {
        StructureReport report = cache.assessWorld(world);
        assertEquals(StructureReport.empty(), report, "no tracked blocks must give the empty report");
        assertEquals(0, solver.solveAllCalls, "an empty world must not be solved");
    }

    @Test
    @DisplayName("two assessments at the same revision solve exactly once (cache reuse)")
    void cacheReuseWhileRevisionUnchanged() {
        buildTower();

        StructureReport first = cache.assessWorld(world);
        StructureReport second = cache.assessWorld(world);

        assertNotNull(first);
        assertSame(first, second, "the same revision must return the cached report instance");
        assertEquals(1, solver.solveAllCalls, "the second poll at the same revision must not re-solve");
    }

    @Test
    @DisplayName("a markDirty between assessments forces exactly one rebuild")
    void rebuildOnRevisionBump() {
        buildTower();

        StructureReport first = cache.assessWorld(world);
        assertEquals(1, solver.solveAllCalls, "first assessment solves once");

        store.markDirty(world); // revision bump invalidates the cached report

        StructureReport afterBump = cache.assessWorld(world);
        assertEquals(2, solver.solveAllCalls, "a revision bump must force exactly one re-solve");
        assertNotSame(first, afterBump, "after a bump the cache returns a freshly built report");

        // A second poll at the now-current revision reuses again (no third solve).
        cache.assessWorld(world);
        assertEquals(2, solver.solveAllCalls, "polling again at the new revision must reuse the cache");
    }

    @Test
    @DisplayName("atlasFor is null for an untracked world and stable for the same graph")
    void atlasReuseAndNull() {
        assertNull(cache.atlasFor(world), "an untracked world has no atlas");

        buildTower();
        CollapseAtlas atlas = cache.atlasFor(world);
        assertNotNull(atlas, "a tracked world has an atlas");
        assertSame(atlas, cache.atlasFor(world), "the atlas must be reused while the graph instance is the same");
    }

    @Test
    @DisplayName("atlasFor rebuilds when the world's graph instance is swapped")
    void atlasRebuildsOnGraphSwap() {
        buildTower();
        CollapseAtlas first = cache.atlasFor(world);

        // Simulate a reload: swap in a brand-new graph instance for the world.
        StructureGraph replacement = new StructureGraph();
        replacement.addGroundBlock(new NodePos(0, world.getMinHeight(), 0));
        store.put(world.getUID(), replacement);

        CollapseAtlas second = cache.atlasFor(world);
        assertNotSame(first, second, "a swapped graph instance must rebuild the atlas");
        assertSame(replacement, second.graph(), "the rebuilt atlas must bind to the new graph");
    }
}
