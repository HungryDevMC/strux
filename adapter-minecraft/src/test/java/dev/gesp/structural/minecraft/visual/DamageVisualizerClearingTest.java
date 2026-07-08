package dev.gesp.structural.minecraft.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.config.EffectsConfig;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.material.MaterialRegistry;
import dev.gesp.structural.minecraft.perf.TaskTimings;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Tests for the crack-overlay CLEARING behaviour of {@link DamageVisualizer}
 * (feature stress-visual-clearing).
 *
 * <p>Playtest 2026-06-05: when a player rescued a stressed block (e.g. placed a
 * support pillar) the crack overlay lingered ~2s client-side because the
 * visualizer never sent a {@code progress = 0} packet to wipe it. These tests pin
 * the fix: a previously-overlaid block that stops being cracked gets exactly one
 * clear packet, once.
 *
 * <p>MockBukkit's {@code PlayerMock.sendBlockDamage} is a no-op, so we observe the
 * packets through the package-private {@code sendDamage} seam by subclassing the
 * visualizer with a recording sink.
 */
@DisplayName("DamageVisualizer: crack-overlay clearing")
class DamageVisualizerClearingTest {

    private ServerMock server;
    private WorldMock world;
    private MaterialRegistry materialRegistry;
    private StructureManager manager;
    private EffectsConfig effects;
    private PhysicsConfig physics;

    /** One recorded sendDamage call. */
    private record Packet(String playerName, int x, int y, int z, float progress, int sourceId) {}

    /** A visualizer that records every packet instead of dropping it into MockBukkit. */
    private static final class RecordingVisualizer extends DamageVisualizer {
        final List<Packet> packets = new ArrayList<>();

        RecordingVisualizer(
                StructureManager m, MaterialRegistry mr, Plugin p, EffectsConfig e, PhysicsConfig pc, TaskTimings t) {
            super(m, mr, p, e, pc, t);
        }

        @Override
        void sendDamage(Player player, Location loc, float progress, int sourceId) {
            packets.add(new Packet(
                    player.getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), progress, sourceId));
        }
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("test_world");
        physics = new PhysicsConfig();
        materialRegistry = new MaterialRegistry();
        manager = new StructureManager(materialRegistry, physics);
        effects = new EffectsConfig();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Add a non-grounded tracked node and return it. */
    private Node addNode(int x, int y, int z) {
        StructureGraph graph = manager.getOrCreateGraph(world);
        graph.addNode(new NodePos(x, y, z), new MaterialSpec(3.0, 100.0), false);
        return graph.getNode(new NodePos(x, y, z));
    }

    /** A player standing on top of the given block (well within view distance). */
    private PlayerMock playerAt(int x, int y, int z) {
        PlayerMock p = server.addPlayer("Viewer");
        p.teleport(new Location(world, x + 0.5, y, z + 0.5));
        return p;
    }

    private int sourceIdFor(int x, int y, int z) {
        int hash = x * 73856093 ^ y * 19349663 ^ z * 83492791;
        return Integer.MIN_VALUE + (hash & 0x7FFFFFFF);
    }

    private List<Packet> packetsFor(RecordingVisualizer v, int x, int y, int z) {
        return v.packets.stream()
                .filter(pk -> pk.x() == x && pk.y() == y && pk.z() == z)
                .toList();
    }

    // ── criterion 1: clear packet sent when progress drops to <= 0 ────────────

    @Test
    @DisplayName("AC1: a tracked block that becomes uncracked gets exactly one progress=0 clear packet to all viewers")
    void clearPacketSentWhenProgressDropsToZero() {
        Node tip = addNode(0, 10, 0);
        playerAt(0, 10, 0);
        RecordingVisualizer v = makeVisualizer();

        // Pass 1: cracked (damage drives progress > 0).
        tip.addDamage(0.5);
        v.run();
        List<Packet> first = packetsFor(v, 0, 10, 0);
        assertEquals(1, first.size(), "one crack packet on the cracked pass");
        assertTrue(first.get(0).progress() > 0.0f, "crack packet has progress > 0");

        // Pass 2: rescued → no longer cracked.
        v.packets.clear();
        tip.repair();
        v.run();
        List<Packet> clear = packetsFor(v, 0, 10, 0);
        assertEquals(1, clear.size(), "exactly one clear packet on the rescue pass");
        assertEquals(0.0f, clear.get(0).progress(), "clear packet carries progress = 0.0f");
        assertEquals(sourceIdFor(0, 10, 0), clear.get(0).sourceId(), "clear targets the same overlay entity id");
        assertEquals("Viewer", clear.get(0).playerName(), "clear goes to the viewer in range");
    }

    // ── criterion 2: clear sent once, no per-tick spam afterwards ─────────────

    @Test
    @DisplayName("AC2: a block that stays uncracked gets no further packets after the single clear")
    void noSpamAfterClear() {
        Node tip = addNode(0, 10, 0);
        playerAt(0, 10, 0);
        RecordingVisualizer v = makeVisualizer();

        tip.addDamage(0.5);
        v.run(); // cracked
        tip.repair();

        v.packets.clear();
        v.run(); // clears once
        assertEquals(1, packetsFor(v, 0, 10, 0).size(), "the rescue pass sends exactly one clear packet");
        assertEquals(0.0f, packetsFor(v, 0, 10, 0).get(0).progress(), "and it is a clear (progress 0)");

        v.packets.clear();
        v.run(); // stays uncracked
        v.run();
        v.run();

        assertTrue(
                packetsFor(v, 0, 10, 0).isEmpty(),
                "no further packets for a block that is uncracked and already cleared");
    }

    // ── criterion 3: node removed from graph is dropped without leaking ────────

    @Test
    @DisplayName("AC3: a previously-overlaid node removed from the graph is dropped from tracking (set never leaks)")
    void removedNodeDroppedWithoutLeaking() {
        Node tip = addNode(0, 10, 0);
        playerAt(0, 10, 0);
        RecordingVisualizer v = makeVisualizer();

        tip.addDamage(0.5);
        v.run(); // cracked → tracked
        assertEquals(1, v.trackedOverlayCount(world), "one overlay tracked after the crack pass");

        // The block collapses / is broken: gone from the graph.
        manager.getGraph(world).removeBlock(new NodePos(0, 10, 0));
        v.packets.clear();
        v.run();

        assertEquals(0, v.trackedOverlayCount(world), "a removed node must be dropped from the overlay set");
        // Re-running must not re-emit anything for the vanished block.
        v.packets.clear();
        v.run();
        assertTrue(packetsFor(v, 0, 10, 0).isEmpty(), "no packets for a block no longer in the graph");
    }

    // ── criterion 4: still-cracked blocks behave exactly as before ────────────

    @Test
    @DisplayName("AC4: a block still cracked is refreshed every pass to every viewer in range")
    void stillCrackedRefreshedEveryPass() {
        Node tip = addNode(0, 10, 0);
        playerAt(0, 10, 0);
        RecordingVisualizer v = makeVisualizer();

        tip.addDamage(0.5);
        v.run();
        v.run();
        v.run();

        List<Packet> packets = packetsFor(v, 0, 10, 0);
        assertEquals(3, packets.size(), "still-cracked block refreshed once per pass");
        for (Packet p : packets) {
            assertTrue(p.progress() > 0.0f, "every refresh carries the live crack progress, not a clear");
        }
    }

    // ── criterion 5: disabling cracks clears every overlay and empties the set ─

    @Test
    @DisplayName("AC5: disabling cracks mid-run clears all overlaid blocks and empties the tracking set")
    void disablingCracksClearsAllOverlays() {
        Node a = addNode(0, 10, 0);
        Node b = addNode(2, 10, 0);
        playerAt(1, 10, 0); // within 32 of both
        RecordingVisualizer v = makeVisualizer();

        a.addDamage(0.5);
        b.addDamage(0.5);
        v.run(); // both cracked → tracked
        assertEquals(2, v.trackedOverlayCount(world), "two overlays tracked");

        v.packets.clear();
        effects.setCracksEnabled(false);
        v.run();

        // Each overlaid block gets a single clear packet, set emptied.
        assertEquals(1, packetsFor(v, 0, 10, 0).size(), "one clear for block A");
        assertEquals(0.0f, packetsFor(v, 0, 10, 0).get(0).progress());
        assertEquals(1, packetsFor(v, 2, 10, 0).size(), "one clear for block B");
        assertEquals(0.0f, packetsFor(v, 2, 10, 0).get(0).progress());
        assertEquals(0, v.trackedOverlayCount(world), "tracking set emptied after disable-clear");

        // And no further packets once disabled.
        v.packets.clear();
        v.run();
        assertTrue(v.packets.isEmpty(), "disabled visualizer sends nothing further");
    }

    // ── E2E: cantilever cracked → support placed → clear once → silence ───────

    @Test
    @DisplayName("E2E: rescued tip gets a crack, then exactly one clear packet, then silence")
    void e2eRescueClearsCrackOnce() {
        // A horizontal cantilever whose tip is distressed enough to crack. We
        // assert on a real tracked node in a real world with a real player; the
        // tip's distress is driven so the scenario is deterministic (a support
        // pillar that re-solves to stable maps to the tip's distress dropping to 0,
        // which we model with repair()).
        Node tip = addNode(8, 64, 0);
        PlayerMock viewer = playerAt(8, 64, 0);
        RecordingVisualizer v = makeVisualizer();

        // Start: tip is cracked → viewer sees a crack overlay (progress > 0).
        tip.addDamage(0.6);
        v.run();
        List<Packet> crackPass = packetsFor(v, 8, 64, 0);
        assertEquals(1, crackPass.size());
        assertTrue(crackPass.get(0).progress() > 0.0f, "viewer sees the crack on the distressed tip");
        assertEquals(viewer.getName(), crackPass.get(0).playerName());

        // Action: support placed → structure re-solves to stable (distress 0).
        v.packets.clear();
        tip.repair();
        v.run();
        List<Packet> rescuePass = packetsFor(v, 8, 64, 0);
        assertEquals(1, rescuePass.size(), "rescue pass sends exactly one packet");
        assertEquals(0.0f, rescuePass.get(0).progress(), "and it is the clear packet");

        // One more pass: silence.
        v.packets.clear();
        v.run();
        assertTrue(packetsFor(v, 8, 64, 0).isEmpty(), "no packet at all once the overlay is cleared");
    }

    /** Build a recording visualizer wired to this test's manager/world/configs. */
    private RecordingVisualizer makeVisualizer() {
        Plugin plugin = MockBukkit.createMockPlugin();
        return new RecordingVisualizer(manager, materialRegistry, plugin, effects, physics, new TaskTimings());
    }
}
