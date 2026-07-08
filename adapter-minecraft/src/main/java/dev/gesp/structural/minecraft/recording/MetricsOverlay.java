package dev.gesp.structural.minecraft.recording;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.manager.StructureManager;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Real-time metrics display via boss bar.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                      METRICS OVERLAY                               │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Displays real-time structure metrics per-player:                  │
 *   │                                                                     │
 *   │  • Tracked blocks: total blocks in structure graph                 │
 *   │  • Recording: whether event recording is active                    │
 *   │                                                                     │
 *   │  Players can toggle the overlay with /strux metrics                │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class MetricsOverlay extends BukkitRunnable {

    private final Plugin plugin;
    private final StructureManager structureManager;
    private final MinecraftEventRecorder recorder;
    private final int updateIntervalTicks;
    private final boolean enabled;

    private final Set<UUID> activeViewers = new HashSet<>();
    private final BossBar bossBar;

    public MetricsOverlay(
            Plugin plugin,
            StructureManager structureManager,
            MinecraftEventRecorder recorder,
            int updateIntervalTicks,
            boolean enabled) {
        this.plugin = plugin;
        this.structureManager = structureManager;
        this.recorder = recorder;
        this.updateIntervalTicks = Math.max(1, updateIntervalTicks);
        this.enabled = enabled;
        this.bossBar =
                BossBar.bossBar(Component.text("Strux Metrics"), 0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
    }

    /** Start the periodic update task. */
    public void start() {
        if (enabled) {
            runTaskTimer(plugin, updateIntervalTicks, updateIntervalTicks);
        }
    }

    /** Stop the update task and hide the overlay for all viewers. */
    public void stop() {
        try {
            cancel();
        } catch (IllegalStateException ignored) {
            // never started or already cancelled
        }
        for (UUID uuid : activeViewers) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                player.hideBossBar(bossBar);
            }
        }
        activeViewers.clear();
    }

    /** Toggle the overlay for a player. */
    public boolean toggle(Player player) {
        UUID uuid = player.getUniqueId();
        if (activeViewers.contains(uuid)) {
            activeViewers.remove(uuid);
            player.hideBossBar(bossBar);
            return false;
        } else {
            activeViewers.add(uuid);
            player.showBossBar(bossBar);
            return true;
        }
    }

    /** Check if a player has the overlay active. */
    public boolean isActive(Player player) {
        return activeViewers.contains(player.getUniqueId());
    }

    @Override
    public void run() {
        if (activeViewers.isEmpty()) {
            return;
        }

        // Gather metrics from the first online viewer's world
        int totalBlocks = 0;
        for (UUID uuid : activeViewers) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && player.isOnline()) {
                World world = player.getWorld();
                StructureGraph graph = structureManager.getGraph(world);
                if (graph != null) {
                    totalBlocks = graph.size();
                    break;
                }
            }
        }

        // Build display text
        Component text = Component.text()
                .append(Component.text("Strux ", NamedTextColor.AQUA))
                .append(Component.text("| ", NamedTextColor.DARK_GRAY))
                .append(Component.text(totalBlocks, NamedTextColor.WHITE))
                .append(Component.text(" blocks ", NamedTextColor.GRAY))
                .append(Component.text("| ", NamedTextColor.DARK_GRAY))
                .append(
                        recorder.isRecording()
                                ? Component.text("REC", NamedTextColor.RED)
                                : Component.text("---", NamedTextColor.DARK_GRAY))
                .build();

        bossBar.name(text);

        // Progress bar shows a basic fraction (blocks / 10000 cap for display)
        float progress = Math.min(1f, totalBlocks / 10000f);
        bossBar.progress(progress);

        // Color based on block count
        if (totalBlocks >= 5000) {
            bossBar.color(BossBar.Color.RED);
        } else if (totalBlocks >= 1000) {
            bossBar.color(BossBar.Color.YELLOW);
        } else {
            bossBar.color(BossBar.Color.GREEN);
        }

        // Remove offline players
        activeViewers.removeIf(uuid -> plugin.getServer().getPlayer(uuid) == null);
    }
}
