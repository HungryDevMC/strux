package dev.gesp.structural.minecraft.listener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.api.CollapseReason;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.StructuralIntegrityPlugin;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.NodePos;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Wiring test for the critical-stress placement warning. The static warning helper was
 * already tested directly, but the place path never delivered onStressUpdated to it — so
 * the warning could never fire in production. This pins that the place path now emits a
 * stress update (when the callback opts in) describing the settled, still-standing region.
 */
@DisplayName("onBlockPlaced delivers a stress update for the settled region")
class BlockPlaceCriticalStressWiringTest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        world = server.addSimpleWorld("crit_stress_world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("a placement reports the loaded, still-standing supporting block's stress")
    void placeEmitsStressUpdate() {
        StructureGraph graph = plugin.getStructureManager().getOrCreateGraph(world);
        graph.addGroundBlock(new NodePos(0, 64, 0));
        world.getBlockAt(0, 64, 0).setType(Material.STONE);

        // Place the lower block on the grounded base.
        world.getBlockAt(0, 65, 0).setType(Material.STONE);
        plugin.getStructureManager().onBlockPlaced(world.getBlockAt(0, 65, 0), SolverCallback.NONE);

        // Place a second block on top, capturing the stress update the place path emits.
        AtomicReference<Map<NodePos, Double>> captured = new AtomicReference<>();
        world.getBlockAt(0, 66, 0).setType(Material.STONE);
        plugin.getStructureManager().onBlockPlaced(world.getBlockAt(0, 66, 0), new CapturingCallback(captured) {
            @Override
            public boolean wantsStressUpdates() {
                return true;
            }
        });

        Map<NodePos, Double> stress = captured.get();
        assertNotNull(stress, "the place path delivered onStressUpdated to a callback that opted in");
        assertFalse(stress.isEmpty(), "the settled region reports at least one standing node's stress");
        assertTrue(
                stress.containsKey(new NodePos(0, 65, 0)),
                "the lower block (now carrying the upper one) is reported: " + stress);
        assertTrue(stress.get(new NodePos(0, 65, 0)) > 0.0, "...with a non-zero stress");
        assertFalse(
                stress.containsKey(new NodePos(0, 64, 0)),
                "the grounded base is excluded (it never overloads): " + stress);
    }

    @Test
    @DisplayName("a callback that does not opt in receives no stress update (NONE default)")
    void noUpdateWhenNotOptedIn() {
        StructureGraph graph = plugin.getStructureManager().getOrCreateGraph(world);
        graph.addGroundBlock(new NodePos(0, 64, 0));
        world.getBlockAt(0, 64, 0).setType(Material.STONE);
        world.getBlockAt(0, 65, 0).setType(Material.STONE);

        AtomicReference<Map<NodePos, Double>> captured = new AtomicReference<>();
        // wantsStressUpdates() defaults to false, so onStressUpdated must not be called.
        plugin.getStructureManager().onBlockPlaced(world.getBlockAt(0, 65, 0), new CapturingCallback(captured));
        org.junit.jupiter.api.Assertions.assertNull(captured.get(), "no update without opting in");
    }

    /** SolverCallback that ignores cascade steps and just captures any stress update. */
    private static class CapturingCallback implements SolverCallback {
        private final AtomicReference<Map<NodePos, Double>> sink;

        CapturingCallback(AtomicReference<Map<NodePos, Double>> sink) {
            this.sink = sink;
        }

        @Override
        public void onStressUpdated(Map<NodePos, Double> stressMap) {
            sink.set(stressMap);
        }

        @Override
        public void onCascadeStep(CollapsedNode collapsed, int stepNumber, CollapseReason reason) {}

        @Override
        public void onCascadeComplete(List<CollapsedNode> collapsedNodes) {}
    }
}
