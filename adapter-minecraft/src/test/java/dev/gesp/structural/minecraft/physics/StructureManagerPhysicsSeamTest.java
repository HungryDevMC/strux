package dev.gesp.structural.minecraft.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.metrics.StruxMetrics;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.material.MaterialRegistry;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import dev.gesp.structural.solver.CascadeResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * StructureManager must route EVERY collapse-path engine interaction through its injected
 * {@link PhysicsService} — that routing is the whole point of the seam (SCALING.md §2). A
 * recording spy (delegating to {@link LocalPhysicsService}, so behaviour stays real) pins
 * each call site: break → {@code onBreak}, resume → {@code settle}, placement overload →
 * {@code solveProgressively}, and reinforce / repair / triggerCascadeCheck →
 * {@code solveScoped}. If the manager quietly stops calling the seam on any of these paths
 * (e.g. a mutant removes the {@code solveScoped} after a repair), these tests fail.
 */
@DisplayName("StructureManager routes all collapse-path physics through the PhysicsService seam")
class StructureManagerPhysicsSeamTest {

    /** Delegating spy: real physics via LocalPhysicsService, plus call recording. */
    private static final class RecordingPhysicsService implements PhysicsService {
        private final LocalPhysicsService delegate;
        int onBreakCalls;
        int settleCalls;
        int solveProgressivelyCalls;
        final List<Set<NodePos>> solveScopedRegions = new ArrayList<>();
        Set<NodePos> lastSettleScope;

        RecordingPhysicsService(PhysicsConfig config) {
            this.delegate = new LocalPhysicsService(config, new StruxMetrics());
        }

        @Override
        public CascadeResult onBreak(
                StructureGraph graph, NodePos triggerPos, SolverCallback callback, BooleanSupplier pause) {
            onBreakCalls++;
            return delegate.onBreak(graph, triggerPos, callback, pause);
        }

        @Override
        public CascadeEngine.SettleOutcome settle(
                StructureGraph graph, Set<NodePos> scope, SolverCallback callback, BooleanSupplier pause) {
            settleCalls++;
            lastSettleScope = new HashSet<>(scope);
            return delegate.settle(graph, scope, callback, pause);
        }

        @Override
        public NodePos solveProgressively(StructureGraph graph, Set<NodePos> region) {
            solveProgressivelyCalls++;
            return delegate.solveProgressively(graph, region);
        }

        @Override
        public void solveScoped(StructureGraph graph, Set<NodePos> region) {
            solveScopedRegions.add(new HashSet<>(region));
            delegate.solveScoped(graph, region);
        }
    }

    private ServerMock server;
    private WorldMock world;
    private RecordingPhysicsService physics;
    private StructureManager manager;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("seam-world");
        PhysicsConfig config = new PhysicsConfig();
        physics = new RecordingPhysicsService(config);
        manager = new StructureManager(new MaterialRegistry(), config, physics);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Bedrock anchor at (0,64,0) with a 3-block stone post on top; returns the post blocks. */
    private List<Block> buildPost() {
        world.getBlockAt(0, 64, 0).setType(Material.BEDROCK);
        List<Block> placed = new ArrayList<>();
        for (int y = 65; y <= 67; y++) {
            Block b = world.getBlockAt(0, y, 0);
            b.setType(Material.STONE);
            manager.onBlockPlaced(b);
            placed.add(b);
        }
        return placed;
    }

    @Test
    @DisplayName("onBlockPlaced routes the overload check through solveProgressively")
    void placeRoutesThroughSolveProgressively() {
        buildPost();
        assertTrue(physics.solveProgressivelyCalls >= 3, "each placement must run the progressive overload check");
        // And the post is tracked + stable (real physics via the delegate).
        assertTrue(manager.isTracked(world.getBlockAt(0, 66, 0)));
    }

    @Test
    @DisplayName("onBlockBroken routes through onBreak, and the cascade result is applied to the graph")
    void breakRoutesThroughOnBreak() {
        List<Block> post = buildPost();
        CascadeResult result = manager.onBlockBroken(post.get(0)); // break the base -> orphans the two above
        assertEquals(1, physics.onBreakCalls, "exactly one onBreak per block break");
        assertEquals(2, result.totalCollapsed(), "the two unsupported blocks above must collapse");
        StructureGraph graph = manager.getGraph(world);
        assertFalse(graph.hasBlock(new NodePos(0, 67, 0)), "collapse must be applied to the live graph");
    }

    @Test
    @DisplayName("resumeCascade routes through settle with the given seed scope")
    void resumeRoutesThroughSettle() {
        buildPost();
        Set<NodePos> seed = Set.of(new NodePos(0, 66, 0));
        manager.resumeCascade(world, seed);
        assertEquals(1, physics.settleCalls, "resume must settle exactly once");
        assertEquals(seed, physics.lastSettleScope, "resume must settle the caller's seed scope, not the whole graph");
    }

    @Test
    @DisplayName("reinforce re-solves the dependent subgraph through solveScoped")
    void reinforceRoutesThroughSolveScoped() {
        List<Block> post = buildPost();
        physics.solveScopedRegions.clear();
        StructureManager.Reinforced outcome = manager.reinforce(post.get(0), 0.5, 2.0);
        assertEquals(StructureManager.ReinforceResult.OK, outcome.result());
        assertEquals(1.5, outcome.multiplier(), 1e-9);
        assertEquals(1, physics.solveScopedRegions.size(), "reinforce must re-solve exactly once");
        assertTrue(
                physics.solveScopedRegions.get(0).contains(new NodePos(0, 66, 0)),
                "the re-solve region must include the blocks depending on the reinforced one");
    }

    @Test
    @DisplayName("repair clears damage and re-solves the dependent subgraph through solveScoped")
    void repairRoutesThroughSolveScoped() {
        List<Block> post = buildPost();
        Block base = post.get(0);
        Node node = manager.getGraph(world).getNode(new NodePos(0, 65, 0));
        node.addDamage(0.4);
        physics.solveScopedRegions.clear();
        assertTrue(manager.repair(base), "a damaged tracked block must be repairable");
        assertEquals(0.0, node.damage(), 1e-9, "repair must clear the damage");
        assertEquals(1, physics.solveScopedRegions.size(), "repair must re-solve exactly once");
        assertTrue(
                physics.solveScopedRegions.get(0).contains(new NodePos(0, 66, 0)),
                "the re-solve region must include the blocks depending on the repaired one");
    }

    @Test
    @DisplayName("triggerCascadeCheck re-solves the affected region through solveScoped")
    void triggerCascadeCheckRoutesThroughSolveScoped() {
        buildPost();
        // Simulate a delayed-collapse removal of the middle block, then the follow-up check.
        NodePos removed = new NodePos(0, 66, 0);
        manager.removeBlockDirect(world, removed);
        physics.solveScopedRegions.clear();
        manager.triggerCascadeCheck(world, removed);
        assertEquals(1, physics.solveScopedRegions.size(), "the follow-up check must re-solve the affected region");
        assertTrue(
                physics.solveScopedRegions.get(0).contains(new NodePos(0, 67, 0)),
                "the region must include the neighbour left floating by the removal");
        assertFalse(
                manager.getGraph(world).hasBlock(new NodePos(0, 67, 0)),
                "the orphaned block above must be collapsed by the follow-up check");
    }
}
