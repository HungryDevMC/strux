package dev.gesp.structural.minecraft.scan;

import dev.gesp.structural.minecraft.StructuralIntegrityPlugin;
import dev.gesp.structural.minecraft.fire.FireScorchTask;
import dev.gesp.structural.minecraft.item.ReinforcementItem;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Pre-built demo scenarios for marketing/trailer recording.
 *
 * <p>Each scenario matches a shot from the trailer storyboard in STORE_LISTING.md:
 *
 * <ol>
 *   <li>Tower cascade — tall tower, knock out the base
 *   <li>TNT wall breach — curtain wall for explosion demo
 *   <li>Fire + heat — cobblestone wall with fire (radiant heat shows cracks)
 *   <li>Stress overlay — thin column to stack blocks on (shows green→red)
 * </ol>
 *
 * <p>All structures are built on bedrock foundations for proper grounding.
 */
public class DemoScenarios {

    private final Plugin plugin;
    private final RegionScanner scanner;
    private final ReinforcementItem reinforcementItem;

    public DemoScenarios(Plugin plugin, RegionScanner scanner, ReinforcementItem reinforcementItem) {
        this.plugin = plugin;
        this.scanner = scanner;
        this.reinforcementItem = reinforcementItem;
    }

    /** Build all scenarios in a row, spaced apart. */
    public void buildAll(Player player) {
        Location start = player.getLocation();
        BlockFace face = cardinalFacing(start.getYaw());

        // Build each scenario offset to the right
        int spacing = 12;
        int rx = -face.getModZ();
        int rz = face.getModX();

        player.teleport(start);
        buildScenario1Tower(player);

        player.teleport(start.clone().add(rx * spacing, 0, rz * spacing));
        buildScenario2Wall(player);

        player.teleport(start.clone().add(rx * spacing * 2, 0, rz * spacing * 2));
        buildScenario3Fire(player);

        player.teleport(start.clone().add(rx * spacing * 3, 0, rz * spacing * 3));
        buildScenario4Stress(player);

        player.teleport(start);
        player.sendMessage("§a4 demo scenarios built §7— walk right to see each one.");
        player.sendMessage("§7Use §e/strux demo 1-4 §7to rebuild individually.");
    }

    /**
     * Scenario 1: The Hook — tall tower that cascades when base is broken.
     */
    public void buildScenario1Tower(Player player) {
        World world = player.getWorld();
        BlockFace face = cardinalFacing(player.getLocation().getYaw());
        Block feet = player.getLocation().getBlock();

        int ox = feet.getX() + face.getModX() * 6;
        int oy = feet.getY();
        int oz = feet.getZ() + face.getModZ() * 6;

        clearArea(world, ox - 2, oy - 1, oz - 2, ox + 2, oy + 12, oz + 2);

        // Bedrock foundation for grounding
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                place(world, ox + dx, oy - 1, oz + dz, Material.BEDROCK);
            }
        }

        // 2x2 tower, 10 blocks tall
        int minX = ox, minZ = oz, maxX = ox + 1, maxZ = oz + 1;
        for (int h = 0; h < 10; h++) {
            Material mat = h < 2 ? Material.COBBLESTONE : Material.STONE_BRICKS;
            place(world, ox, oy + h, oz, mat);
            place(world, ox + 1, oy + h, oz, mat);
            place(world, ox, oy + h, oz + 1, mat);
            place(world, ox + 1, oy + h, oz + 1, mat);
        }
        // Crenellation top
        place(world, ox, oy + 10, oz, Material.STONE_BRICK_WALL);
        place(world, ox + 1, oy + 10, oz + 1, Material.STONE_BRICK_WALL);

        scanner.scan(world, minX, oy - 1, minZ, maxX, oy + 10, maxZ);

        player.sendMessage("§6Scenario 1: Tower Cascade");
        player.sendMessage("§7Break a §fcorner base block §7and watch it all come down.");
        giveSiegeKit(player);
        playBuildSound(player);
    }

    /**
     * Scenario 2: TNT Wall Breach — curtain wall for explosion demo.
     */
    public void buildScenario2Wall(Player player) {
        World world = player.getWorld();
        BlockFace face = cardinalFacing(player.getLocation().getYaw());
        Block feet = player.getLocation().getBlock();

        int fx = face.getModX();
        int fz = face.getModZ();
        int rx = -fz;
        int rz = fx;

        int ox = feet.getX() + fx * 6;
        int oy = feet.getY();
        int oz = feet.getZ() + fz * 6;

        clearArea(world, ox + rx * -5, oy - 1, oz + rz * -5, ox + rx * 5, oy + 6, oz + rz * 5);

        int minX = Integer.MAX_VALUE, minY = oy - 1, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = oy, maxZ = Integer.MIN_VALUE;

        // Bedrock foundation + Wall: 9 wide, 5 tall
        for (int w = -4; w <= 4; w++) {
            int bx = ox + rx * w;
            int bz = oz + rz * w;
            // Bedrock under each column
            place(world, bx, oy - 1, bz, Material.BEDROCK);
            minX = Math.min(minX, bx);
            minZ = Math.min(minZ, bz);
            maxX = Math.max(maxX, bx);
            maxZ = Math.max(maxZ, bz);

            for (int h = 0; h < 5; h++) {
                place(world, bx, oy + h, bz, Material.STONE_BRICKS);
                maxY = Math.max(maxY, oy + h);
            }
        }

        scanner.scan(world, minX, minY, minZ, maxX, maxY, maxZ);

        player.getInventory().addItem(new ItemStack(Material.TNT, 16));
        player.getInventory().addItem(new ItemStack(Material.FLINT_AND_STEEL, 1));

        player.sendMessage("§6Scenario 2: TNT Wall Breach");
        player.sendMessage("§7Throw/place §fTNT §7at the wall and watch cracks spread + collapse.");
        playBuildSound(player);
    }

    /**
     * Scenario 3: Fire/Heat Damage — wooden wall with fire source.
     * Uses wood so vanilla fire can spread and burn it, while strux radiant heat
     * also damages it showing cracks before collapse.
     */
    public void buildScenario3Fire(Player player) {
        World world = player.getWorld();
        BlockFace face = cardinalFacing(player.getLocation().getYaw());
        Block feet = player.getLocation().getBlock();

        int fx = face.getModX();
        int fz = face.getModZ();
        int rx = -fz;
        int rz = fx;

        int ox = feet.getX() + fx * 6;
        int oy = feet.getY();
        int oz = feet.getZ() + fz * 6;

        clearArea(world, ox + rx * -3, oy - 1, oz + rz * -3, ox + rx * 3, oy + 5, oz + rz * 3);

        int minX = Integer.MAX_VALUE, minY = oy - 1, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = oy, maxZ = Integer.MIN_VALUE;

        // Wooden wall: 5 wide, 4 tall on bedrock
        for (int w = -2; w <= 2; w++) {
            int bx = ox + rx * w;
            int bz = oz + rz * w;
            // Bedrock foundation
            place(world, bx, oy - 1, bz, Material.BEDROCK);
            minX = Math.min(minX, bx);
            minZ = Math.min(minZ, bz);
            maxX = Math.max(maxX, bx);
            maxZ = Math.max(maxZ, bz);
            // Oak planks above (burns naturally AND takes radiant heat damage)
            for (int h = 0; h <= 3; h++) {
                place(world, bx, oy + h, bz, Material.OAK_PLANKS);
                maxY = Math.max(maxY, oy + h);
            }
        }

        // Place netherrack with fire in front of center (sustained heat source)
        int fireBx = ox + fx * -1; // one block in front
        int fireBz = oz + fz * -1;
        place(world, fireBx, oy, fireBz, Material.NETHERRACK);
        place(world, fireBx, oy + 1, fireBz, Material.FIRE);

        scanner.scan(world, minX, minY, minZ, maxX, maxY, maxZ);

        // Manually register the fire with FireScorchTask since setType() doesn't fire BlockIgniteEvent
        if (plugin instanceof StructuralIntegrityPlugin sip) {
            FireScorchTask fireTask = sip.getFireScorchTask();
            if (fireTask != null) {
                fireTask.registerFire(new Location(world, fireBx, oy + 1, fireBz));
            }
        }

        player.getInventory().addItem(new ItemStack(Material.FLINT_AND_STEEL, 1));
        player.getInventory().addItem(new ItemStack(Material.NETHERRACK, 8));

        player.sendMessage("§6Scenario 3: Fire Damage");
        player.sendMessage("§7The fire burns the wood. Watch §fcracks appear §7as it burns.");
        player.sendMessage("§7Set more blocks on fire §fwith flint & steel§7 to speed collapse.");
        playBuildSound(player);
    }

    /**
     * Scenario 4: Stress Overlay — thin column to demo /engineer green→red.
     */
    public void buildScenario4Stress(Player player) {
        World world = player.getWorld();
        BlockFace face = cardinalFacing(player.getLocation().getYaw());
        Block feet = player.getLocation().getBlock();

        int ox = feet.getX() + face.getModX() * 6;
        int oy = feet.getY();
        int oz = feet.getZ() + face.getModZ() * 6;

        clearArea(world, ox - 1, oy - 1, oz - 1, ox + 1, oy + 8, oz + 1);

        // Bedrock foundation
        place(world, ox, oy - 1, oz, Material.BEDROCK);

        // Single column, 3 tall — low capacity, easy to overload
        for (int h = 0; h < 3; h++) {
            place(world, ox, oy + h, oz, Material.COBBLESTONE);
        }

        scanner.scan(world, ox, oy - 1, oz, ox, oy + 2, oz);

        // Give heavy blocks to stack
        player.getInventory().addItem(new ItemStack(Material.GOLD_BLOCK, 16));
        player.getInventory().addItem(new ItemStack(Material.IRON_BLOCK, 16));

        player.sendMessage("§6Scenario 4: Stress Overlay");
        player.sendMessage("§7Run §e/engineer§7, then stack §fgold/iron blocks §7on top.");
        player.sendMessage("§7Watch the column go §agreen §7→ §eyellow §7→ §6orange §7→ §cred§7.");
        playBuildSound(player);
    }

    private void giveSiegeKit(Player player) {
        player.getInventory().addItem(new ItemStack(Material.TNT, 8));
        player.getInventory().addItem(new ItemStack(Material.FLINT_AND_STEEL, 1));
    }

    private void place(World world, int x, int y, int z, Material material) {
        world.getBlockAt(x, y, z).setType(material, false);
    }

    private void clearArea(World world, int x1, int y1, int z1, int x2, int y2, int z2) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }
    }

    private void playBuildSound(Player player) {
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.2f);
    }

    private BlockFace cardinalFacing(float yaw) {
        float y = (yaw % 360 + 360) % 360;
        if (y >= 315 || y < 45) {
            return BlockFace.SOUTH;
        } else if (y < 135) {
            return BlockFace.WEST;
        } else if (y < 225) {
            return BlockFace.NORTH;
        } else {
            return BlockFace.EAST;
        }
    }
}
