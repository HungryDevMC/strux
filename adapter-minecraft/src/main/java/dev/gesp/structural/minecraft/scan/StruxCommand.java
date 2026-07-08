package dev.gesp.structural.minecraft.scan;

import dev.gesp.structural.assess.StructureGrade;
import dev.gesp.structural.assess.StructureReport;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.metrics.StruxMetrics;
import dev.gesp.structural.minecraft.hook.EconomyCharges;
import dev.gesp.structural.minecraft.item.ReinforcementItem;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.perf.PerfTracker;
import dev.gesp.structural.minecraft.perf.TaskTimings;
import dev.gesp.structural.minecraft.recording.MetricsOverlay;
import dev.gesp.structural.minecraft.recording.RecordingCommand;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * {@code /strux} — select a region and retroactively give an existing build
 * structural integrity.
 *
 * <pre>
 *   /strux wand   get the scanner (left-click = corner 1, right-click = corner 2)
 *   /strux pos1   set corner 1 to the block you're looking at
 *   /strux pos2   set corner 2
 *   /strux scan   register everything in the selection
 * </pre>
 */
public class StruxCommand implements CommandExecutor, Listener {

    private static final int REACH = 100;

    private final Plugin plugin;
    private final RegionScanner scanner;
    private final StructureManager structureManager;
    private final EconomyCharges economy;
    private final ReinforcementItem reinforcementItem;
    private final double reinforceCommandAdd;
    private final double reinforceMax;
    private final double reinforceCommandCost;
    private final double repairCost;
    private final NamespacedKey wandKey;
    private final DemoScenarios demoScenarios;
    private RecordingCommand recordingCommand;
    private MetricsOverlay metricsOverlay;
    private TaskTimings taskTimings;

    private final Map<UUID, Location> corner1 = new HashMap<>();
    private final Map<UUID, Location> corner2 = new HashMap<>();

    public StruxCommand(
            Plugin plugin,
            RegionScanner scanner,
            StructureManager structureManager,
            EconomyCharges economy,
            ReinforcementItem reinforcementItem,
            double reinforceCommandAdd,
            double reinforceMax,
            double reinforceCommandCost,
            double repairCost) {
        this.plugin = plugin;
        this.scanner = scanner;
        this.structureManager = structureManager;
        this.economy = economy;
        this.reinforcementItem = reinforcementItem;
        this.reinforceCommandAdd = reinforceCommandAdd;
        this.reinforceMax = reinforceMax;
        this.reinforceCommandCost = reinforceCommandCost;
        this.repairCost = repairCost;
        this.wandKey = new NamespacedKey(plugin, "strux_scanner");
        this.demoScenarios = new DemoScenarios(plugin, scanner, reinforcementItem);
    }

    /**
     * Set the recording command handler for /strux record subcommands.
     */
    public void setRecordingCommand(RecordingCommand recordingCommand) {
        this.recordingCommand = recordingCommand;
    }

    /**
     * Set the metrics overlay for /strux metrics toggle.
     */
    public void setMetricsOverlay(MetricsOverlay metricsOverlay) {
        this.metricsOverlay = metricsOverlay;
    }

    /**
     * Set the per-task timing registry so {@code /strux perf} can print the
     * per-task table below the solver section.
     */
    public void setTaskTimings(TaskTimings taskTimings) {
        this.taskTimings = taskTimings;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        // Recording is operator tooling (verify/replay/list run from console, RCON,
        // or CI), so it is exempt from the player gate below. Everything else needs
        // a body in the world.
        if (sub.equals("record")) {
            record(sender, args);
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is for players.");
            return true;
        }
        switch (sub) {
            case "wand" -> {
                player.getInventory().addItem(createWand());
                player.sendMessage(
                        "§bReceived the Strux Scanner §7— left-click & right-click two corners, then §e/strux scan");
            }
            case "pos1" -> setCorner(player, true);
            case "pos2" -> setCorner(player, false);
            case "scan" -> scan(player);
            case "reinforce" -> reinforce(player);
            case "repair" -> repair(player);
            case "grade" -> grade(player);
            case "predict" -> predict(player);
            case "perf" -> perf(player);
            case "beam" -> beam(player, args);
            case "demo" -> demo(player, args);
            case "metrics" -> metrics(player);
            default -> help(player);
        }
        return true;
    }

    private void help(Player p) {
        p.sendMessage("§6Strux:");
        p.sendMessage("§e/strux wand §7- scanner (left/right click two corners)");
        p.sendMessage("§e/strux pos1 §7/ §epos2 §7- set corners to the block you look at");
        p.sendMessage("§e/strux scan §7- give everything in the selection structural integrity");
        p.sendMessage("§e/strux reinforce §7- reinforce the block you're looking at");
        p.sendMessage("§e/strux repair §7- repair (clear damage on) the block you're looking at");
        p.sendMessage("§e/strux grade §7- structural grade of this world's builds");
        p.sendMessage("§e/strux predict §7- how much collapses if you break the block you're looking at");
        p.sendMessage("§e/strux perf §7- performance: solve time, tracked blocks, caps");
        if (p.hasPermission("structuralintegrity.admin")) {
            p.sendMessage("§e/strux beam [n] §7- get Support Beam items");
            p.sendMessage("§e/strux demo [1-4|all] §7- build trailer demo scenarios");
        }
        if (recordingCommand != null) {
            p.sendMessage("§e/strux record §7- event recording (stop/start/list/replay/export/clear)");
        }
        if (metricsOverlay != null) {
            p.sendMessage("§e/strux metrics §7- toggle metrics overlay (boss bar)");
        }
        if (worldEditPresent()) {
            p.sendMessage("§7(or just make a WorldEdit selection with §e//wand§7 and run §e/strux scan§7)");
        }
    }

    private boolean worldEditPresent() {
        return plugin.getServer().getPluginManager().getPlugin("WorldEdit") != null;
    }

    private void record(CommandSender sender, String[] args) {
        if (recordingCommand == null) {
            sender.sendMessage("§cRecording not available.");
            return;
        }
        // Pass remaining args to RecordingCommand (skip "record" itself)
        String[] recordArgs = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];
        recordingCommand.handle(sender, recordArgs);
    }

    private void metrics(Player player) {
        if (metricsOverlay == null) {
            player.sendMessage("§cMetrics overlay not available.");
            return;
        }
        boolean active = metricsOverlay.toggle(player);
        if (active) {
            player.sendMessage("§aMetrics overlay enabled.");
        } else {
            player.sendMessage("§7Metrics overlay disabled.");
        }
    }

    private void setCorner(Player p, boolean first, Location loc) {
        (first ? corner1 : corner2).put(p.getUniqueId(), loc);
        p.sendMessage("§aCorner " + (first ? "1" : "2") + " §7set to §f" + loc.getBlockX() + ", " + loc.getBlockY()
                + ", " + loc.getBlockZ());
    }

    private void setCorner(Player p, boolean first) {
        Block target = p.getTargetBlockExact(REACH);
        if (target == null) {
            p.sendMessage("§cLook at a block to set the corner.");
            return;
        }
        setCorner(p, first, target.getLocation());
    }

    private void scan(Player player) {
        World world;
        int minX, minY, minZ, maxX, maxY, maxZ;

        Location a = corner1.get(player.getUniqueId());
        Location b = corner2.get(player.getUniqueId());
        if (a != null && b != null) {
            // Strux selection takes priority when explicitly set. Location.getWorld() THROWS
            // IllegalArgumentException for an unloaded world (it holds the world weakly), so a
            // multiworld server that unloaded the corners' world must fail gracefully, not crash.
            World aWorld;
            World bWorld;
            try {
                aWorld = a.getWorld();
                bWorld = b.getWorld();
            } catch (IllegalArgumentException unloaded) {
                player.sendMessage("§cYour selection's world is no longer loaded — set the corners again.");
                return;
            }
            if (aWorld == null || !aWorld.equals(bWorld)) {
                player.sendMessage("§cBoth corners must be in the same world.");
                return;
            }
            world = aWorld;
            minX = Math.min(a.getBlockX(), b.getBlockX());
            minY = Math.min(a.getBlockY(), b.getBlockY());
            minZ = Math.min(a.getBlockZ(), b.getBlockZ());
            maxX = Math.max(a.getBlockX(), b.getBlockX());
            maxY = Math.max(a.getBlockY(), b.getBlockY());
            maxZ = Math.max(a.getBlockZ(), b.getBlockZ());
        } else if (worldEditPresent()) {
            // Fall back to the player's WorldEdit selection.
            int[] bounds = WorldEditHook.selectionBounds(player);
            if (bounds == null) {
                player.sendMessage("§cNo selection — set Strux corners (§e/strux wand§c) "
                        + "or make a WorldEdit selection (§e//wand§c).");
                return;
            }
            world = player.getWorld();
            minX = bounds[0];
            minY = bounds[1];
            minZ = bounds[2];
            maxX = bounds[3];
            maxY = bounds[4];
            maxZ = bounds[5];
            player.sendMessage("§7Using your WorldEdit selection.");
        } else {
            player.sendMessage("§cSet both corners first (§e/strux wand§c, or §e/strux pos1§c / §epos2§c).");
            return;
        }

        RegionScanner.Result r = scanner.scan(world, minX, minY, minZ, maxX, maxY, maxZ);
        if (r.tooLarge()) {
            player.sendMessage("§cSelection too large (" + r.volume() + " cells, max " + RegionScanner.MAX_VOLUME
                    + "). Pick a smaller region.");
            return;
        }
        player.sendMessage("§aScanned §e" + r.registered() + "§a blocks into the structure " + "§7(" + r.groundAnchors()
                + " ground anchors).");
        if (r.registered() == 0) {
            player.sendMessage("§7Nothing new to register here (empty or already tracked).");
            return;
        }
        player.sendMessage("§7Grade: " + gradeLabel(r.grade()) + " §7— peak §f" + r.peakPercent() + "%§7, avg §f"
                + r.avgPercent() + "%§7 stress.");
        if (r.overstressed() > 0) {
            player.sendMessage("§e⚠ " + r.overstressed() + " block(s) are already over-stressed — "
                    + "they'll come down if disturbed. Try §e/strux reinforce§e.");
        } else {
            player.sendMessage("§aStructure is sound. Break a support or blow it up to test the cascade.");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  REINFORCE / REPAIR / GRADE / BEAM
    // ─────────────────────────────────────────────────────────────────────

    private void reinforce(Player player) {
        if (!player.hasPermission("structuralintegrity.reinforce")) {
            player.sendMessage("§cYou don't have permission to reinforce.");
            return;
        }
        Block target = player.getTargetBlockExact(REACH);
        if (target == null) {
            player.sendMessage("§cLook at a block to reinforce it.");
            return;
        }
        switch (structureManager.canReinforce(target, reinforceMax)) {
            case NOT_TRACKED -> player.sendMessage(
                    "§cThat block isn't tracked. Run §e/strux scan §con the build first.");
            case IS_GROUND -> player.sendMessage("§7Foundation blocks are already immovable.");
            case AT_MAX -> player.sendMessage("§eThat block is already fully reinforced.");
            case OK -> {
                if (!economy.charge(player, reinforceCommandCost, "reinforce a block")) {
                    return;
                }
                StructureManager.Reinforced result =
                        structureManager.reinforce(target, reinforceCommandAdd, reinforceMax);
                int pct = (int) Math.round((result.multiplier() - 1.0) * 100);
                player.sendMessage("§aReinforced §7→ §a+" + pct + "%§7 load capacity.");
            }
        }
    }

    private void repair(Player player) {
        if (!player.hasPermission("structuralintegrity.repair")) {
            player.sendMessage("§cYou don't have permission to repair.");
            return;
        }
        Block target = player.getTargetBlockExact(REACH);
        if (target == null) {
            player.sendMessage("§cLook at a block to repair it.");
            return;
        }
        if (!structureManager.isTracked(target)) {
            player.sendMessage("§cThat block isn't tracked. Run §e/strux scan §cfirst.");
            return;
        }
        // Charge only when there's actually damage to repair.
        if (!structureManager.isRepairable(target)) {
            player.sendMessage("§7That block has no damage to repair.");
            return;
        }
        if (!economy.charge(player, repairCost, "repair a block")) {
            return;
        }
        structureManager.repair(target);
        player.sendMessage("§aRepaired §7— damage cleared, full capacity restored.");
    }

    private void grade(Player player) {
        StructureReport report = structureManager.assessWorld(player.getWorld());
        if (report.assessedNodes() == 0) {
            player.sendMessage("§7No tracked structures in this world yet. Try §e/strux scan§7.");
            return;
        }
        player.sendMessage("§6Structural grade: " + gradeLabel(report.grade()));
        player.sendMessage("§7Peak stress §f" + report.peakPercent() + "%§7, average §f" + report.avgPercent()
                + "%§7 across §f" + report.assessedNodes() + "§7 blocks.");
        if (report.overloadedCount() > 0) {
            player.sendMessage("§c" + report.overloadedCount() + " block(s) overloaded.");
        }
    }

    private void predict(Player player) {
        Block target = player.getTargetBlockExact(REACH);
        if (target == null) {
            player.sendMessage("§cLook at a block to predict its collapse.");
            return;
        }
        int n = structureManager.predictCollapse(target);
        if (n < 0) {
            player.sendMessage("§cThat block isn't tracked. Run §e/strux scan §cfirst.");
            return;
        }
        if (n == 0) {
            player.sendMessage("§7Breaking this drops nothing — it isn't holding anything up.");
            return;
        }
        // Lookup, not simulation — the collapse atlas already knows the answer.
        String label = n >= 20 ? "§c§lCRITICAL SUPPORT" : (n >= 5 ? "§6key support" : "§eminor support");
        player.sendMessage("§7Breaking this → §c" + n + "§7 block(s) lose support  " + label);
    }

    private void perf(Player player) {
        PerfTracker perf = structureManager.getPerf();
        StruxMetrics m = structureManager.getMetrics();
        int total = structureManager.totalTrackedBlocks();
        StructureGraph here = structureManager.getGraph(player.getWorld());
        int hereCount = here != null ? here.size() : 0;
        int maxCascade = structureManager.getConfig().getMaxCascadeSteps();
        int maxPerTick = plugin.getConfig().getInt("effects.max-collapses-per-tick", 25);

        player.sendMessage("§6Strux performance");
        player.sendMessage(String.format("§7Tracked blocks: §f%,d §7(this world: §f%,d§7)", total, hereCount));
        if (perf.sampleCount() > 0) {
            player.sendMessage(String.format(
                    "§7Solve time: §a%.3f ms §7avg §8(last %d) §7| worst §e%.3f ms §8(%,d blocks)",
                    perf.averageMillis(), perf.sampleCount(), perf.worstMillis(), perf.worstBlocks()));
        } else {
            player.sendMessage("§7Solve time: §8no solves sampled yet — break or place a tracked block.");
        }
        player.sendMessage(String.format(
                "§7Engine work: §f%,d §7solves, §f%,d §7node visits, §f%,d §7blocks collapsed",
                m.solveInvocations, m.nodeVisits, m.blocksRemoved));
        player.sendMessage(String.format(
                "§7Safety caps: §f%d §7blocks/cascade, §f%d §7collapses/tick §8(load spread across ticks)",
                maxCascade, maxPerTick));

        perTask(player);
    }

    /**
     * Print one line per repeating task that has recorded at least one pass:
     * average ms, worst ms, pass count, and average work per pass. Tasks that
     * never ran are omitted, so an admin sees exactly where the tick is spent.
     */
    private void perTask(Player player) {
        if (taskTimings == null) {
            return;
        }
        Map<String, PerfTracker> tasks = taskTimings.snapshot();
        if (tasks.isEmpty()) {
            return; // nothing has ticked yet — no table to show
        }
        player.sendMessage("§6Per-task (avg / worst ms over last N passes)");
        for (Map.Entry<String, PerfTracker> entry : tasks.entrySet()) {
            PerfTracker t = entry.getValue();
            player.sendMessage(String.format(
                    "§7%s: §a%.3f ms §7avg §7| worst §e%.3f ms §8(%,d passes, %,.0f work/pass)",
                    entry.getKey(), t.averageMillis(), t.worstMillis(), t.sampleCount(), t.averageWork()));
        }
    }

    /**
     * Build demo scenarios for testing and trailer recording.
     *
     * <pre>
     *   /strux demo       - show usage
     *   /strux demo all   - build all 5 scenarios side by side
     *   /strux demo 1     - tower cascade
     *   /strux demo 2     - TNT wall breach
     *   /strux demo 3     - fire weakening
     *   /strux demo 4     - stress overlay
     *   /strux demo 5     - reinforcement counterplay
     * </pre>
     */
    private void demo(Player player, String[] args) {
        if (!player.hasPermission("structuralintegrity.admin")) {
            player.sendMessage("§cYou don't have permission for that.");
            return;
        }
        String scenario = args.length > 1 ? args[1].toLowerCase() : "";
        switch (scenario) {
            case "all" -> demoScenarios.buildAll(player);
            case "1", "tower" -> demoScenarios.buildScenario1Tower(player);
            case "2", "wall", "tnt" -> demoScenarios.buildScenario2Wall(player);
            case "3", "fire", "heat" -> demoScenarios.buildScenario3Fire(player);
            case "4", "stress", "overlay" -> demoScenarios.buildScenario4Stress(player);
            default -> {
                player.sendMessage("§6Demo scenarios §7(for trailer/testing):");
                player.sendMessage("§e/strux demo all §7- build all 4 scenarios side by side");
                player.sendMessage("§e/strux demo 1 §7- tower cascade (the hook)");
                player.sendMessage("§e/strux demo 2 §7- TNT wall breach");
                player.sendMessage("§e/strux demo 3 §7- heat damage (fire → cracks)");
                player.sendMessage("§e/strux demo 4 §7- stress overlay (stack → red)");
                player.sendMessage("§8Run on flat ground. Each gives you the items you need.");
            }
        }
    }

    private void beam(Player player, String[] args) {
        if (!player.hasPermission("structuralintegrity.admin")) {
            player.sendMessage("§cYou don't have permission for that.");
            return;
        }
        int amount = 1;
        if (args.length > 1) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[1])));
            } catch (NumberFormatException ignored) {
                player.sendMessage("§cUsage: /strux beam [1-64]");
                return;
            }
        }
        player.getInventory().addItem(reinforcementItem.create(amount));
        player.sendMessage(
                "§bReceived §e" + amount + "§b Support Beam(s) §7— right-click a structure block to reinforce.");
    }

    /** Colorized letter grade for chat. */
    private String gradeLabel(StructureGrade grade) {
        String color =
                switch (grade) {
                    case S -> "§b";
                    case A -> "§a";
                    case B -> "§2";
                    case C -> "§e";
                    case F -> "§c";
                };
        return color + "§l" + grade.name();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SCANNER WAND
    // ─────────────────────────────────────────────────────────────────────

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!isWand(player.getInventory().getItemInMainHand())) {
            return;
        }
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        event.setCancelled(true);
        Action action = event.getAction();
        if (action == Action.LEFT_CLICK_BLOCK) {
            setCorner(player, true, clicked.getLocation());
        } else if (action == Action.RIGHT_CLICK_BLOCK) {
            setCorner(player, false, clicked.getLocation());
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (isWand(event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true); // wand selects, never breaks
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Wand corner selections are per-player scratch state. Drop them when the player
        // leaves; otherwise every player who ever set a corner leaves two Location entries
        // (each holding a world reference) for the plugin's lifetime — an unbounded leak.
        UUID id = event.getPlayer().getUniqueId();
        corner1.remove(id);
        corner2.remove(id);
    }

    @SuppressWarnings("deprecation")
    private ItemStack createWand() {
        ItemStack item = new ItemStack(Material.GOLDEN_AXE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§bStrux Scanner");
        meta.setLore(List.of(
                "§7Left-click §fa block — corner 1", "§7Right-click §fa block — corner 2", "§7Then §e/strux scan"));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isWand(ItemStack item) {
        return item != null
                && item.getType() == Material.GOLDEN_AXE
                && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }
}
