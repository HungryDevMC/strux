package dev.gesp.structural.minecraft.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.minecraft.StructuralIntegrityPlugin;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.persistence.BlockData;
import dev.gesp.structural.recording.BlastEvent;
import dev.gesp.structural.recording.BlockBreakEvent;
import dev.gesp.structural.recording.BlockPlaceEvent;
import dev.gesp.structural.recording.ImpactEvent;
import dev.gesp.structural.recording.RecordingSession;
import dev.gesp.structural.recording.StruxEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.TNTPrimed;
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
 * Schema-v2 capture: the live Minecraft path now fills the new recording fields —
 * per-event {@code actorId} (who did it), per-block {@code materialId} on the initial
 * snapshot (the real block-state string), and the session's {@code engineVersion} +
 * {@code physicsConfig}. These assert each is populated end-to-end through the real
 * listener/processor wiring, not just that the records can hold the values.
 */
@DisplayName("E2E: recordings capture actorId, block-states and physics context")
class RecordingCaptureFieldsE2ETest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        world = server.addSimpleWorld("capture_world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SESSION CONTEXT: engineVersion + physicsConfig stamped at start
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A started session carries the plugin's engineVersion and the active physicsConfig")
    void sessionCarriesEngineVersionAndPhysicsConfig() throws Exception {
        StructureManager manager = plugin.getStructureManager();
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.STONE);

        MinecraftEventRecorder recorder = recorder();
        String session =
                recorder.startRecording("test/context", world.getUID().toString(), manager.getGraph(world), false);
        // One trivial event so the session is non-empty and definitely saved.
        manager.onBlockBroken(world.getBlockAt(0, 65, 0));
        recorder.stopRecording();

        RecordingSession saved = awaitSession(recorder, session);
        assertEquals(
                RecordingSession.SCHEMA_VERSION,
                saved.getSchemaVersion(),
                "the session is written at the current schema version");
        assertEquals(
                plugin.getDescription().getVersion(),
                saved.getEngineVersion(),
                "the session is stamped with the plugin version");
        assertNotNull(saved.getPhysicsConfig(), "the active physics config is recorded for deterministic replay");
        assertTrue(
                saved.getPhysicsConfig().getMaxCascadeSteps() > 0,
                "the recorded physics config carries the engine's real settings (a positive cascade-step cap)");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SNAPSHOT: initial-state BlockData carry the live block-state string
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Every block in the initial snapshot carries a non-null materialId (the block-state string)")
    void initialSnapshotBlocksCarryMaterialId() throws Exception {
        StructureManager manager = plugin.getStructureManager();
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.OAK_PLANKS);

        MinecraftEventRecorder recorder = recorder();
        String session =
                recorder.startRecording("test/snapshot", world.getUID().toString(), manager.getGraph(world), false);
        manager.onBlockBroken(world.getBlockAt(0, 65, 0));
        recorder.stopRecording();

        RecordingSession saved = awaitSession(recorder, session);
        List<BlockData> blocks = saved.getInitialState().getBlocks();
        assertFalse(blocks.isEmpty(), "the snapshot has blocks");
        for (BlockData b : blocks) {
            assertNotNull(b.materialId(), "every snapshot block carries a block-state materialId");
        }
        // The planks block keeps a recognisable minecraft block-state string.
        BlockData planks = blocks.stream().filter(b -> b.y() == 65).findFirst().orElseThrow();
        assertTrue(
                planks.materialId().contains("oak_planks"),
                "the planks block-state string is captured, got: " + planks.materialId());
    }

    // ─────────────────────────────────────────────────────────────────────
    //  BLOCK BREAK: the breaking player's UUID is the actorId
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A recorded block break carries the breaking player's UUID as actorId")
    void breakCarriesPlayerUuid() throws Exception {
        StructureManager manager = plugin.getStructureManager();
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.STONE);

        MinecraftEventRecorder recorder = recorder();
        String session =
                recorder.startRecording("test/break", world.getUID().toString(), manager.getGraph(world), false);

        PlayerMock breaker = server.addPlayer("Breaker");
        server.getPluginManager()
                .callEvent(new org.bukkit.event.block.BlockBreakEvent(world.getBlockAt(0, 65, 0), breaker));
        recorder.stopRecording();

        RecordingSession saved = awaitSession(recorder, session);
        BlockBreakEvent breakEvent = firstOf(saved, BlockBreakEvent.class);
        assertNotNull(breakEvent, "the break was recorded");
        assertEquals(
                breaker.getUniqueId().toString(),
                breakEvent.actorId(),
                "the recorded break is attributed to the player who broke it");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  BLOCK PLACE: the placing player's UUID + the block-state materialId
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A recorded block place carries the placing player's UUID and the block-state materialId")
    void placeCarriesPlayerUuidAndBlockState() throws Exception {
        StructureManager manager = plugin.getStructureManager();
        place(0, 64, 0, Material.BEDROCK);

        MinecraftEventRecorder recorder = recorder();
        String session =
                recorder.startRecording("test/place", world.getUID().toString(), manager.getGraph(world), false);

        PlayerMock placer = server.addPlayer("Placer");
        Block placed = world.getBlockAt(0, 65, 0);
        placed.setType(Material.OAK_PLANKS);
        Block against = world.getBlockAt(0, 64, 0);
        var event = new org.bukkit.event.block.BlockPlaceEvent(
                placed, placed.getState(), against, placer.getInventory().getItemInMainHand(), placer, true);
        server.getPluginManager().callEvent(event);
        recorder.stopRecording();

        RecordingSession saved = awaitSession(recorder, session);
        BlockPlaceEvent placeEvent = firstOf(saved, BlockPlaceEvent.class);
        assertNotNull(placeEvent, "the place was recorded");
        assertEquals(
                placer.getUniqueId().toString(),
                placeEvent.actorId(),
                "the recorded place is attributed to the player who placed it");
        assertTrue(
                placeEvent.materialId().contains("oak_planks"),
                "the place carries the block-state string, not just the material name, got: "
                        + placeEvent.materialId());

        // The full material spec is recorded from the registry (not defaulted), and an
        // ordinary block on bedrock is NOT a ground anchor.
        MaterialSpec expected = manager.getMaterialRegistry().getSpec(Material.OAK_PLANKS);
        assertFalse(placeEvent.grounded(), "oak planks resting on bedrock are not a ground anchor");
        assertEquals(expected.blastResistance(), placeEvent.blastResistance(), 1e-9, "recorded blast resistance");
        assertEquals(expected.fireResistance(), placeEvent.fireResistance(), 1e-9, "recorded fire resistance");
        assertEquals(expected.thermalClass(), placeEvent.thermalClass(), "recorded thermal class");
    }

    @Test
    @DisplayName("A recorded place of a ground material carries grounded=true")
    void placeOfGroundMaterialIsGrounded() throws Exception {
        StructureManager manager = plugin.getStructureManager();
        place(0, 64, 0, Material.STONE); // terrain to place the anchor against

        MinecraftEventRecorder recorder = recorder();
        String session = recorder.startRecording(
                "test/place-grounded", world.getUID().toString(), manager.getGraph(world), false);

        PlayerMock placer = server.addPlayer("Anchorer");
        Block placed = world.getBlockAt(0, 65, 0);
        placed.setType(Material.BEDROCK); // a ground material → anchored
        Block against = world.getBlockAt(0, 64, 0);
        var event = new org.bukkit.event.block.BlockPlaceEvent(
                placed, placed.getState(), against, placer.getInventory().getItemInMainHand(), placer, true);
        server.getPluginManager().callEvent(event);
        recorder.stopRecording();

        RecordingSession saved = awaitSession(recorder, session);
        BlockPlaceEvent placeEvent = firstOf(saved, BlockPlaceEvent.class);
        assertNotNull(placeEvent, "the place was recorded");
        assertTrue(placeEvent.grounded(), "a ground material records as a grounded anchor");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  BLAST: the TNT igniter's UUID is the actorId
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A recorded blast carries the TNT igniter's UUID as actorId")
    void blastCarriesIgniterUuid() throws Exception {
        StructureManager manager = plugin.getStructureManager();
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.STONE);
        place(0, 66, 0, Material.STONE);

        MinecraftEventRecorder recorder = recorder();
        String session =
                recorder.startRecording("test/blast", world.getUID().toString(), manager.getGraph(world), false);

        // A player primes the TNT: the blast must be attributed to that player, not
        // the anonymous TNT entity (TNTPrimed.getSource()).
        PlayerMock igniter = server.addPlayer("Igniter");
        Location center = new Location(world, 0, 66, 0);
        TNTPrimed tnt = (TNTPrimed) world.spawn(center, TNTPrimed.class);
        tnt.setSource(igniter);
        var explode = new org.bukkit.event.entity.EntityExplodeEvent(
                tnt, center, new ArrayList<>(), 4.0f, org.bukkit.ExplosionResult.DESTROY);
        server.getPluginManager().callEvent(explode);
        server.getScheduler().performTicks(3); // settle + record the blast

        recorder.stopRecording();
        RecordingSession saved = awaitSession(recorder, session);
        BlastEvent blast = firstOf(saved, BlastEvent.class);
        assertNotNull(blast, "the blast was recorded once it settled");
        assertEquals(
                igniter.getUniqueId().toString(),
                blast.actorId(),
                "the recorded blast is attributed to whoever primed the TNT");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  IMPACT: the projectile shooter's UUID is the actorId
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A recorded impact carries the projectile shooter's UUID as actorId")
    void impactCarriesShooterUuid() throws Exception {
        StructureManager manager = plugin.getStructureManager();
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.GLASS);

        MinecraftEventRecorder recorder = recorder();
        String session =
                recorder.startRecording("test/impact", world.getUID().toString(), manager.getGraph(world), false);

        PlayerMock archer = server.addPlayer("Archer");
        Block support = world.getBlockAt(0, 65, 0);
        ArrowMock arrow = new ArrowMock(server, UUID.randomUUID()) {
            @Override
            public boolean isInBlock() {
                return false;
            }
        };
        server.registerEntity(arrow);
        arrow.setVelocity(new Vector(12.0, 0.0, 0.0));
        arrow.setShooter(archer);
        arrow.setLocation(support.getLocation());
        Arrow asArrow = arrow;
        server.getPluginManager().callEvent(new ProjectileHitEvent(asArrow, support));
        server.getScheduler().performTicks(5); // settle + record the impact

        recorder.stopRecording();
        RecordingSession saved = awaitSession(recorder, session);
        ImpactEvent impact = firstOf(saved, ImpactEvent.class);
        assertNotNull(impact, "the impact was recorded once it settled");
        assertEquals(
                archer.getUniqueId().toString(), impact.actorId(), "the recorded impact is attributed to the shooter");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private MinecraftEventRecorder recorder() {
        return (MinecraftEventRecorder) plugin.getStructureManager().getEventRecorder();
    }

    private void place(int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(material);
        plugin.getStructureManager().onBlockPlaced(block);
    }

    private static <T extends StruxEvent> T firstOf(RecordingSession saved, Class<T> type) {
        for (StruxEvent e : saved.getEvents()) {
            if (type.isInstance(e)) {
                return type.cast(e);
            }
        }
        return null;
    }

    /** Wait (briefly) for the async session save to land on disk, then load it. */
    private RecordingSession awaitSession(MinecraftEventRecorder recorder, String session) throws Exception {
        for (int i = 0; i < 100; i++) {
            RecordingSession loaded = recorder.loadSession(session);
            if (loaded != null) {
                return loaded;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("recording session was never saved: " + session);
    }
}
