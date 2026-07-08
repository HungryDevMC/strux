package dev.gesp.structural.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.blast.StruxExplosionEngine;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.listener.BlastProcessor;
import dev.gesp.structural.minecraft.listener.CraterApplier;
import dev.gesp.structural.minecraft.listener.DebrisVisuals;
import dev.gesp.structural.minecraft.listener.QueuedBlast;
import dev.gesp.structural.minecraft.listener.StreamedBukkitCraterRemover;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.visual.CollapseEffects;
import dev.gesp.structural.solver.CascadeEngine;
import org.bukkit.Location;
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
 * E2E for cross-tick cascade resumption on the BLAST path.
 *
 * <pre>
 *   A blast settles its crater region in-core (crater → floating → damaged →
 *   overload) and the adapter then runs a SCOPED ground-refresh that only drops
 *   FLOATING blocks. Neither reaches the overloaded-but-still-grounded root of a
 *   doomed cantilever the blast creates OUTSIDE its crater scope — those blocks
 *   should progressively overload and fall, but the blast path used to be the one
 *   disturbance with NO link to {@link
 *   dev.gesp.structural.minecraft.listener.CascadeResumeManager}, so it abandoned
 *   them: they hung in the air forever (the production "16 floaters" report from a
 *   blast-heavy match).
 *
 *   These tests build a span longer than a deliberately tiny cap, BLAST one of its
 *   two piers, and assert the blast — like a break — registers a resume job and
 *   drains to a genuine fixpoint over later ticks.
 * </pre>
 */
@DisplayName("E2E: a truncated BLAST cascade resumes over later ticks instead of stranding leftovers")
class BlastResumeE2ETest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        world = server.addSimpleWorld("blast_resume_world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static final int SPAN_LEN = 40; // far longer than the tiny cap below
    private static final int TINY_CAP = 4;

    /**
     * A long horizontal span at y=65 held up at BOTH ends by short stone piers; a
     * non-tracked bedrock roof removes sky access so the background WeatherLoadTask
     * leaves it alone. Built with {@link StructureManager#addBlockDirect} so the
     * per-placement overload check doesn't trim the half-built span — we want the
     * WHOLE span standing before the blast.
     *
     * @return the x of the doomed pier (the blast target)
     */
    private int buildTwoPierSpan() {
        int y0 = 64;
        for (int px : new int[] {0, SPAN_LEN}) {
            addDirect(px, y0, 0, Material.BEDROCK);
            addDirect(px, y0 + 1, 0, Material.STONE);
        }
        for (int x = 1; x < SPAN_LEN; x++) {
            addDirect(x, y0 + 1, 0, Material.STONE);
        }
        for (int x = 0; x <= SPAN_LEN; x++) {
            world.getBlockAt(x, y0 + 3, 0).setType(Material.BEDROCK);
        }
        return SPAN_LEN;
    }

    private void addDirect(int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(material);
        plugin.getStructureManager().addBlockDirect(block);
    }

    @Test
    @DisplayName("(1)+(4) a blast over the cap registers a resume job and the settle tick stays bounded")
    void blastTruncatedCascadeRegistersResumeAndIsBounded() {
        StructureManager manager = plugin.getStructureManager();
        manager.getConfig().setMaxCascadeSteps(TINY_CAP);

        int doomedPierX = buildTwoPierSpan();
        StructureGraph graph = manager.getGraph(world);
        int totalBefore = graph.size();

        // Drive a dedicated processor with direct run() calls instead of performTicks,
        // so the (scheduler-driven) CascadeResumeManager stays FROZEN while we observe
        // the enqueue. That makes "(1) the blast registered a resume job" deterministic
        // — otherwise the resume task would drain the job within the same tick window
        // and pendingWorlds() would read 0 again before we could check it.
        BlastProcessor processor = newInlineProcessor();
        processor.enqueue(new QueuedBlast(world, new Location(world, doomedPierX, 65, 0), 6.0));
        int guard = 0;
        do {
            processor.run();
        } while (processor.hasActiveBlast() && ++guard < 10_000);

        // (1) the blast left an overloaded cantilever its scoped settle couldn't
        // finish, so it handed a resume job to the manager.
        assertEquals(
                1,
                plugin.getCascadeResumeManager().pendingWorlds(),
                "a truncated blast cascade must register a resume job for the world");

        // (4) BOUNDED: far more than the cantilever still stands right after the
        // blast settles — the rest comes down over later resume ticks, not all at once.
        int standingRightAfter = graph.size();
        assertTrue(
                standingRightAfter > totalBefore - SPAN_LEN,
                "the blast tick must be bounded by the cap, not collapse the whole span at once " + "(standing="
                        + standingRightAfter + " of " + totalBefore + ")");
    }

    /**
     * A processor that runs its overload queries INLINE (no off-thread worker) with a
     * huge wall-clock budget, so a single {@code run()} loop finalizes the blast — the
     * tests drive it with direct {@code run()} calls to keep the scheduler-driven
     * resume manager frozen while they observe the enqueue. It still shares the
     * plugin's real {@link dev.gesp.structural.minecraft.listener.CascadeResumeManager}.
     */
    private BlastProcessor newInlineProcessor() {
        CraterApplier craterApplier = new CraterApplier(
                plugin.getCollapseGuard(),
                new CollapseEffects(plugin.getEffectsConfig(), plugin),
                new DebrisVisuals(plugin),
                new StreamedBukkitCraterRemover(),
                8);
        return new BlastProcessor(
                plugin,
                plugin.getStructureManager(),
                new StruxExplosionEngine(new PhysicsConfig()),
                plugin.getDelayedCollapseManager(),
                plugin.getCascadeResumeManager(),
                craterApplier,
                plugin.getEffectsConfig(),
                new CollapseEffects(plugin.getEffectsConfig(), plugin),
                plugin.getLogger(),
                1_000_000.0,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                plugin.getTaskTimings());
    }

    @Test
    @DisplayName("(2)+(3) the blast resumes over later ticks and leaves a settled fixpoint")
    void blastResumesAndLeavesAFixpoint() {
        StructureManager manager = plugin.getStructureManager();
        manager.getConfig().setMaxCascadeSteps(TINY_CAP);

        int doomedPierX = buildTwoPierSpan();
        StructureGraph graph = manager.getGraph(world);

        manager.blast(world, new Location(world, doomedPierX, 65, 0), 6.0);

        // Advance enough ticks for the blast to settle, the resume task to drain, and
        // the delayed-collapse drama to finish.
        server.getScheduler().performTicks(800);

        // (3) no pure floaters dangling.
        assertTrue(
                graph.getFloatingBlocks().isEmpty(),
                "after blast resumption no tracked block may be left floating: " + graph.getFloatingBlocks());

        // (2) STRONGER: the graph must be a genuine fixpoint — a fresh uncapped settle
        // on the SAME graph collapses nothing. This catches the overloaded-but-grounded
        // leftovers the floaters-only refresh never finished (the real blast bug).
        PhysicsConfig uncapped = new PhysicsConfig();
        uncapped.setMaxCascadeSteps(1_000_000);
        int residual =
                new CascadeEngine(uncapped).settle(graph, SolverCallback.NONE).size();
        assertEquals(0, residual, "the settled graph must be a fixpoint — no overloaded/floating leftovers remain");

        // (3) the resume job retired once the world settled — not ticking forever.
        assertEquals(
                0,
                plugin.getCascadeResumeManager().pendingWorlds(),
                "the resume job must retire once the structure is fully settled");

        // The survivor pier and its base still stand.
        assertEquals(Material.BEDROCK, world.getBlockAt(0, 64, 0).getType(), "survivor ground holds");
        assertEquals(Material.STONE, world.getBlockAt(0, 65, 0).getType(), "survivor pier holds");
    }

    @Test
    @DisplayName("(5) an under-cap blast collapse does NOT register a resume job (no regression)")
    void underCapBlastDoesNotRegisterResume() {
        StructureManager manager = plugin.getStructureManager();
        manager.getConfig().setMaxCascadeSteps(50); // server default, well above this small collapse

        // A short tower: BEDROCK(y=64) - STONE(y=65..68). Blasting the lower pier
        // collapses only a handful of blocks — under the cap, so no resume needed.
        addDirect(0, 64, 0, Material.BEDROCK);
        for (int y = 65; y <= 68; y++) {
            addDirect(0, y, 0, Material.STONE);
        }

        manager.blast(world, new Location(world, 0, 65, 0), 6.0);
        server.getScheduler().performTicks(60);

        assertEquals(
                0,
                plugin.getCascadeResumeManager().pendingWorlds(),
                "an under-cap blast collapse must not schedule any resume work");
        assertTrue(
                manager.getGraph(world).getFloatingBlocks().isEmpty(), "small blast collapse leaves nothing dangling");
    }
}
