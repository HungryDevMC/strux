package dev.gesp.structural.minecraft.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.crack.CrackModel;
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
 * Tests for the revision-cached candidate set of {@link DamageVisualizer}
 * (feature perf-damage-visualizer-cache).
 *
 * <p>Before this change a pass scanned every tracked node to find the few that
 * should show a crack overlay. {@link StressVisualizer} already caches its
 * candidate set per world keyed by {@code StructureManager.revision()} and only
 * rebuilds on a revision bump; these tests pin the same behaviour for the damage
 * visualizer:
 *
 * <ul>
 *   <li><b>Crit 1</b> — a static world (revision unchanged) costs ~K (distressed)
 *       per pass, not N (all tracked). Measured through the {@code lastPassWork}
 *       seam, which counts the nodes a pass actually visits.</li>
 *   <li><b>Crit 2</b> — a world change (a freshly-cracked node bumps the revision)
 *       rebuilds the cache and overlays the new block.</li>
 *   <li><b>Crit 3</b> — overlays are byte-for-byte identical to the pre-cache,
 *       node-by-node scan for every node, every pass (equivalence oracle).</li>
 *   <li><b>Crit 5</b> — a node removed from the graph drops from BOTH the
 *       distressed cache and the overlay-tracking set (no unbounded growth).</li>
 * </ul>
 *
 * <p>(Crit 4 — rescue still clears in one packet — lives in
 * {@link DamageVisualizerClearingTest}, which this change must keep green.)
 */
@DisplayName("DamageVisualizer: revision-cached candidate set")
class DamageVisualizerCacheTest {

    private ServerMock server;
    private WorldMock world;
    private MaterialRegistry materialRegistry;
    private StructureManager manager;
    private EffectsConfig effects;
    private PhysicsConfig physics;

    /** One recorded sendDamage call. */
    private record Packet(int x, int y, int z, float progress, int sourceId) {}

    /** A visualizer that records every packet instead of dropping it into MockBukkit. */
    private static final class RecordingVisualizer extends DamageVisualizer {
        final List<Packet> packets = new ArrayList<>();

        RecordingVisualizer(StructureManager m, MaterialRegistry mr, Plugin p, EffectsConfig e, PhysicsConfig pc) {
            super(m, mr, p, e, pc, new TaskTimings());
        }

        @Override
        void sendDamage(Player player, Location loc, float progress, int sourceId) {
            packets.add(new Packet(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), progress, sourceId));
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

    /** A player at the origin column so every node within view distance is in range. */
    private PlayerMock playerAt(int x, int y, int z) {
        PlayerMock p = server.addPlayer("Viewer");
        p.teleport(new Location(world, x + 0.5, y, z + 0.5));
        return p;
    }

    private RecordingVisualizer makeVisualizer() {
        Plugin plugin = MockBukkit.createMockPlugin();
        return new RecordingVisualizer(manager, materialRegistry, plugin, effects, physics);
    }

    // ── Crit 1: static world → pass work is ~K distressed, not N tracked ───────

    @Test
    @DisplayName("Crit1: a static world's pass visits only the K distressed nodes, not all N tracked")
    void staticWorldPassVisitsOnlyDistressed() {
        int n = 2000;
        int k = 0;
        // Build a synthetic world: most nodes pristine, a handful cracked.
        for (int i = 0; i < n; i++) {
            Node node = addNode(i % 50, 64 + i / 50, 0);
            if (i % 400 == 0) { // 5 of 2000 cracked
                node.addDamage(0.5);
                k++;
            }
        }
        playerAt(0, 64, 0);
        RecordingVisualizer v = makeVisualizer();

        // Warm pass: rebuilds the cache (a full scan of N).
        v.run();
        assertEquals(n, v.lastPassWork(), "the rebuild pass scans all tracked nodes once");
        assertTrue(k > 0 && k < n, "test fixture must have a few cracked of many (K=" + k + ", N=" + n + ")");

        // Static passes: revision unchanged → iterate only the cached distressed set.
        v.run();
        assertEquals(k, v.lastPassWork(), "a static pass visits only the K distressed nodes, not N");
        v.run();
        assertEquals(k, v.lastPassWork(), "and stays at K on every subsequent static pass");
    }

    // ── Crit 2: world change → next pass rebuilds and overlays the new block ───

    @Test
    @DisplayName("Crit2: cracking a new block bumps the revision; the next pass rebuilds and overlays it")
    void worldChangeRebuildsAndOverlaysNewBlock() {
        Node a = addNode(0, 64, 0);
        Node b = addNode(1, 64, 0);
        a.addDamage(0.5); // a is cracked from the start
        playerAt(0, 64, 0);
        RecordingVisualizer v = makeVisualizer();

        v.run(); // warm: a overlaid, b not
        v.run(); // static: cached set is {a}
        assertEquals(1, v.lastPassWork(), "static pass over the cached {a} only");
        assertEquals(1, v.trackedOverlayCount(world), "only a is overlaid");

        // b cracks (impact/blast) → mark the world dirty (bumps revision).
        b.addDamage(0.5);
        manager.markDirty(world);

        v.packets.clear();
        v.run(); // rebuild: now visits N=2, overlays both
        assertEquals(2, v.lastPassWork(), "the rebuild pass after a revision bump rescans all nodes");
        assertEquals(2, v.trackedOverlayCount(world), "the newly-cracked block is now overlaid");
        boolean overlaidB = v.packets.stream().anyMatch(p -> p.x() == 1 && p.progress() > 0.0f);
        assertTrue(overlaidB, "the newly-cracked block b got its crack overlay");
    }

    // ── Crit 2b: a stress-only crack is found after a re-solve bumps revision ──

    @Test
    @DisplayName("Crit2b: a stress-driven crack (no damage) is picked up once the re-solve bumps the revision")
    void stressCrackPickedUpOnRevisionBump() {
        Node n = addNode(0, 64, 0);
        playerAt(0, 64, 0);
        RecordingVisualizer v = makeVisualizer();

        v.run(); // nothing cracked yet
        assertEquals(0, v.trackedOverlayCount(world), "no overlay before any distress");

        // A re-solve loads the node past the hairline crack threshold (0.60) and
        // bumps the revision (markDirty is called on every re-solve path).
        n.setVerticalStress(70.0); // 70/100 = 0.70 >= hairline 0.60
        manager.markDirty(world);

        v.packets.clear();
        v.run();
        assertEquals(1, v.trackedOverlayCount(world), "the stress crack is overlaid after the revision bump");
        boolean overlaid = v.packets.stream().anyMatch(p -> p.x() == 0 && p.progress() > 0.0f);
        assertTrue(overlaid, "the stress-cracked node got a crack overlay");
    }

    // ── Crit 3: overlays identical to the pre-cache node-by-node scan ──────────

    @Test
    @DisplayName("Crit3: overlays match a from-scratch full-scan oracle for every node, every pass")
    void overlaysMatchUncachedOracle() {
        // Mixed population: pristine, damage-cracked, and stress-cracked nodes.
        for (int i = 0; i < 60; i++) {
            Node node = addNode(i, 64, 0);
            if (i % 7 == 0) {
                node.addDamage(0.4 + 0.01 * (i % 5)); // damage cracks at varied progress
            } else if (i % 11 == 0) {
                node.setVerticalStress(65.0 + i); // stress cracks
            }
        }
        playerAt(30, 64, 0);
        RecordingVisualizer cached = makeVisualizer();

        // Run several static passes and compare each pass to the oracle.
        for (int pass = 0; pass < 4; pass++) {
            cached.packets.clear();
            cached.run();
            List<Packet> expected = oraclePass();
            assertEquals(
                    canonical(expected),
                    canonical(cached.packets),
                    "pass " + pass + ": cached overlays must equal the full-scan oracle exactly");
        }
    }

    /**
     * The pre-cache behaviour, recomputed from scratch: scan every node, send a
     * crack packet for each distressed one to the in-range viewer. This is the
     * exact loop the cached visualizer replaces, so its output is the oracle.
     */
    private List<Packet> oraclePass() {
        StructureGraph graph = manager.getGraph(world);
        CrackModel model = new CrackModel(physics);
        List<Packet> out = new ArrayList<>();
        double viewDistSq = effects.getDamageViewDistance() * effects.getDamageViewDistance();
        Player viewer = world.getPlayers().get(0);
        for (Node node : graph.getAllNodes()) {
            if (node.isGrounded()) {
                continue;
            }
            double damage = node.damage();
            double damageProgress = damage >= effects.getMinVisibleDamage() ? Math.min(damage, 0.99) : 0.0;
            double crackProgress =
                    effects.isStressCracksEnabled() ? model.crackLevel(node).overlayProgress() : 0.0;
            double progress = Math.max(damageProgress, crackProgress);
            if (progress <= 0.0) {
                continue;
            }
            NodePos pos = node.pos();
            Location loc = new Location(world, pos.x(), pos.y(), pos.z());
            if (viewer.getLocation().distanceSquared(loc) <= viewDistSq) {
                int hash = pos.x() * 73856093 ^ pos.y() * 19349663 ^ pos.z() * 83492791;
                int sourceId = Integer.MIN_VALUE + (hash & 0x7FFFFFFF);
                out.add(new Packet(pos.x(), pos.y(), pos.z(), (float) progress, sourceId));
            }
        }
        return out;
    }

    /** Sorted, deduped view of a packet list so order does not affect equality. */
    private List<String> canonical(List<Packet> packets) {
        return packets.stream()
                .map(p -> p.x() + "," + p.y() + "," + p.z() + ":" + p.progress() + ":" + p.sourceId())
                .sorted()
                .toList();
    }

    // ── Crit 5: removed node drops from BOTH the cache and the overlay set ─────

    @Test
    @DisplayName("Crit5: a removed node is dropped from the distressed cache and the overlay-tracking set")
    void removedNodeDropsFromCacheAndOverlaySet() {
        Node a = addNode(0, 64, 0);
        Node b = addNode(1, 64, 0);
        a.addDamage(0.5);
        b.addDamage(0.5);
        playerAt(0, 64, 0);
        RecordingVisualizer v = makeVisualizer();

        v.run(); // warm: both cracked
        v.run(); // static: cached set is {a, b}
        assertEquals(2, v.lastPassWork(), "cached set holds both cracked nodes");
        assertEquals(2, v.trackedOverlayCount(world), "both overlaid");

        // a is broken out of the graph. Removal bumps the revision in real code;
        // assert the cache is rebuilt and a leaks from NEITHER set.
        manager.getGraph(world).removeBlock(new NodePos(0, 0 + 64, 0));
        manager.markDirty(world);

        v.packets.clear();
        v.run(); // rebuild: only b survives
        assertEquals(1, v.lastPassWork(), "the rebuilt cache no longer holds the removed node");
        assertEquals(1, v.trackedOverlayCount(world), "the removed node is gone from the overlay-tracking set too");

        // And a stays gone on subsequent passes — no re-emit, no leak.
        v.packets.clear();
        v.run();
        boolean anyForA = v.packets.stream().anyMatch(p -> p.x() == 0);
        assertTrue(!anyForA, "no packets for the removed node");
        assertEquals(1, v.lastPassWork(), "still only the one survivor in the cache");
    }

    // ── E2E: cracked-among-thousands → crack a new one → rescue clears once ────

    @Test
    @DisplayName("E2E: a few cracked among thousands; static passes cost ~K; crack one more, then rescue clears once")
    void e2eStaticThenChangeThenRescue() {
        int n = 3000;
        List<Node> cracked = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Node node = addNode(i % 60, 64 + i / 60, 0);
            if (i % 600 == 0) { // 5 cracked among 3000
                node.addDamage(0.5);
                cracked.add(node);
            }
        }
        int k = cracked.size();
        playerAt(0, 64, 0);
        RecordingVisualizer v = makeVisualizer();

        // Several static passes after the warm rebuild: each costs ~K, not N.
        v.run(); // warm rebuild (N)
        assertEquals(n, v.lastPassWork());
        for (int pass = 0; pass < 3; pass++) {
            v.run();
            assertEquals(k, v.lastPassWork(), "static pass " + pass + " costs ~K, not N");
        }

        // Crack one more via an impact → revision bumps → next pass rebuilds.
        Node fresh = addNode(5, 64, 0); // new tracked node (addNode does not bump revision here)
        fresh.addDamage(0.5);
        manager.markDirty(world);
        v.packets.clear();
        v.run(); // rebuild
        assertEquals(k + 1, v.trackedOverlayCount(world), "the freshly-cracked node is overlaid");
        boolean overlaidFresh = v.packets.stream().anyMatch(p -> p.x() == 5 && p.y() == 64 && p.progress() > 0.0f);
        assertTrue(overlaidFresh, "impact-cracked node got its overlay");

        // Rescue it (support placed → re-solve stable → distress 0). Repair, bump.
        fresh.repair();
        manager.markDirty(world);
        v.packets.clear();
        v.run();
        List<Packet> freshPackets =
                v.packets.stream().filter(p -> p.x() == 5 && p.y() == 64).toList();
        assertEquals(1, freshPackets.size(), "exactly one packet for the rescued node");
        assertEquals(0.0f, freshPackets.get(0).progress(), "and it is a single clear packet");
        assertEquals(k, v.trackedOverlayCount(world), "back to the original K overlays after the rescue");
    }
}
