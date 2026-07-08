package dev.gesp.structural.minecraft.material;

import dev.gesp.structural.minecraft.config.FoundationConfig;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Decides whether a block anchors to NATURAL TERRAIN under the foundation policy.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                       TERRAIN GROUNDING                           │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Two ways a build can reach solid ground without exposed bedrock:  │
 *   │                                                                     │
 *   │   • FOUNDATION BLOCK: a designated material, placed on solid        │
 *   │     terrain, acts as a ground anchor.                              │
 *   │                                                                     │
 *   │   • DEPTH GROUNDING: a block with N solid natural-terrain blocks   │
 *   │     straight below it is treated as standing on solid ground.      │
 *   │                                                                     │
 *   │        [BLOCK]   ← grounded                                         │
 *   │        [DIRT]                                                       │
 *   │        [STONE]   N = 3 contiguous terrain blocks below            │
 *   │        [STONE]                                                      │
 *   │                                                                     │
 *   │  Shared by the place path (StructureManager) and the scan path     │
 *   │  (RegionScanner) so both ground a build identically.               │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public final class TerrainGrounding {

    private TerrainGrounding() {}

    /**
     * Whether the foundation policy anchors this block to the terrain — either it
     * is the configured foundation block sitting on solid ground, or it has the
     * configured depth of solid natural terrain straight below it.
     *
     * <p>With the default config (depth grounding off, no foundation block) this
     * always returns {@code false}, preserving legacy behaviour.
     */
    public static boolean groundsAsFoundation(Block block, FoundationConfig config, MaterialRegistry materials) {
        if (config.isFoundationBlock(block.getType()) && restsOnSolidTerrain(block)) {
            return true;
        }
        return config.isDepthGroundingEnabled() && hasTerrainDepthBelow(block, config.getMinDepth(), materials);
    }

    /** Whether the block directly below is a solid (non-air) world block. */
    private static boolean restsOnSolidTerrain(Block block) {
        World world = block.getWorld();
        int belowY = block.getY() - 1;
        if (belowY < world.getMinHeight()) {
            return false;
        }
        Material below = world.getBlockAt(block.getX(), belowY, block.getZ()).getType();
        return below.isSolid();
    }

    /**
     * Whether at least {@code depth} contiguous solid NATURAL-terrain blocks sit
     * straight below {@code block} (a foundation reaching solid ground). The run
     * must be unbroken: the first air / non-terrain / out-of-bounds block ends it.
     */
    private static boolean hasTerrainDepthBelow(Block block, int depth, MaterialRegistry materials) {
        World world = block.getWorld();
        int minHeight = world.getMinHeight();
        int x = block.getX();
        int z = block.getZ();
        int found = 0;
        for (int y = block.getY() - 1; y >= minHeight && found < depth; y--) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (type.isSolid() && materials.isNaturalTerrain(type)) {
                found++;
            } else {
                break; // run broken — not resting on a solid terrain column
            }
        }
        return found >= depth;
    }
}
