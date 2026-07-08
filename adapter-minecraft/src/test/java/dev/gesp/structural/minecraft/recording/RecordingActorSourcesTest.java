package dev.gesp.structural.minecraft.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.minecraft.StructuralIntegrityPlugin;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.recording.BlastEvent;
import dev.gesp.structural.recording.RecordingSession;
import dev.gesp.structural.recording.StruxEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.logging.Logger;
import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Covers the remaining blast-actor resolution branches the igniter test does not:
 * a TNT whose priming source is gone (falls back to the TNT entity), a generic
 * exploding entity that is its own actor (a creeper), and the back-compat recorder
 * constructor without an engine version.
 */
@DisplayName("E2E: blast actor resolution across entity sources")
class RecordingActorSourcesTest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        world = server.addSimpleWorld("actor_world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("a TNT with no priming source is attributed to the TNT entity itself")
    void tntWithoutSourceFallsBackToTheTntEntity() throws Exception {
        TNTPrimed tnt = (TNTPrimed) world.spawn(new Location(world, 0, 66, 0), TNTPrimed.class);
        // No setSource: getSource() is null, so the blast attributes to the TNT.
        BlastEvent blast = explodeAndRecord(tnt, new Location(world, 0, 66, 0));
        assertEquals(tnt.getUniqueId().toString(), blast.actorId(), "no igniter known → the TNT is its own actor");
    }

    @Test
    @DisplayName("a generic exploding entity (a creeper) is its own actor")
    void creeperIsItsOwnActor() throws Exception {
        Creeper creeper = (Creeper) world.spawn(new Location(world, 0, 66, 0), Creeper.class);
        BlastEvent blast = explodeAndRecord(creeper, new Location(world, 0, 66, 0));
        assertEquals(creeper.getUniqueId().toString(), blast.actorId(), "a creeper detonates itself");
    }

    @Test
    @DisplayName("a fireball blast is attributed to its shooter")
    void fireballAttributedToShooter() throws Exception {
        var shooter = server.addPlayer("Ghast");
        org.bukkit.entity.LargeFireball fireball = (org.bukkit.entity.LargeFireball)
                world.spawn(new Location(world, 0, 66, 0), org.bukkit.entity.LargeFireball.class);
        fireball.setShooter(shooter);
        BlastEvent blast = explodeAndRecord(fireball, new Location(world, 0, 66, 0));
        assertEquals(shooter.getUniqueId().toString(), blast.actorId(), "the fireball's shooter is the actor");
    }

    @Test
    @DisplayName("the back-compat recorder constructor (no engine version) builds and is idle")
    void backCompatRecorderConstructor() {
        MinecraftEventRecorder recorder = new MinecraftEventRecorder(
                Path.of(System.getProperty("java.io.tmpdir")),
                new RecordingConfig(),
                new PhysicsConfig(),
                Logger.getLogger("test"));
        assertFalse(recorder.isRecording(), "a fresh recorder is not recording");
        recorder.close();
    }

    /** Set up a small tracked structure, record one explosion of {@code source}, return its BlastEvent. */
    private BlastEvent explodeAndRecord(org.bukkit.entity.Entity source, Location center) throws Exception {
        StructureManager manager = plugin.getStructureManager();
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.STONE);
        place(0, 66, 0, Material.STONE);

        MinecraftEventRecorder recorder = recorder();
        String session =
                recorder.startRecording("test/actor", world.getUID().toString(), manager.getGraph(world), false);

        var explode = new EntityExplodeEvent(source, center, new ArrayList<>(), 4.0f, ExplosionResult.DESTROY);
        server.getPluginManager().callEvent(explode);
        server.getScheduler().performTicks(3);

        recorder.stopRecording();
        RecordingSession saved = awaitSession(recorder, session);
        BlastEvent blast = firstOf(saved, BlastEvent.class);
        assertNotNull(blast, "the blast was recorded once it settled");
        return blast;
    }

    private MinecraftEventRecorder recorder() {
        return (MinecraftEventRecorder) plugin.getStructureManager().getEventRecorder();
    }

    private void place(int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(material);
        plugin.getStructureManager().onBlockPlaced(block);
    }

    private static BlastEvent firstOf(RecordingSession saved, Class<BlastEvent> type) {
        for (StruxEvent e : saved.getEvents()) {
            if (type.isInstance(e)) {
                return type.cast(e);
            }
        }
        return null;
    }

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

    @Test
    @DisplayName("a block-driven explosion (bed/anchor) records an unattributed blast")
    void blockExplosionIsUnattributed() throws Exception {
        StructureManager manager = plugin.getStructureManager();
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.STONE);
        place(0, 66, 0, Material.STONE);

        MinecraftEventRecorder recorder = recorder();
        String session =
                recorder.startRecording("test/blockboom", world.getUID().toString(), manager.getGraph(world), false);

        Block source = world.getBlockAt(0, 66, 0);
        var explode = new org.bukkit.event.block.BlockExplodeEvent(
                source, source.getState(), new ArrayList<>(), 4.0f, ExplosionResult.DESTROY);
        server.getPluginManager().callEvent(explode);
        server.getScheduler().performTicks(3);

        recorder.stopRecording();
        RecordingSession saved = awaitSession(recorder, session);
        BlastEvent blast = firstOf(saved, BlastEvent.class);
        assertNotNull(blast, "the block explosion was recorded");
        assertNull(blast.actorId(), "a bed/anchor blast has no entity source — unattributed");
    }
}
