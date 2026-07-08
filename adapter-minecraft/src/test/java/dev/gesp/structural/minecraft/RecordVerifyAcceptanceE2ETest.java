package dev.gesp.structural.minecraft;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.recording.MinecraftEventRecorder;
import dev.gesp.structural.recording.ImpactEvent;
import dev.gesp.structural.recording.RecordingSession;
import dev.gesp.structural.recording.StruxEvent;
import java.util.ArrayList;
import java.util.UUID;
import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.ArrowMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * The acceptance test for "verify is trustworthy".
 *
 * <pre>
 *   Play a real, mixed session through the LIVE adapter (a terrain break, a
 *   cascading break, a multi-hit arrow sequence with the killing blow, a
 *   PENETRATING impact, and several blasts including a volley on one wall),
 *   stop recording, then run /strux record verify. The whole session must
 *   replay with ZERO divergences and ZERO invariant violations.
 * </pre>
 *
 * <p>This exercises the three verification fixes end-to-end. The blast comparison
 * now checks destroyed + collapsed + damaged and runs the adapter's whole-world
 * floating sweep on the replay graph; the impact event carries penetrated[] +
 * per-block path damage so a penetrating hit reproduces exactly. Before the fixes
 * the blast and impact events read as false divergences here.
 */
@DisplayName("E2E acceptance: a mixed recorded session verifies with zero divergences")
class RecordVerifyAcceptanceE2ETest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private WorldMock world;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        world = server.addSimpleWorld("accept_world");
        player = server.addPlayer("Breaker");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("breaks + multi-hit arrows + penetration + blast volley → verify reports zero divergences")
    void mixedSessionVerifiesClean() throws Exception {
        StructureManager manager = plugin.getStructureManager();
        MinecraftEventRecorder recorder = (MinecraftEventRecorder) manager.getEventRecorder();

        // Build everything BEFORE recording so the initial-state snapshot captures
        // the full graph and every later event is a true mutation of it.
        buildArena();

        // verifyOnStop=false: we run verify ourselves after the async save lands.
        String session = recorder.startRecording(
                "accept/mixed-session", world.getUID().toString(), manager.getGraph(world), false);
        assertTrue(session != null, "recording must start");

        // (1) A plain terrain break: knock a standalone surface block off its foot.
        breakBlock(20, 65, 0);

        // (2) A cascading break: pull the support out from under a stack so the
        //     blocks above lose their path to ground and fall.
        breakBlock(24, 65, 0);

        // (3) A multi-hit arrow sequence on ONE post, ending in the killing blow.
        //     Each arrow cracks the same fragile cap a little; the last punches it out.
        Block cap = world.getBlockAt(28, 67, 0);
        for (int i = 0; i < 4; i++) {
            fireArrow(cap, 6.0, 0.0, 0.0);
            server.getScheduler().performTicks(2);
        }

        // (4) A penetrating high-energy impact: bore horizontally into a glass wall.
        fireArrow(world.getBlockAt(32, 67, -1), 30.0, 0.0, 0.0);
        server.getScheduler().performTicks(3);

        // (5) Two blasts on independent towers, plus a VOLLEY (two blasts) on the
        //     SAME wall — the second blast resettles a wall the first already holed.
        blast(40, 66, 0, 4.0f);
        blast(44, 66, 0, 4.0f);
        blast(50, 66, 0, 4.0f); // volley shot 1 on the long wall
        blast(53, 66, 0, 4.0f); // volley shot 2 on the same wall

        // Drain any straggler queued work, then stop + wait for the async save.
        server.getScheduler().performTicks(5);
        recorder.stopRecording();
        RecordingSession saved = awaitSession(recorder, session);

        // The session must genuinely COVER the spread of features — otherwise a
        // clean verify proves nothing. Assert the recorded mix before verifying.
        int breaks = 0;
        int blasts = 0;
        int penetratingImpacts = 0;
        for (StruxEvent e : saved.getEvents()) {
            switch (e.type()) {
                case BLOCK_BREAK -> breaks++;
                case BLAST -> blasts++;
                case IMPACT -> {
                    if (e instanceof ImpactEvent ie && !ie.penetrated().isEmpty()) {
                        penetratingImpacts++;
                    }
                }
                default -> {}
            }
        }
        assertTrue(breaks >= 1, "the session must record at least one block break, got " + breaks);
        assertTrue(blasts >= 3, "the session must record the blast volley (>=3 blasts), got " + blasts);
        assertTrue(
                penetratingImpacts >= 1,
                "the session must record at least one PENETRATING impact (path fields exercised), got "
                        + penetratingImpacts);

        // Run verify via the SAME path /strux record verify uses.
        String report = recorder.verifySession(session);

        assertTrue(report.contains("VALID"), "verify must declare the session VALID, got:\n" + report);
        assertFalse(report.contains("DIVERGENCE"), "verify must report no divergences, got:\n" + report);
        assertFalse(report.contains("INVARIANT VIOLATION"), "verify must report no violations, got:\n" + report);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  ARENA
    // ─────────────────────────────────────────────────────────────────────

    /** A spread-out arena so no two features' collapses interfere. */
    private void buildArena() {
        // (1) Standalone block on a foot — a clean terrain break target.
        place(20, 64, 0, Material.BEDROCK);
        place(20, 65, 0, Material.STONE);

        // (2) A column whose base we pull out (cascading break).
        place(24, 64, 0, Material.BEDROCK);
        for (int y = 65; y <= 68; y++) {
            place(24, y, 0, Material.STONE);
        }

        // (3) A short post for the multi-hit arrow sequence: a fragile cap repeated
        //     arrows crack then punch through.
        place(28, 64, 0, Material.BEDROCK);
        place(28, 65, 0, Material.STONE);
        place(28, 66, 0, Material.STONE);
        place(28, 67, 0, Material.GLASS);

        // (4) A glass wall slab for the penetrating impact to bore into.
        for (int z = -1; z <= 1; z++) {
            place(32, 64, z, Material.BEDROCK);
            for (int y = 65; y <= 68; y++) {
                place(32, y, z, Material.GLASS);
            }
        }

        // (5a) Two independent blast towers.
        blastTower(40);
        blastTower(44);

        // (5b) A long wall for the volley (two blasts on the same wall).
        for (int x = 49; x <= 54; x++) {
            place(x, 64, 0, Material.BEDROCK);
            for (int y = 65; y <= 67; y++) {
                place(x, y, 0, Material.STONE);
            }
        }
    }

    private void blastTower(int x) {
        place(x, 64, 0, Material.BEDROCK);
        for (int y = 65; y <= 68; y++) {
            place(x, y, 0, Material.STONE);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  ACTIONS
    // ─────────────────────────────────────────────────────────────────────

    private void place(int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(material);
        plugin.getStructureManager().onBlockPlaced(block);
    }

    /** Break a block through the REAL BlockBreakListener (which records the event). */
    private void breakBlock(int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        server.getPluginManager().callEvent(new BlockBreakEvent(block, player));
    }

    private void blast(int x, int y, int z, float power) {
        Location center = new Location(world, x, y, z);
        TNTPrimed tnt = (TNTPrimed) world.spawn(center, TNTPrimed.class);
        EntityExplodeEvent event =
                new EntityExplodeEvent(tnt, center, new ArrayList<>(), power, ExplosionResult.DESTROY);
        server.getPluginManager().callEvent(event);
    }

    private void fireArrow(Block hitBlock, double vx, double vy, double vz) {
        // MockBukkit's isInBlock() throws (Unimplemented); the listener calls it on
        // every hit to skip arrows already at rest, so report a still-flying arrow.
        ArrowMock arrow = new ArrowMock(server, UUID.randomUUID()) {
            @Override
            public boolean isInBlock() {
                return false; // in flight — a genuine fresh hit
            }
        };
        server.registerEntity(arrow);
        arrow.setLocation(hitBlock.getLocation());
        arrow.setVelocity(new Vector(vx, vy, vz));
        Arrow asArrow = arrow;
        server.getPluginManager().callEvent(new ProjectileHitEvent(asArrow, hitBlock));
    }

    private RecordingSession awaitSession(MinecraftEventRecorder recorder, String session) throws Exception {
        for (int i = 0; i < 200; i++) {
            RecordingSession loaded = recorder.loadSession(session);
            if (loaded != null) {
                return loaded;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("recording session was never saved: " + session);
    }
}
