package dev.gesp.structural.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.listener.CascadeResumeManager;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.recording.MinecraftEventRecorder;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.recording.CascadeEvent;
import dev.gesp.structural.recording.RecordingSession;
import dev.gesp.structural.recording.StruxEvent;
import dev.gesp.structural.solver.CascadeEngine;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * E2E for cross-tick cascade resumption.
 *
 * <pre>
 *   The step cap (maxCascadeSteps) is a per-event work budget. A huge collapse
 *   used to truncate at the cap on the break tick and STRAND floating blocks that
 *   nothing ever cleaned. The fix resumes the truncated cascade on later ticks —
 *   each tick still bounded by the cap — until the structure is fully settled.
 *
 *   These tests build a span larger than a deliberately tiny cap, break one of
 *   its two piers (turning the span into an overloaded cantilever that trims in
 *   capped OVERLOAD steps), and assert:
 *     (a) right after the break, work is bounded (not the whole span at once);
 *     (b) after enough ticks, every block that must fall is gone AND no tracked
 *         block is left floating;
 *     (c) an under-cap collapse still finishes in a single tick (no regression).
 * </pre>
 */
@DisplayName("E2E: a truncated cascade resumes over later ticks instead of stranding floaters")
class CascadeResumeE2ETest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private WorldMock world;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        world = server.addSimpleWorld("resume_world");
        player = server.addPlayer("Breaker");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static final int SPAN_LEN = 40; // span length; far longer than the tiny cap below
    private static final int TINY_CAP = 4;

    /**
     * A long horizontal span at y=65 held up at BOTH ends by short stone piers.
     * As a two-pier bridge it stands. Knock out one pier and the span becomes a
     * long cantilever off the survivor — overloaded at the root, trimming in
     * capped OVERLOAD steps that a tiny cap cannot finish in one tick.
     *
     * <p>Built with {@link StructureManager#addBlockDirect} so the per-placement
     * overload check doesn't collapse the half-finished cantilever mid-build —
     * we want the WHOLE span standing before the trigger break.
     *
     * @return the x of the doomed pier's base block (the break trigger)
     */
    private int buildTwoPierSpan() {
        int y0 = 64;
        // Left pier (survivor) at x=0, right pier (doomed) at x=SPAN_LEN.
        for (int px : new int[] {0, SPAN_LEN}) {
            addDirect(px, y0, 0, Material.BEDROCK);
            addDirect(px, y0 + 1, 0, Material.STONE);
        }
        // The span itself at y0+1, between the piers.
        for (int x = 1; x < SPAN_LEN; x++) {
            addDirect(x, y0 + 1, 0, Material.STONE);
        }
        // A non-tracked bedrock roof two blocks above the span removes its sky
        // access, so the background WeatherLoadTask skips it. (Weather mutating
        // this large graph mid-scan is an unrelated pre-existing concern; this
        // test isolates the cascade-resume behavior from it.)
        for (int x = 0; x <= SPAN_LEN; x++) {
            world.getBlockAt(x, y0 + 3, 0).setType(Material.BEDROCK);
        }
        return SPAN_LEN; // break the doomed pier's base
    }

    private void addDirect(int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(material);
        plugin.getStructureManager().addBlockDirect(block);
    }

    @Test
    @DisplayName("A truncated fire burn-out resumes over later ticks instead of stranding floaters")
    void truncatedFireBurnResumes() {
        StructureManager manager = plugin.getStructureManager();
        manager.getConfig().setMaxCascadeSteps(TINY_CAP);

        int doomedPierX = buildTwoPierSpan();
        StructureGraph graph = manager.getGraph(world);
        int totalBefore = graph.size();

        // Fire consuming the doomed pier is the same disturbance a break is — the span
        // becomes an overloaded cantilever that trims in capped OVERLOAD steps. With the
        // tiny cap it truncates, so FireScorchTask.onBlockBurn MUST register a resume
        // job. Before the fix, fire was the one collapse path that ran a synchronous,
        // non-resumable settle and silently dropped whatever was past the cap.
        Block trigger = world.getBlockAt(doomedPierX, 65, 0); // the doomed pier holding the span up
        server.getPluginManager().callEvent(new BlockBurnEvent(trigger, trigger));

        assertEquals(
                1,
                plugin.getCascadeResumeManager().pendingWorlds(),
                "a truncated fire burn must register a resume job for the world");

        // Bounded: the burn tick can't have dropped the whole span at once.
        int standingRightAfter = graph.size();
        assertTrue(
                standingRightAfter > totalBefore - SPAN_LEN,
                "the burn tick must be bounded by the cap, not collapse the whole span at once " + "(standing="
                        + standingRightAfter + " of " + totalBefore + ")");

        // Resumes: after enough ticks the structure is fully settled, no floaters left.
        server.getScheduler().performTicks(400);
        assertTrue(
                graph.getFloatingBlocks().isEmpty(),
                "after fire resumption no tracked block may be left floating: " + graph.getFloatingBlocks());
    }

    @Test
    @DisplayName("(a)+(b) a capped break collapses gradually and leaves NO floaters once settled")
    void truncatedCascadeResumesAndLeavesNoFloaters() {
        StructureManager manager = plugin.getStructureManager();
        PhysicsConfig config = manager.getConfig();
        config.setMaxCascadeSteps(TINY_CAP);

        int doomedPierX = buildTwoPierSpan();
        StructureGraph graph = manager.getGraph(world);
        int totalBefore = graph.size();

        // Break the doomed pier's base. The span loses one support → overloaded
        // cantilever → settle truncates at the tiny cap, stranding floaters
        // unless the resume task finishes the job.
        Block trigger = world.getBlockAt(doomedPierX, 64, 0);
        server.getPluginManager().callEvent(new BlockBreakEvent(trigger, player));

        // The break truncated, so a resume job is now in flight for this world.
        assertEquals(
                1,
                plugin.getCascadeResumeManager().pendingWorlds(),
                "a truncated break must register a resume job for the world");

        // (a) BOUNDED: the break tick must not have cleared the whole span. With
        // the cap at TINY_CAP, far more than that many blocks still stand right
        // after the break (the rest are scheduled to fall over later ticks).
        int standingRightAfter = graph.size();
        assertTrue(
                standingRightAfter > totalBefore - SPAN_LEN,
                "the break tick must be bounded by the cap, not collapse the entire span at once " + "(standing="
                        + standingRightAfter + " of " + totalBefore + ")");

        // (b) RESUMES: advance enough ticks for the resume task + delayed-collapse
        // drama to drain, then assert the structure is fully settled with no
        // floaters left dangling.
        server.getScheduler().performTicks(400);

        assertTrue(
                graph.getFloatingBlocks().isEmpty(),
                "after resumption no tracked block may be left floating: " + graph.getFloatingBlocks());

        // STRONGER: the graph must be a genuine fixpoint — a fresh uncapped
        // settle on the SAME graph collapses nothing. Before the fix, the cap
        // truncation stranded overloaded-but-grounded leftovers that the
        // per-batch sweep (which only drops floaters) never finished; this check
        // catches exactly that, where the floaters-only check is fooled.
        PhysicsConfig uncapped = new PhysicsConfig();
        uncapped.setMaxCascadeSteps(1_000_000);
        int residual =
                new CascadeEngine(uncapped).settle(graph, SolverCallback.NONE).size();
        assertEquals(0, residual, "the settled graph must be a fixpoint — no overloaded/floating leftovers remain");

        // The resume job retired itself once the world settled — it is not
        // ticking forever.
        assertEquals(
                0,
                plugin.getCascadeResumeManager().pendingWorlds(),
                "the resume job must retire once the structure is fully settled");

        // The survivor pier and its base are still standing; the doomed pier and
        // the span have come down.
        assertEquals(Material.BEDROCK, world.getBlockAt(0, 64, 0).getType(), "survivor ground holds");
        assertEquals(Material.STONE, world.getBlockAt(0, 65, 0).getType(), "survivor pier holds");
    }

    @Test
    @DisplayName("(c) an under-cap collapse still finishes in one tick (no behavior change)")
    void underCapCollapseFinishesInOneTick() {
        StructureManager manager = plugin.getStructureManager();
        PhysicsConfig config = manager.getConfig();
        config.setMaxCascadeSteps(50); // the server default, well above this small collapse

        // BEDROCK(y=0) - STONE(y=1..4): break the base, three blocks above fall.
        place(0, 0, 0, Material.BEDROCK);
        for (int y = 1; y <= 4; y++) {
            place(0, y, 0, Material.STONE);
        }
        StructureGraph graph = manager.getGraph(world);

        Block base = world.getBlockAt(0, 1, 0);
        server.getPluginManager().callEvent(new BlockBreakEvent(base, player));

        // An under-cap collapse is NOT truncated, so it must NOT register a resume
        // job — the resume path is for cap-truncated collapses only.
        assertEquals(
                0,
                plugin.getCascadeResumeManager().pendingWorlds(),
                "an under-cap collapse must not schedule any resume work");

        // One tick of delayed-collapse drama is enough; nothing should still be
        // pending a resume.
        server.getScheduler().performTicks(60);

        assertTrue(graph.getFloatingBlocks().isEmpty(), "small collapse leaves nothing dangling");
        for (int y = 2; y <= 4; y++) {
            assertEquals(Material.AIR, world.getBlockAt(0, y, 0).getType(), "y=" + y + " fell");
            assertFalse(manager.isTracked(world.getBlockAt(0, y, 0)), "y=" + y + " untracked");
        }
        assertEquals(Material.BEDROCK, world.getBlockAt(0, 0, 0).getType(), "ground holds");
    }

    @Test
    @DisplayName("Follow-up collapses on later ticks are RECORDED as BREAK_RESUME cascade events")
    void resumeCollapsesAreRecorded() throws Exception {
        StructureManager manager = plugin.getStructureManager();
        manager.getConfig().setMaxCascadeSteps(TINY_CAP);

        int doomedPierX = buildTwoPierSpan();

        MinecraftEventRecorder recorder = (MinecraftEventRecorder) manager.getEventRecorder();
        String session = recorder.startRecording(
                "test/resume-record", world.getUID().toString(), manager.getGraph(world), false);

        Block trigger = world.getBlockAt(doomedPierX, 64, 0);
        server.getPluginManager().callEvent(new BlockBreakEvent(trigger, player));
        // Let the resume task drain over ticks; the recorder's per-tick throttle
        // resets each tick via the scheduled onTickStart, so spread the ticks.
        server.getScheduler().performTicks(400);

        recorder.stopRecording();
        RecordingSession saved = awaitSession(recorder, session);

        CascadeEvent resume = null;
        for (StruxEvent e : saved.getEvents()) {
            if (e.type() == StruxEvent.EventType.CASCADE
                    && e instanceof CascadeEvent ce
                    && "BREAK_RESUME".equals(ce.reason())) {
                resume = ce;
                break;
            }
        }
        assertNotNull(resume, "follow-up collapses must be recorded, not applied silently");
        assertFalse(resume.steps().isEmpty(), "a recorded resume event must list the blocks that fell");
    }

    @Test
    @DisplayName("A resume that keeps finding work is stopped at the tick bound with a WARNING")
    void resumeStopsAtTickBoundWithWarning() {
        StructureManager manager = plugin.getStructureManager();
        manager.getConfig().setMaxCascadeSteps(TINY_CAP);
        buildTwoPierSpan();
        // Disconnect the doomed pier base directly so the span is left overloaded
        // and a resume genuinely has work to do.
        manager.removeBlockDirect(world, new NodePos(SPAN_LEN, 64, 0));

        // A manager with a deliberately tiny bound: it must give up after one
        // tick and WARN rather than keep finishing.
        AtomicReference<String> warning = new AtomicReference<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) {
                    warning.set(record.getMessage());
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        plugin.getLogger().addHandler(capture);
        try {
            CascadeResumeManager bounded = new CascadeResumeManager(
                    plugin,
                    manager,
                    plugin.getDelayedCollapseManager(),
                    plugin.getLogger(),
                    1,
                    plugin.getTaskTimings());
            bounded.enqueue(world);
            bounded.run(); // tick 0: does work (ticksSpent 0 -> 1)
            bounded.run(); // tick 1: ticksSpent (1) >= bound (1) -> WARN + drop

            assertEquals(0, bounded.pendingWorlds(), "the job must be dropped once it hits the tick bound");
            assertNotNull(warning.get(), "hitting the resume tick bound must log a WARNING");
            assertTrue(
                    warning.get().contains("max-resume-ticks") || warning.get().contains("tick bound"),
                    "the WARNING must point operators at the bound: " + warning.get());
        } finally {
            plugin.getLogger().removeHandler(capture);
        }
    }

    @Test
    @DisplayName("resumeCascade on a world with no graph is a harmless no-op")
    void resumeCascadeOnUntrackedWorldIsNoOp() {
        WorldMock empty = server.addSimpleWorld("empty_world");
        CascadeEngine.SettleOutcome outcome = plugin.getStructureManager().resumeCascade(empty);
        assertTrue(outcome.collapsed().isEmpty(), "an untracked world collapses nothing");
        assertFalse(outcome.truncated(), "an untracked world is never truncated");
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

    private void place(int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(material);
        plugin.getStructureManager().onBlockPlaced(block);
    }
}
