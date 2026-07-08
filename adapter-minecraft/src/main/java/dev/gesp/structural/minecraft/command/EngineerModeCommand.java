package dev.gesp.structural.minecraft.command;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.hook.EconomyCharges;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Command to toggle engineer mode visualization.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                      ENGINEER MODE                                 │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Shows structural stress with particles ON TOP of blocks:          │
 *   │                                                                     │
 *   │       🟢 [D]       Particles appear on block surfaces              │
 *   │        ├──         Connection indicators at block faces            │
 *   │       🟡 [C]                                                        │
 *   │        │           Color indicates stress level:                   │
 *   │       🟠 [B]       • Green  = low stress                           │
 *   │        │           • Yellow = moderate                             │
 *   │       🔵 [A]       • Orange = high                                 │
 *   │        │           • Red    = critical                             │
 *   │      [GND]         • Cyan   = grounded                             │
 *   │                                                                     │
 *   │  Usage: /engineer (aliases: /stress, /loadpath)                    │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class EngineerModeCommand implements CommandExecutor {

    private final StructureManager structureManager;
    private final Plugin plugin;
    private final EconomyCharges economy;
    private final double cost;
    private final Set<UUID> enabledPlayers = new HashSet<>();
    private final Map<UUID, BukkitRunnable> playerTasks = new HashMap<>();

    /** Declared in plugin.yml (default true); enforced here so admins can revoke it. */
    public static final String USE_PERMISSION = "structuralintegrity.engineer";

    // Visualization settings - tuned for performance
    private static final long UPDATE_INTERVAL = 10L; // Every 0.5 seconds (was 0.25)
    private static final double VIEW_RADIUS = 24.0; // Blocks around player (was 32)
    private static final int MAX_BLOCKS_PER_UPDATE = 500; // Limit particles per tick (raised from 200)

    // Stress colors
    private static final Color COLOR_LOW = Color.fromRGB(0, 255, 0); // Green
    private static final Color COLOR_MEDIUM = Color.fromRGB(255, 255, 0); // Yellow
    private static final Color COLOR_HIGH = Color.fromRGB(255, 165, 0); // Orange
    private static final Color COLOR_CRITICAL = Color.fromRGB(255, 0, 0); // Red
    private static final Color COLOR_GROUND = Color.fromRGB(0, 200, 255); // Cyan for ground

    public EngineerModeCommand(StructureManager structureManager, Plugin plugin, EconomyCharges economy, double cost) {
        this.structureManager = structureManager;
        this.plugin = plugin;
        this.economy = economy;
        this.cost = cost;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        plugin.getLogger().info("Engineer command executed by " + sender.getName());

        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        // plugin.yml declares this permission (default true) but Bukkit only enforces it
        // when the command stanza names it; enforce it here so revoking it actually blocks
        // /engineer (and its /stress, /loadpath aliases) and the economy charge.
        if (!player.hasPermission(USE_PERMISSION)) {
            player.sendMessage("§cYou don't have permission to use engineer mode.");
            return true;
        }

        UUID playerId = player.getUniqueId();

        if (enabledPlayers.contains(playerId)) {
            // Disable engineer mode
            enabledPlayers.remove(playerId);
            BukkitRunnable task = playerTasks.remove(playerId);
            if (task != null) {
                task.cancel();
            }
            player.sendMessage("§7Engineer mode §cdisabled§7.");
            plugin.getLogger().info("Engineer mode disabled for " + player.getName());
        } else {
            // Enable engineer mode — charge the configured perk price (free if 0 / no Vault).
            if (!economy.charge(player, cost, "enable engineer mode")) {
                return true;
            }
            enabledPlayers.add(playerId);
            startVisualization(player);
            player.sendMessage("§7Engineer mode §aenabled§7. Showing load paths.");
            player.sendMessage("§8Particles on block tops. Color = stress level.");
            plugin.getLogger().info("Engineer mode enabled for " + player.getName());
        }

        return true;
    }

    /**
     * Programmatically turn engineer mode on or off for a player — idempotent,
     * so callers (e.g. the Siege build-mode designer) can force it on without
     * worrying about the current toggle state.
     */
    public void setEnabled(Player player, boolean enabled) {
        UUID id = player.getUniqueId();
        if (enabled) {
            if (enabledPlayers.add(id)) {
                startVisualization(player);
            }
        } else {
            if (enabledPlayers.remove(id)) {
                BukkitRunnable task = playerTasks.remove(id);
                if (task != null) {
                    task.cancel();
                }
            }
        }
    }

    /**
     * Start the load path visualization for a player.
     */
    private void startVisualization(Player player) {
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || !enabledPlayers.contains(player.getUniqueId())) {
                    this.cancel();
                    playerTasks.remove(player.getUniqueId());
                    // Also clear the enabled flag: a player who logs out while engineer mode
                    // is on would otherwise keep a stale enabledPlayers entry, so after relog
                    // setEnabled(true) no-ops (no particles), /engineer takes the disable
                    // branch, isEnabled() lies, and the set grows unboundedly.
                    enabledPlayers.remove(player.getUniqueId());
                    return;
                }

                visualizeLoadPaths(player);
            }
        };

        task.runTaskTimer(plugin, 0L, UPDATE_INTERVAL);
        playerTasks.put(player.getUniqueId(), task);
    }

    /**
     * Render load path particles for blocks near the player.
     * Optimized: no BFS, just stress colors on block tops.
     * Sorts by distance so closest blocks are always shown first.
     */
    private void visualizeLoadPaths(Player player) {
        World world = player.getWorld();
        StructureGraph graph = structureManager.getGraph(world);
        if (graph == null) {
            return;
        }

        Location playerLoc = player.getLocation();
        double radiusSq = VIEW_RADIUS * VIEW_RADIUS;

        // Collect all nodes within range with their distances
        record NodeWithDist(Node node, double distSq) {}
        List<NodeWithDist> candidates = new ArrayList<>();

        for (Node node : graph.getAllNodes()) {
            NodePos pos = node.pos();
            double dx = pos.x() + 0.5 - playerLoc.getX();
            double dy = pos.y() + 0.5 - playerLoc.getY();
            double dz = pos.z() + 0.5 - playerLoc.getZ();
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq <= radiusSq) {
                candidates.add(new NodeWithDist(node, distSq));
            }
        }

        // Sort by distance (closest first) so we always show the nearest blocks
        candidates.sort(Comparator.comparingDouble(NodeWithDist::distSq));

        // Draw particles for the closest blocks up to the limit
        int count = 0;
        for (NodeWithDist nwd : candidates) {
            if (count >= MAX_BLOCKS_PER_UPDATE) {
                break;
            }

            Node node = nwd.node();
            NodePos pos = node.pos();
            Color color = node.isGrounded() ? COLOR_GROUND : getStressColor(node.stressPercent());
            float size = node.isGrounded() ? 1.2f : 1.0f;

            // Calculate block center
            double bx = pos.x() + 0.5;
            double by = pos.y() + 0.5;
            double bz = pos.z() + 0.5;

            // Find which face is closest to the player (particle on facing face)
            double dx = playerLoc.getX() - bx;
            double dy = playerLoc.getY() + 1.5 - by; // eye height
            double dz = playerLoc.getZ() - bz;

            // Determine dominant axis (which face to show particle on)
            double ax = Math.abs(dx);
            double ay = Math.abs(dy);
            double az = Math.abs(dz);

            double px = bx, py = by, pz = bz;
            if (ax >= ay && ax >= az) {
                // X face (east/west)
                px = bx + (dx > 0 ? 0.52 : -0.52);
            } else if (ay >= ax && ay >= az) {
                // Y face (top/bottom)
                py = by + (dy > 0 ? 0.52 : -0.52);
            } else {
                // Z face (north/south)
                pz = bz + (dz > 0 ? 0.52 : -0.52);
            }

            Location faceLoc = new Location(world, px, py, pz);
            spawnDust(player, faceLoc, color, size, 1);

            count++;
        }
    }

    /**
     * Spawn dust particles visible only to a specific player.
     */
    private void spawnDust(Player player, Location loc, Color color, float size, int count) {
        Particle.DustOptions dust = new Particle.DustOptions(color, size);
        player.spawnParticle(Particle.DUST, loc, count, 0.02, 0.02, 0.02, 0, dust);
    }

    /**
     * Get color based on stress percentage.
     */
    private Color getStressColor(double stress) {
        if (stress >= 0.95) return COLOR_CRITICAL;
        if (stress >= 0.80) return COLOR_HIGH;
        if (stress >= 0.50) return COLOR_MEDIUM;
        return COLOR_LOW;
    }

    /**
     * Check if a player has engineer mode enabled.
     */
    public boolean isEnabled(Player player) {
        return enabledPlayers.contains(player.getUniqueId());
    }

    /**
     * Disable engineer mode for all players (on plugin disable).
     */
    public void disableAll() {
        for (BukkitRunnable task : playerTasks.values()) {
            task.cancel();
        }
        playerTasks.clear();
        enabledPlayers.clear();
    }
}
