package dev.gesp.structural.minecraft.visual;

import dev.gesp.structural.assess.StructureReport;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.perf.TaskTimings;
import dev.gesp.structural.minecraft.visual.ActionbarArbiter.Priority;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Shows each player a live structural-stress readout in the action bar.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                      STRESS SUMMARY TASK                           │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  While you LOOK AT (or stand on) a tracked structure, a little bar  │
 *   │  rides above your hotbar:                                           │
 *   │                                                                     │
 *   │     World: ██░░░░ 34% avg | Peak: 78%                              │
 *   │                                                                     │
 *   │  The bar fills with the world's average stress; the number after   │
 *   │  it is that same average, and "Peak" is the single most-stressed    │
 *   │  block in the world.                                               │
 *   │                                                                     │
 *   │  CAVEAT (v1): the numbers are WORLD-level, not for the one          │
 *   │  structure you're looking at — strux's revision-cached assessment   │
 *   │  is per world. We label the readout "World:" so it never lies about │
 *   │  what it measures. A true per-structure report would be a core      │
 *   │  change, deferred.                                                  │
 *   │                                                                     │
 *   │  Routed through the ActionbarArbiter so a "⚠ CRITICAL STRESS"       │
 *   │  placement warning on the same tick wins — the summary suppresses   │
 *   │  itself rather than flickering against it.                         │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>Off by default ({@code effects.stress-summary-enabled}) so existing servers
 * are not surprised by a new always-on HUD element.
 */
public class StressSummaryTask extends BukkitRunnable {

    /** How wide the bar glyph is. */
    static final int BAR_CELLS = 6;

    /** How far the look-at ray reaches, in blocks (mirrors {@code /strux} reach). */
    private static final int REACH = 100;

    private final StructureManager structureManager;
    private final Plugin plugin;
    private final ActionbarArbiter arbiter;
    private final TaskTimings taskTimings;
    private final boolean enabled;

    public StressSummaryTask(
            StructureManager structureManager,
            Plugin plugin,
            ActionbarArbiter arbiter,
            TaskTimings taskTimings,
            boolean enabled) {
        this.structureManager = structureManager;
        this.plugin = plugin;
        this.arbiter = arbiter;
        this.taskTimings = taskTimings;
        this.enabled = enabled;
    }

    /**
     * Start the per-player summary loop.
     *
     * @param intervalTicks how often to refresh (e.g. 10 ticks = half a second)
     */
    public void start(long intervalTicks) {
        if (!enabled) {
            return; // disabled: never schedule, zero cost
        }
        this.runTaskTimer(plugin, 20L, Math.max(1L, intervalTicks));
    }

    @Override
    public void run() {
        long start = System.nanoTime();
        int work = 0;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            work++;
            updateFor(player);
        }
        taskTimings.record(TaskTimings.STRESS_SUMMARY, System.nanoTime() - start, work);
    }

    /**
     * Refresh one player's action bar: if they are looking at (or standing on) a
     * tracked block, build the readout and offer it to the arbiter. The arbiter
     * drops it when a higher-priority warning already won this tick.
     */
    void updateFor(Player player) {
        // Don't even build the message if a warning already took this tick.
        if (arbiter.isSuppressed(player, Priority.SUMMARY)) {
            return;
        }
        Block block = focusBlock(player);
        if (block == null || !structureManager.isTracked(block)) {
            return; // not aimed at / standing on a tracked structure — nothing to show
        }
        World world = block.getWorld();
        StructureReport report = structureManager.assessWorld(world);
        if (report.assessedNodes() == 0) {
            return; // tracked block exists but nothing load-bearing to grade
        }
        arbiter.send(player, Priority.SUMMARY, render(report));
    }

    /**
     * Build the readout component for a report. Package-private so a test can
     * assert on its plain-text content directly.
     */
    static Component render(StructureReport report) {
        int avg = report.avgPercent();
        int peak = report.peakPercent();
        String bar = StressBar.bar(avg / 100.0, BAR_CELLS);
        return Component.text()
                .append(Component.text("World: ", NamedTextColor.GRAY, TextDecoration.BOLD))
                .append(Component.text(bar, barColor(avg)))
                .append(Component.text(" " + avg + "% avg", NamedTextColor.WHITE))
                .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                .append(Component.text("Peak: ", NamedTextColor.GRAY))
                .append(Component.text(peak + "%", barColor(peak)))
                .build();
    }

    /** Tint the bar/number by how stressed it is: green → yellow → red. */
    private static NamedTextColor barColor(int percent) {
        if (percent >= 90) {
            return NamedTextColor.RED;
        }
        if (percent >= 60) {
            return NamedTextColor.YELLOW;
        }
        return NamedTextColor.GREEN;
    }

    /**
     * The block a player is "focused on": the block they are looking at within
     * reach, or — when looking at nothing — the block they are standing on. Pure
     * selection logic, package-private so a test can drive it through the
     * {@link #targetBlock} seam (MockBukkit cannot raytrace).
     */
    Block focusBlock(Player player) {
        Block target = targetBlock(player);
        if (target != null) {
            return target;
        }
        // Standing on: the block directly beneath the player's feet.
        Location feet = player.getLocation();
        return feet.getWorld() == null
                ? null
                : feet.getWorld().getBlockAt(feet.getBlockX(), feet.getBlockY() - 1, feet.getBlockZ());
    }

    /**
     * The block the player is looking at within reach, or null if they aren't
     * aiming at one. Thin wrapper over Bukkit raytracing, isolated and overridable
     * because MockBukkit does not implement {@code getTargetBlockExact}.
     */
    Block targetBlock(Player player) {
        return player.getTargetBlockExact(REACH);
    }

    /** Stop the task (no-op if it was never scheduled because it is disabled). */
    public void stop() {
        try {
            this.cancel();
        } catch (IllegalStateException ignored) {
            // never scheduled (disabled) or already cancelled
        }
    }
}
