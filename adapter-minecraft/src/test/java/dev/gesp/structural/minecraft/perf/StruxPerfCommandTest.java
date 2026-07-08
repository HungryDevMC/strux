package dev.gesp.structural.minecraft.perf;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.minecraft.StructuralIntegrityPlugin;
import dev.gesp.structural.persistence.BlockData;
import dev.gesp.structural.persistence.PersistenceAdapter;
import dev.gesp.structural.persistence.StructureData;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * After the repeating tasks have ticked, {@code /strux perf} must print a
 * per-task table — one line per task that ran — below the existing solver
 * section.
 */
@DisplayName("/strux perf prints the per-task timing table once the tasks have run")
class StruxPerfCommandTest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private WorldMock world;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        world = server.addSimpleWorld("perf_cmd_world");
        player = server.addPlayer("Watcher");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** A small grounded grid loaded through the persistence path. */
    private void loadGrid() {
        StructureData data = new StructureData(world.getUID().toString());
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                data.addBlock(new BlockData(x, 64, z, 0.0, Double.MAX_VALUE, true)); // grounded base
                data.addBlock(new BlockData(x, 65, z, 2.0, 30.0, false));
            }
        }
        plugin.getStructureManager().setPersistenceAdapter(adapterReturning(data));
        assertTrue(plugin.getStructureManager().loadWorld(world), "synthetic world must load");
    }

    private static PersistenceAdapter adapterReturning(StructureData data) {
        return new PersistenceAdapter() {
            @Override
            public String getName() {
                return "perf-cmd-test-adapter";
            }

            @Override
            public CompletableFuture<Void> saveAsync(String worldId, StructureData d) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Optional<StructureData>> loadAsync(String worldId) {
                return CompletableFuture.completedFuture(Optional.of(data));
            }

            @Override
            public CompletableFuture<Void> deleteAsync(String worldId) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Boolean> existsAsync(String worldId) {
                return CompletableFuture.completedFuture(true);
            }
        };
    }

    @Test
    @DisplayName("perf output contains the per-task header and lines for the periodic tasks that ran")
    void perfPrintsPerTaskTable() {
        loadGrid();

        // Run past every periodic task's period (visual 10/20, entity 10, fire 20,
        // weather 40). The visualizers + entity/fire/weather scans tick on their
        // own timers; draining a few seconds of ticks guarantees each ran.
        server.getScheduler().performTicks(120);

        // Drain any messages the ticking produced so we read a clean perf block.
        while (player.nextMessage() != null) {
            // discard
        }

        assertTrue(server.dispatchCommand(player, "strux perf"), "/strux perf must succeed");

        List<String> lines = new ArrayList<>();
        String msg;
        while ((msg = player.nextMessage()) != null) {
            lines.add(msg);
        }
        String all = String.join("\n", lines);

        assertTrue(all.contains("Strux performance"), "the existing solver section header must still print");
        assertTrue(all.contains("Per-task"), "a per-task section header must print after the solver section: " + all);

        // The periodic tasks always record a pass (work may be zero), so their
        // canonical names must each appear as a line.
        assertTrue(all.contains(TaskTimings.DAMAGE_VISUALIZER), "damage-visualizer line must appear");
        assertTrue(all.contains(TaskTimings.STRESS_VISUALIZER), "stress-visualizer line must appear");
        assertTrue(all.contains(TaskTimings.ENTITY_WEIGHT), "entity-weight line must appear");
        assertTrue(all.contains(TaskTimings.FIRE_SCORCH), "fire-scorch line must appear");
        assertTrue(all.contains(TaskTimings.WEATHER_LOAD), "weather-load line must appear");

        // Queue tasks that never had work this run must NOT print (idle ticks are
        // skipped, so they have no samples).
        assertTrue(
                !all.contains(TaskTimings.BLAST_QUEUE),
                "blast-queue had nothing to settle, so it must be omitted: " + all);
        assertTrue(
                !all.contains(TaskTimings.IMPACT_QUEUE),
                "impact-queue had nothing to settle, so it must be omitted: " + all);
    }

    @Test
    @DisplayName("before any task ticks, the per-task section is absent (nothing recorded yet)")
    void perfOmitsTableBeforeAnyPass() {
        loadGrid();
        // No performTicks: the registry is empty.
        while (player.nextMessage() != null) {
            // discard
        }
        assertTrue(server.dispatchCommand(player, "strux perf"), "/strux perf must succeed");

        String all = drain(player);
        assertNotNull(all);
        assertTrue(all.contains("Strux performance"), "solver section still prints");
        assertTrue(!all.contains("Per-task"), "with no recorded passes there is no per-task section: " + all);
    }

    private static String drain(PlayerMock player) {
        StringBuilder sb = new StringBuilder();
        String msg;
        while ((msg = player.nextMessage()) != null) {
            sb.append(msg).append('\n');
        }
        return sb.toString();
    }
}
