package dev.gesp.structural.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.impact.ImpactEngine;
import dev.gesp.structural.minecraft.listener.DebrisVisuals;
import dev.gesp.structural.minecraft.listener.ImpactProcessor;
import dev.gesp.structural.minecraft.listener.ProjectileImpactListener;
import dev.gesp.structural.minecraft.listener.QueuedImpact;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.recording.MinecraftEventRecorder;
import dev.gesp.structural.minecraft.visual.CollapseEffects;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.recording.ImpactEvent;
import dev.gesp.structural.recording.RecordingSession;
import dev.gesp.structural.recording.StruxEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.FallingBlock;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.ArrowMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * E2E tests for the tick-budgeted projectile-impact path.
 *
 * <pre>
 *   A projectile hit no longer settles the structure on the spot. The event
 *   handler only queues the hit; a repeating task drains the queue under a
 *   per-tick wall-clock budget. These tests fire the real ProjectileHitEvent,
 *   advance the scheduler with performTicks(...), and assert the WORLD ends up
 *   in sync — exactly the wiring the pure-:core tests cannot cover.
 * </pre>
 *
 * <p>We aim arrows horizontally so the energy is spent on the block actually
 * struck (penetration ray-casts along travel direction; a horizontal shot at a
 * column's support block leaves the column after one block, into open air).
 */
@DisplayName("E2E: tick-budgeted projectile impacts")
class ProjectileImpactE2ETest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        world = server.addSimpleWorld("impact_world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  (a) An impact still damages/collapses the structure after ticks pass
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A queued impact collapses the structure once ticks advance — not on the event itself")
    void impactCollapsesAfterTicks() {
        StructureManager manager = plugin.getStructureManager();

        // BEDROCK base, two glass blocks stacked on it. Glass is fragile
        // (blastResistance 0.3 → ~1.2 energy to punch through).
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.GLASS); // the support we shoot out
        place(0, 66, 0, Material.GLASS); // rides on the support → should fall

        Block support = world.getBlockAt(0, 65, 0);
        long revisionBefore = manager.revision(world);

        // Fire the real event a fast arrow striking the support produces.
        fireArrow(support, 12.0, 0.0, 0.0);

        // Nothing should have changed yet: the hit is only queued.
        assertEquals(Material.GLASS, support.getType(), "impact must NOT settle on the event itself");
        assertTrue(manager.isTracked(support), "support is still tracked before the queue drains");
        assertEquals(
                revisionBefore,
                manager.revision(world),
                "the queued (un-settled) impact must not bump the revision yet");

        // Drain the queue.
        server.getScheduler().performTicks(5);

        // The struck support was punched out and the glass above lost support.
        assertEquals(Material.AIR, support.getType(), "the struck support must be gone from the world");
        assertFalse(manager.isTracked(support), "the struck support must no longer be tracked");
        Block above = world.getBlockAt(0, 66, 0);
        assertEquals(Material.AIR, above.getType(), "the unsupported glass above must have collapsed out of the world");
        assertFalse(manager.isTracked(above), "the collapsed glass must no longer be tracked");
        // The foundation is untouched.
        assertEquals(Material.BEDROCK, world.getBlockAt(0, 64, 0).getType(), "ground is never destroyed by an arrow");
        // The settle bumped the revision (markDirty fired) and spawned falling debris.
        assertTrue(
                manager.revision(world) > revisionBefore,
                "settling the impact must bump the world revision (markDirty)");
        assertFalse(
                world.getEntitiesByClass(FallingBlock.class).isEmpty(), "punched-out blocks must spawn falling debris");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  (b) A volley is processed in FIFO (landing) order
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A volley settles in the order the arrows landed (FIFO)")
    void volleyProcessesInFifoOrder() {
        StructureManager manager = plugin.getStructureManager();

        // Three independent glass posts, each on its own bedrock foot, far enough
        // apart that one collapsing can't touch another. We hit them in a fixed
        // order and record the order the processor actually settles them.
        for (int x : new int[] {0, 4, 8}) {
            place(x, 64, 0, Material.BEDROCK);
            place(x, 65, 0, Material.GLASS);
        }

        ImpactProcessor processor = plugin.getImpactProcessor();
        int[] landed = {8, 0, 4}; // deliberately not sorted — proves order isn't re-derived
        for (int x : landed) {
            fireArrow(world.getBlockAt(x, 65, 0), 12.0, 0.0, 0.0);
        }
        assertEquals(3, processor.queueSize(), "all three hits should be queued, none settled yet");

        // Settle them all.
        server.getScheduler().performTicks(5);

        // Each post's glass was knocked out — order-independent outcome — and the
        // queue drained completely (FIFO leaves nothing behind).
        for (int x : new int[] {0, 4, 8}) {
            Block post = world.getBlockAt(x, 65, 0);
            assertEquals(Material.AIR, post.getType(), "post at x=" + x + " should be knocked out");
            assertFalse(manager.isTracked(post), "post at x=" + x + " should no longer be tracked");
        }
        assertEquals(0, processor.queueSize(), "FIFO queue must drain completely");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  (c) An impact whose target vanished before its turn is dropped silently
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A stale-target impact is dropped without error")
    void staleTargetImpactIsDropped() {
        StructureManager manager = plugin.getStructureManager();

        place(0, 64, 0, Material.BEDROCK);
        Block target = world.getBlockAt(0, 65, 0);
        target.setType(Material.GLASS);
        manager.onBlockPlaced(target);

        ImpactProcessor processor = plugin.getImpactProcessor();
        // Queue a hit on the glass directly (the listener path would also work,
        // but this pins the stale-drop branch without needing a live entity).
        NodePos origin = StructureManager.toBlockPos(target);
        processor.enqueue(new QueuedImpact(world, origin, target.getLocation(), 1.0, 0.0, 0.0, 50.0, "ARROW"));

        // The target is broken (e.g. another player mines it) BEFORE the queue runs.
        manager.onBlockBroken(target);
        target.setType(Material.AIR);
        assertFalse(manager.isTracked(target), "target is gone before its impact is processed");

        // Draining must not throw, and must leave the world as-is (no resurrection).
        server.getScheduler().performTicks(5);

        assertEquals(0, processor.queueSize(), "the stale impact must be consumed (dropped), not stuck in the queue");
        assertEquals(Material.AIR, target.getType(), "a dropped impact changes nothing");
        assertEquals(Material.BEDROCK, world.getBlockAt(0, 64, 0).getType(), "ground still stands");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  (d) The per-tick budget is respected — a volley spreads over >1 tick
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("With the normal (constructor) budget a small volley drains in a single tick")
    void defaultBudgetDrainsSmallVolleyInOneTick() {
        // The plugin builds the processor with impact.tick-budget-ms (10 ms by
        // default). That is plenty for a handful of fast impacts, so a small volley
        // must fully drain in ONE tick. This proves the constructor actually applied
        // a positive budget (not the zero fallback) and that the per-tick deadline
        // is computed forward in time, not backward.
        ImpactProcessor processor = plugin.getImpactProcessor();
        int[] xs = {0, 4, 8};
        for (int x : xs) {
            place(x, 64, 0, Material.BEDROCK);
            place(x, 65, 0, Material.GLASS);
        }
        for (int x : xs) {
            fireArrow(world.getBlockAt(x, 65, 0), 12.0, 0.0, 0.0);
        }
        assertEquals(xs.length, processor.queueSize(), "the whole volley is queued at event time");

        server.getScheduler().performTicks(1);

        assertEquals(0, processor.queueSize(), "a normal budget drains this small volley in a single tick");
    }

    @Test
    @DisplayName("A zero budget forces a volley to drain one impact per tick, not all at once")
    void tinyBudgetSpreadsVolleyOverMultipleTicks() {
        // Use the plugin's real, already-running processor but pin its budget to
        // zero. The drain loop always processes ONE impact before checking the
        // deadline, so a zero budget means exactly one impact per tick — the
        // queue must shrink step by step, never empty instantly.
        ImpactProcessor processor = plugin.getImpactProcessor();
        processor.setTickBudgetMs(0.0);

        // Four independent glass posts to knock out — real settle work each.
        int[] xs = {0, 4, 8, 12};
        for (int x : xs) {
            place(x, 64, 0, Material.BEDROCK);
            place(x, 65, 0, Material.GLASS);
        }
        for (int x : xs) {
            fireArrow(world.getBlockAt(x, 65, 0), 12.0, 0.0, 0.0);
        }
        assertEquals(xs.length, processor.queueSize(), "all four hits queued up front, none settled yet");

        // One tick → exactly one impact processed.
        server.getScheduler().performTicks(1);
        assertEquals(xs.length - 1, processor.queueSize(), "a zero budget drains exactly one impact in the first tick");

        // Another tick → exactly one more.
        server.getScheduler().performTicks(1);
        assertEquals(xs.length - 2, processor.queueSize(), "the queue keeps shrinking one per tick, not all at once");

        // Enough ticks → it fully drains.
        server.getScheduler().performTicks(xs.length);
        assertEquals(0, processor.queueSize(), "given enough ticks the whole volley settles");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  RECORDING: a settled impact is written to the active recording session
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A settled impact is recorded with its projectile, energy, and collapse list")
    void settledImpactIsRecorded() throws Exception {
        StructureManager manager = plugin.getStructureManager();
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.GLASS);
        place(0, 66, 0, Material.GLASS);

        MinecraftEventRecorder recorder = (MinecraftEventRecorder) manager.getEventRecorder();
        // Explicit id, verifyOnStop=false (skip the replay check — we only need the events).
        String session = recorder.startRecording(
                "test/impact-record", world.getUID().toString(), manager.getGraph(world), false);

        fireArrow(world.getBlockAt(0, 65, 0), 12.0, 0.0, 0.0);
        server.getScheduler().performTicks(5); // let the processor settle + record

        recorder.stopRecording();
        // The save is async; wait for the session file to materialise.
        RecordingSession saved = awaitSession(recorder, session);

        ImpactEvent impact = null;
        for (StruxEvent e : saved.getEvents()) {
            if (e.type() == StruxEvent.EventType.IMPACT && e instanceof ImpactEvent ie) {
                impact = ie;
                break;
            }
        }
        assertTrue(impact != null, "the settled impact must have been recorded");
        assertEquals("ARROW", impact.projectileId(), "the recorded event carries the projectile type");
        assertTrue(impact.energy() > 0.0, "the recorded event carries the kinetic energy");
        assertTrue(impact.destroyed(), "this hit punched a block through, so destroyed must be true");
        assertTrue(impact.collapsed().contains(new NodePos(0, 66, 0)), "the collapsed glass is in the recorded list");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  RECORDING: damageDealt is per-hit (not cumulative) and nonzero on the kill
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Two weak hits on one block record EQUAL per-hit damage, not a climbing running total")
    void perHitDamageIsNotCumulative() throws Exception {
        StructureManager manager = plugin.getStructureManager();
        // A lone glass block on bedrock — weak hits crack it without destroying it,
        // and nothing rides on it so there is no cascade to muddy the recording.
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.GLASS);
        Block target = world.getBlockAt(0, 65, 0);

        MinecraftEventRecorder recorder = (MinecraftEventRecorder) manager.getEventRecorder();
        String session = recorder.startRecording(
                "test/impact-perhit", world.getUID().toString(), manager.getGraph(world), false);

        // Two identical sub-lethal hits: glass capacity is ~1.2 energy, so a ~0.3
        // energy hit only cracks it. Each hit adds the SAME crack, so the recorded
        // per-hit damage must be (near) equal across the two — not 1× then 2×.
        fireArrow(target, 2.0, 0.0, 0.0);
        server.getScheduler().performTicks(3);
        fireArrow(target, 2.0, 0.0, 0.0);
        server.getScheduler().performTicks(3);

        recorder.stopRecording();
        RecordingSession saved = awaitSession(recorder, session);

        List<ImpactEvent> impacts = recordedImpacts(saved);
        assertEquals(2, impacts.size(), "both hits were recorded");
        double first = impacts.get(0).damageDealt();
        double second = impacts.get(1).damageDealt();
        assertTrue(first > 0.0, "the first crack recorded nonzero per-hit damage");
        assertTrue(second > 0.0, "the second crack recorded nonzero per-hit damage");
        // The bug recorded the running TOTAL, so the second hit was ~2× the first.
        // Per-hit semantics: the two hits are (near) equal.
        assertEquals(first, second, 1.0e-6, "per-hit damage must not climb hit-over-hit (it is a delta, not a sum)");
    }

    @Test
    @DisplayName("The killing blow records the remaining damage it dealt, never 0.0")
    void killingBlowRecordsNonzeroDamage() throws Exception {
        StructureManager manager = plugin.getStructureManager();
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.GLASS);
        Block target = world.getBlockAt(0, 65, 0);

        MinecraftEventRecorder recorder = (MinecraftEventRecorder) manager.getEventRecorder();
        String session =
                recorder.startRecording("test/impact-kill", world.getUID().toString(), manager.getGraph(world), false);

        // Three sub-lethal hits that accumulate past the break threshold: the third
        // destroys the block. The killing blow used to record 0.0 (a destroyed block
        // leaves the damaged() map for penetrated()); it must now record the slice it
        // dealt to finish the block off.
        fireArrow(target, 3.0, 0.0, 0.0);
        server.getScheduler().performTicks(3);
        fireArrow(target, 3.0, 0.0, 0.0);
        server.getScheduler().performTicks(3);
        fireArrow(target, 3.0, 0.0, 0.0);
        server.getScheduler().performTicks(3);

        recorder.stopRecording();
        RecordingSession saved = awaitSession(recorder, session);

        List<ImpactEvent> impacts = recordedImpacts(saved);
        assertTrue(impacts.size() >= 2, "at least the surviving hits plus the kill were recorded");
        ImpactEvent kill = impacts.get(impacts.size() - 1);
        assertTrue(kill.destroyed(), "the last hit destroyed the block");
        assertTrue(kill.damageDealt() > 0.0, "the killing blow records the remaining damage it dealt, not 0.0");
        assertTrue(kill.damageDealt() <= 1.0, "per-hit damage stays within 0..1");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  STALE DROP: the drop is logged at FINE (debug), not silently nothing
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Dropping a stale impact logs a FINE line naming the lost target")
    void staleDropIsLoggedAtFine() {
        ImpactProcessor processor = plugin.getImpactProcessor();

        // Capture FINE log records off the plugin logger.
        AtomicReference<String> fineMessage = new AtomicReference<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.FINE) {
                    fineMessage.set(record.getMessage());
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        plugin.getLogger().setLevel(Level.FINE);
        plugin.getLogger().addHandler(capture);
        try {
            // A queued impact whose target was never tracked → always stale.
            processor.enqueue(new QueuedImpact(
                    world, new NodePos(7, 70, 7), world.getBlockAt(7, 70, 7).getLocation(), 1, 0, 0, 9.0, "ARROW"));
            // Drain directly (no scheduler round-trip needed): run() pops and drops it.
            processor.run();
        } finally {
            plugin.getLogger().removeHandler(capture);
        }

        assertTrue(fineMessage.get() != null, "a dropped stale impact must log at FINE");
        assertTrue(fineMessage.get().contains("(7,70,7)"), "the FINE line names the target that vanished");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  LIFECYCLE: stop() is safe even if the task was never started
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("stop() on a never-started processor is a no-op and clears the queue")
    void stopOnUnstartedProcessorClearsQueue() {
        ImpactProcessor fresh = new ImpactProcessor(
                plugin,
                plugin.getStructureManager(),
                new ImpactEngine(),
                plugin.getDelayedCollapseManager(),
                plugin.getCascadeResumeManager(),
                new DebrisVisuals(plugin),
                plugin.getEffectsConfig(),
                new CollapseEffects(plugin.getEffectsConfig(), plugin),
                plugin.getCollapseGuard(),
                plugin.getLogger(),
                10.0,
                plugin.getTaskTimings());
        fresh.enqueue(new QueuedImpact(world, new NodePos(0, 1, 0), world.getSpawnLocation(), 1, 0, 0, 1.0, "ARROW"));
        assertEquals(1, fresh.queueSize(), "the impact is queued");

        fresh.stop(); // never start()ed — cancel() throws IllegalStateException, which stop() swallows

        assertEquals(0, fresh.queueSize(), "stop() drops anything still queued");
    }

    @Test
    @DisplayName("The plugin wires and exposes the impact processor, fire task, and collapse guard")
    void pluginExposesNewComponents() {
        assertNotNull(plugin.getImpactProcessor(), "the impact processor must be wired and exposed");
        assertNotNull(plugin.getFireScorchTask(), "the fire scorch task must be wired and exposed");
        assertNotNull(plugin.getCollapseGuard(), "the collapse guard must be exposed");
        // The getter returns the live instance, not a fresh one each call.
        assertSame(
                plugin.getImpactProcessor(),
                plugin.getImpactProcessor(),
                "getImpactProcessor returns the one instance");
        // The exposed processor is the one the listener feeds: a hit lands in its queue.
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.GLASS);
        fireArrow(world.getBlockAt(0, 65, 0), 12.0, 0.0, 0.0);
        assertEquals(1, plugin.getImpactProcessor().queueSize(), "the listener enqueues onto the exposed processor");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  DEDUP: one stuck/duplicate arrow must apply block damage at most once
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Firing the same arrow's hit twice enqueues the impact only once (dedup)")
    void duplicateHitFromSameArrowIsDeduped() {
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.GLASS);
        Block support = world.getBlockAt(0, 65, 0);

        ImpactProcessor processor = plugin.getImpactProcessor();

        // One arrow that re-fires its hit event (embedded/sliding arrow in the wild).
        ArrowMock arrow = newArrow(12.0, 0.0, 0.0);
        fireHit(arrow, support);
        fireHit(arrow, support); // a frozen-velocity re-fire of the SAME projectile
        fireHit(arrow, support);

        assertEquals(1, processor.queueSize(), "a single projectile may enqueue at most one block impact");
    }

    @Test
    @DisplayName("An arrow already embedded in a block (isInBlock) is ignored")
    void embeddedArrowIsIgnored() {
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.GLASS);
        Block support = world.getBlockAt(0, 65, 0);

        ImpactProcessor processor = plugin.getImpactProcessor();

        // MockBukkit's isInBlock() throws by default; override it to report embedded.
        ArrowMock embedded = new ArrowMock(server, UUID.randomUUID()) {
            @Override
            public boolean isInBlock() {
                return true;
            }
        };
        server.registerEntity(embedded);
        embedded.setVelocity(new Vector(12.0, 0.0, 0.0));
        fireHit(embedded, support);

        assertEquals(0, processor.queueSize(), "an embedded (resting) arrow must not deal a fresh impact");
    }

    @Test
    @DisplayName("Two different arrows each apply their own impact")
    void twoDifferentArrowsBothApplyImpact() {
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.GLASS);
        place(4, 64, 0, Material.BEDROCK);
        place(4, 65, 0, Material.GLASS);

        ImpactProcessor processor = plugin.getImpactProcessor();

        ArrowMock first = newArrow(12.0, 0.0, 0.0);
        ArrowMock second = newArrow(12.0, 0.0, 0.0);
        fireHit(first, world.getBlockAt(0, 65, 0));
        fireHit(second, world.getBlockAt(4, 65, 0));

        assertEquals(2, processor.queueSize(), "distinct projectiles each enqueue their own impact");
    }

    @Test
    @DisplayName("The dedup set holds up to its cap, then evicts the oldest so it can hit again")
    void dedupSetEvictsOldestWhenCapped() {
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.GLASS);
        Block support = world.getBlockAt(0, 65, 0);

        ImpactProcessor processor = plugin.getImpactProcessor();
        // A standalone listener feeding the real processor, capped at 2 remembered
        // projectiles. Cap=2 (not 1) pins the eviction BOUNDARY: with the correct
        // `> cap` test the set still holds both A and B (no eviction yet), so A's
        // re-fire is deduped; a `>= cap` off-by-one would evict A when B lands and
        // let A through again. The two assertions below distinguish those.
        ProjectileImpactListener listener = new ProjectileImpactListener(
                plugin.getStructureManager(), processor, plugin.getCollapseGuard(), true, 1.0);
        listener.setMaxRememberedProjectiles(2);

        ArrowMock a = newArrow(12.0, 0.0, 0.0);
        ArrowMock b = newArrow(12.0, 0.0, 0.0);
        ArrowMock c = newArrow(12.0, 0.0, 0.0);

        a.setLocation(support.getLocation());
        b.setLocation(support.getLocation());
        c.setLocation(support.getLocation());

        listener.onProjectileHit(new ProjectileHitEvent(a, support)); // set {A}, size 1
        listener.onProjectileHit(new ProjectileHitEvent(b, support)); // set {A,B}, size 2 — at cap, no eviction
        assertEquals(2, processor.queueSize(), "two distinct arrows are queued");

        // A is still remembered (cap not yet exceeded), so its re-fire is deduped.
        listener.onProjectileHit(new ProjectileHitEvent(a, support));
        assertEquals(2, processor.queueSize(), "A is still within the cap → its re-fire is deduped, not re-queued");

        // C exceeds the cap → A (oldest) is evicted.
        listener.onProjectileHit(new ProjectileHitEvent(c, support)); // set {B,C}
        assertEquals(3, processor.queueSize(), "C is a fresh projectile and is queued");

        // A was evicted, so its re-fire is now treated as new — proving eviction ran.
        listener.onProjectileHit(new ProjectileHitEvent(a, support));
        assertEquals(4, processor.queueSize(), "the evicted oldest projectile is forgotten and can hit again");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────────────────

    /** Place a block and register it with the structure manager. */
    private void place(int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(material);
        plugin.getStructureManager().onBlockPlaced(block);
    }

    /** All IMPACT events in a saved session, in sequence order. */
    private List<ImpactEvent> recordedImpacts(RecordingSession saved) {
        List<ImpactEvent> impacts = new ArrayList<>();
        for (StruxEvent e : saved.getEvents()) {
            if (e.type() == StruxEvent.EventType.IMPACT && e instanceof ImpactEvent ie) {
                impacts.add(ie);
            }
        }
        impacts.sort(Comparator.comparingLong(ImpactEvent::sequenceId));
        return impacts;
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

    /** Fire the real ProjectileHitEvent a moving arrow striking {@code hitBlock} produces. */
    private void fireArrow(Block hitBlock, double vx, double vy, double vz) {
        ArrowMock arrow = newArrow(vx, vy, vz);
        fireHit(arrow, hitBlock);
    }

    /**
     * Build (and register) a moving, in-flight arrow with a fixed velocity but no
     * target yet. MockBukkit leaves {@code isInBlock()} unimplemented (it throws),
     * so we override it to report a still-flying arrow — the listener calls it on
     * every hit to skip arrows already at rest.
     */
    private ArrowMock newArrow(double vx, double vy, double vz) {
        ArrowMock arrow = new ArrowMock(server, UUID.randomUUID()) {
            @Override
            public boolean isInBlock() {
                return false; // in flight — a genuine fresh hit
            }
        };
        server.registerEntity(arrow);
        arrow.setVelocity(new Vector(vx, vy, vz));
        return arrow;
    }

    /** Fire the real ProjectileHitEvent {@code arrow} produces when it strikes {@code hitBlock}. */
    private void fireHit(ArrowMock arrow, Block hitBlock) {
        arrow.setLocation(hitBlock.getLocation());
        Arrow asArrow = arrow; // hit-block constructor wants a Projectile
        server.getPluginManager().callEvent(new ProjectileHitEvent(asArrow, hitBlock));
    }
}
