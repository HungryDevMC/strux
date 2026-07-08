package dev.gesp.structural.minecraft.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.assess.StructureGrade;
import dev.gesp.structural.assess.StructureReport;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.config.EffectsConfig;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.material.MaterialRegistry;
import dev.gesp.structural.minecraft.perf.TaskTimings;
import dev.gesp.structural.minecraft.visual.ActionbarArbiter.Priority;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
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
 * Tests for {@link StressSummaryTask}: a player looking at a tracked structure
 * sees a live action-bar readout with the right avg/peak and bar glyphs; the
 * arbiter suppresses it when a critical warning fired the same tick; disabled →
 * nothing.
 *
 * <p>Block targeting is driven through the {@code focusBlock} seam so the test
 * does not depend on MockBukkit raytracing. The avg/peak come from the REAL
 * {@code assessWorld} report — the test recomputes the expected bar/numbers from
 * that report so it asserts the wiring without hard-coding physics output.
 */
@DisplayName("StressSummaryTask: live stress readout in the action bar")
class StressSummaryTaskTest {

    private ServerMock server;
    private WorldMock world;
    private StructureManager manager;
    private EffectsConfig effects;
    private final AtomicLong tick = new AtomicLong(50);

    /**
     * A task whose look-at target is a fixed override (MockBukkit can't raytrace),
     * so the real {@code focusBlock} selection logic still runs. {@code lookAt}
     * sets what the player is aiming at; null means "looking at nothing".
     */
    private static final class TestTask extends StressSummaryTask {
        private Block target;

        TestTask(StructureManager m, Plugin p, ActionbarArbiter a, TaskTimings t, boolean enabled) {
            super(m, p, a, t, enabled);
        }

        void lookAt(Block block) {
            this.target = block;
        }

        @Override
        Block targetBlock(Player player) {
            return target;
        }
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("test_world");
        manager = new StructureManager(new MaterialRegistry(), new PhysicsConfig());
        effects = new EffectsConfig();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * Build a tiny loaded structure: a ground anchor with a heavy stack on top so
     * the solver produces real, non-zero stress. Returns the topmost block, which
     * a player can "look at".
     */
    private Block buildLoadedStructure() {
        StructureGraph graph = manager.getOrCreateGraph(world);
        // Ground at y=0.
        graph.addGroundBlock(new NodePos(0, 0, 0));
        // A column of heavy blocks with a modest capacity → high stress lower down.
        for (int y = 1; y <= 4; y++) {
            graph.addNode(new NodePos(0, y, 0), new MaterialSpec(20.0, 50.0), false);
        }
        manager.markDirty(world);
        // The block a player aims at (the tip is in the graph and thus tracked).
        return world.getBlockAt(0, 4, 0);
    }

    private ActionbarArbiter arbiter() {
        return new ActionbarArbiter(tick::get);
    }

    private TestTask task(ActionbarArbiter arbiter, boolean enabled) {
        Plugin plugin = MockBukkit.createMockPlugin();
        return new TestTask(manager, plugin, arbiter, new TaskTimings(), enabled);
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** Drain the player's action-bar queue and return the plain text of the last message (null if none). */
    private static String lastActionBar(PlayerMock p) {
        Component last = null;
        Component next;
        while ((next = p.nextActionBar()) != null) {
            last = next;
        }
        return last == null ? null : plain(last);
    }

    // ── tests ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("looking at a tracked structure shows the avg/peak readout with the right bar glyphs")
    void looksAtTrackedShowsReadout() {
        Block tip = buildLoadedStructure();
        PlayerMock player = server.addPlayer("Viewer");
        ActionbarArbiter arbiter = arbiter();
        TestTask task = task(arbiter, true);
        task.lookAt(tip);

        // The real assessment the task will surface.
        StructureReport report = manager.assessWorld(world);
        assertTrue(report.assessedNodes() > 0, "the structure must have load-bearing nodes to grade");

        task.updateFor(player);

        String text = lastActionBar(player);
        // Independently recompute what the readout should say from the report.
        String expectedBar = StressBar.bar(report.avgPercent() / 100.0, StressSummaryTask.BAR_CELLS);
        assertEquals(
                "World: " + expectedBar + " " + report.avgPercent() + "% avg | Peak: " + report.peakPercent() + "%",
                text);
        // And it must match what render() produces for that same report.
        assertEquals(plain(StressSummaryTask.render(report)), text);
    }

    @Test
    @DisplayName("the readout is labelled World: (honest about measuring the whole world, not one structure)")
    void readoutLabelledWorld() {
        Block tip = buildLoadedStructure();
        PlayerMock player = server.addPlayer("Viewer");
        ActionbarArbiter arbiter = arbiter();
        TestTask task = task(arbiter, true);
        task.lookAt(tip);

        task.updateFor(player);
        assertTrue(lastActionBar(player).startsWith("World: "), "the readout honestly labels its world-level scope");
    }

    @Test
    @DisplayName("looking at an UNtracked block shows nothing")
    void untrackedShowsNothing() {
        buildLoadedStructure();
        PlayerMock player = server.addPlayer("Viewer");
        ActionbarArbiter arbiter = arbiter();
        TestTask task = task(arbiter, true);
        // Aim at a block that is not in any graph.
        task.lookAt(world.getBlockAt(100, 64, 100));

        task.updateFor(player);
        assertNull(lastActionBar(player), "no readout when not aimed at a tracked block");
    }

    @Test
    @DisplayName("looking at nothing and standing on nothing tracked shows nothing")
    void nullFocusShowsNothing() {
        buildLoadedStructure();
        PlayerMock player = server.addPlayer("Viewer");
        // Far from the structure: the block below the feet is not tracked.
        player.teleport(new Location(world, 500.5, 65.0, 500.5));
        ActionbarArbiter arbiter = arbiter();
        TestTask task = task(arbiter, true);
        task.lookAt(null); // looking at nothing → standing-on, which is untracked here

        task.updateFor(player);
        assertNull(lastActionBar(player));
    }

    @Test
    @DisplayName("a critical warning the SAME tick suppresses the summary (arbiter wins)")
    void criticalWarningSuppressesSummary() {
        Block tip = buildLoadedStructure();
        PlayerMock player = server.addPlayer("Viewer");
        ActionbarArbiter arbiter = arbiter();
        TestTask task = task(arbiter, true);
        task.lookAt(tip);

        // A higher-priority warning fires first this tick.
        assertTrue(arbiter.send(player, Priority.CRITICAL_WARNING, Component.text("WARN")));

        // The summary pass on the same tick must NOT overwrite it.
        task.updateFor(player);
        assertEquals("WARN", lastActionBar(player), "the warning still owns the action bar this tick");
    }

    @Test
    @DisplayName("on the NEXT tick (no warning) the summary shows again")
    void summaryReturnsNextTick() {
        Block tip = buildLoadedStructure();
        PlayerMock player = server.addPlayer("Viewer");
        ActionbarArbiter arbiter = arbiter();
        TestTask task = task(arbiter, true);
        task.lookAt(tip);

        arbiter.send(player, Priority.CRITICAL_WARNING, Component.text("WARN"));
        task.updateFor(player); // suppressed this tick

        tick.incrementAndGet(); // next tick: warning no longer holds
        task.updateFor(player);
        assertTrue(lastActionBar(player).startsWith("World: "), "summary returns once the warning's tick passes");
    }

    @Test
    @DisplayName("disabled: the loop never schedules and never sends")
    void disabledSendsNothing() {
        Block tip = buildLoadedStructure();
        PlayerMock player = server.addPlayer("Viewer");
        ActionbarArbiter arbiter = arbiter();
        TestTask task = task(arbiter, false);
        task.lookAt(tip);

        // start() must be a no-op when disabled (no scheduled task to cancel).
        task.start(10L);

        // Even if run() were invoked, it would produce a readout — but disabled means
        // it is never scheduled. Assert the schedule is empty and a manual run still
        // works only because we bypass the scheduler; the gate is start(), tested here.
        assertEquals(0, server.getScheduler().getActiveWorkers().size(), "disabled task scheduled no workers");
        assertNull(lastActionBar(player), "nothing sent while disabled and unscheduled");
    }

    @Test
    @DisplayName("enabled: start() schedules a repeating worker")
    void enabledSchedules() {
        buildLoadedStructure();
        ActionbarArbiter arbiter = arbiter();
        TestTask task = task(arbiter, true);
        task.start(10L);
        assertFalse(server.getScheduler().getPendingTasks().isEmpty(), "enabled task scheduled a repeating worker");
        task.stop(); // exercise stop() on a scheduled task
    }

    @Test
    @DisplayName("stop() on a never-scheduled (disabled) task is a quiet no-op")
    void stopWhenUnscheduled() {
        ActionbarArbiter arbiter = arbiter();
        TestTask task = task(arbiter, false);
        // No exception even though start() never scheduled it.
        task.stop();
    }

    @Test
    @DisplayName("run() updates every online player's readout (the per-player loop)")
    void runUpdatesAllPlayers() {
        Block tip = buildLoadedStructure();
        PlayerMock a = server.addPlayer("A");
        PlayerMock b = server.addPlayer("B");
        ActionbarArbiter arbiter = arbiter();
        TestTask task = task(arbiter, true);
        task.lookAt(tip);

        task.run();

        assertTrue(lastActionBar(a).startsWith("World: "), "player A got a readout");
        assertTrue(lastActionBar(b).startsWith("World: "), "player B got a readout");
    }

    @Test
    @DisplayName("a tracked-but-unloaded structure (ground only) shows nothing — assessedNodes == 0")
    void groundOnlyShowsNothing() {
        // A graph with only a ground anchor: the block is tracked, but there are no
        // load-bearing nodes to grade, so the readout must stay silent.
        manager.getOrCreateGraph(world).addGroundBlock(new NodePos(5, 0, 5));
        manager.markDirty(world);
        Block groundBlock = world.getBlockAt(5, 0, 5);
        assertTrue(manager.isTracked(groundBlock), "the ground block is tracked");

        PlayerMock player = server.addPlayer("Viewer");
        ActionbarArbiter arbiter = arbiter();
        TestTask task = task(arbiter, true);
        task.lookAt(groundBlock);

        task.updateFor(player);
        assertNull(lastActionBar(player), "no readout when nothing load-bearing is tracked");
    }

    @Test
    @DisplayName("focusBlock falls back to the block under the player's feet when looking at nothing")
    void focusUsesStandingOnBlock() {
        // Player looks at nothing (targetBlock → null) but stands on a tracked
        // block, so focusBlock's standing-on fallback must find it. This drives the
        // REAL focusBlock selection logic via the targetBlock seam.
        StructureGraph graph = manager.getOrCreateGraph(world);
        graph.addGroundBlock(new NodePos(3, 63, 3));
        graph.addNode(new NodePos(3, 64, 3), new MaterialSpec(20.0, 50.0), false);
        manager.markDirty(world);
        world.getBlockAt(3, 64, 3).setType(Material.STONE);

        PlayerMock player = server.addPlayer("Stander");
        // Feet at y=65 → block beneath is y=64 (the tracked node).
        player.teleport(new Location(world, 3.5, 65.0, 3.5, 0f, -90f));

        ActionbarArbiter arbiter = arbiter();
        TestTask task = task(arbiter, true);
        task.lookAt(null); // looking at nothing → standing-on fallback

        task.updateFor(player);
        assertTrue(lastActionBar(player).startsWith("World: "), "standing on a tracked block shows the readout");
    }

    @Test
    @DisplayName("render() produces the right text and the bar colour tracks severity (green/yellow/red)")
    void renderTextAndColour() {
        // Text shape across the three colour buckets.
        assertEquals("World: ░░░░░░ 0% avg | Peak: 0%", plain(StressSummaryTask.render(report(0.0, 0.0))));
        assertEquals("World: ████░░ 65% avg | Peak: 70%", plain(StressSummaryTask.render(report(0.65, 0.70))));
        assertEquals("World: ██████ 95% avg | Peak: 99%", plain(StressSummaryTask.render(report(0.95, 0.99))));

        // The bar (second child) is coloured by the AVERAGE: green < 60 ≤ yellow < 90 ≤ red.
        assertEquals(NamedTextColor.GREEN, barColourOf(report(0.30, 0.30)), "30% avg → green");
        assertEquals(NamedTextColor.YELLOW, barColourOf(report(0.60, 0.60)), "60% avg → yellow (boundary)");
        assertEquals(NamedTextColor.YELLOW, barColourOf(report(0.89, 0.89)), "89% avg → still yellow");
        assertEquals(NamedTextColor.RED, barColourOf(report(0.90, 0.90)), "90% avg → red (boundary)");
    }

    /** The colour of the bar glyph child component for a report (the second child of render()). */
    private static TextColor barColourOf(StructureReport report) {
        return StressSummaryTask.render(report).children().get(1).color();
    }

    /** A synthetic report with the given avg/peak stress fractions. */
    private static StructureReport report(double avg, double peak) {
        return new StructureReport(StructureGrade.C, peak, avg, 0, 4);
    }
}
