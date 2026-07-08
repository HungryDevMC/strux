package dev.gesp.structural.minecraft.temperature;

import dev.gesp.structural.model.NodePos;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;

/**
 * Reads a tracked block's effective temperature (°C) from the Minecraft world —
 * the input to {@code ThermalStrength.capacityFactor}. Hot sources (lava, fire)
 * raise it, cold ones (ice, snow, frozen biomes) lower it, and the biome sets
 * the ambient floor.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │            MINECRAFT'S ABSTRACT HEAT → PLAUSIBLE °C MAPPING          │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │   lava            ≈ 1100 °C   (real basaltic lava is ~1000–1200)   │
 *   │   fire / soul     ≈  800 °C   (an open wood/coal flame)            │
 *   │   magma block     ≈  400 °C   (hot but solid)                      │
 *   │                                                                     │
 *   │   biome ambient:                                                   │
 *   │     snowy / frozen ≈ −10 °C                                        │
 *   │     temperate      ≈  20 °C                                        │
 *   │     desert         ≈  45 °C                                        │
 *   │     nether         ≈  60 °C                                        │
 *   │                                                                     │
 *   │   ice / snow block lowers a block's ambient toward freezing.       │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>Thermal-mass proxy (thick resists heat)</h2>
 *
 * <p>A heat source's contribution falls off with distance AND through any solid
 * blocks between it and the target — so the interior of a thick wall stays cooler
 * (and therefore stronger) than its exposed face. This is a cheap geometric
 * count of intervening solid blocks along the line, NOT a diffusion PDE; it is
 * the "thick wall resists heat" build decision made affordable.
 *
 * <p>Pure function of world state: same world ⇒ same °C. No RNG.
 */
public final class TemperatureProvider {

    /** °C assigned to a block sitting in lava. */
    public static final double LAVA_C = 1100.0;
    /** °C assigned to a block engulfed in fire. */
    public static final double FIRE_C = 800.0;
    /** °C assigned to a block of magma. */
    public static final double MAGMA_C = 400.0;

    private final double comfortC;
    /** How many blocks of distance halve a heat source's above-ambient contribution. */
    private final int heatFalloffRadius;
    /** Extra falloff (in equivalent blocks) charged per intervening SOLID block. */
    private final double solidInsulationBlocks;

    public TemperatureProvider(double comfortC, int heatFalloffRadius, double solidInsulationBlocks) {
        this.comfortC = comfortC;
        this.heatFalloffRadius = Math.max(1, heatFalloffRadius);
        this.solidInsulationBlocks = Math.max(0.0, solidInsulationBlocks);
    }

    /**
     * The effective temperature (°C) at {@code pos}: the biome/cold ambient, plus
     * the hottest nearby source's above-ambient contribution after distance +
     * thermal-mass falloff. Scans a small cube around the block for sources.
     *
     * @param world      the world
     * @param pos        the tracked block
     * @param scanRadius how far (in blocks) to look for heat sources
     */
    public double temperatureAt(World world, NodePos pos, int scanRadius) {
        double ambient = ambientC(world, pos);

        double hottest = ambient;
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dy = -scanRadius; dy <= scanRadius; dy++) {
                int y = pos.y() + dy;
                if (y < minY || y > maxY) {
                    continue; // outside the world column — nothing to read
                }
                for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    Block b = world.getBlockAt(pos.x() + dx, y, pos.z() + dz);
                    double sourceC = sourceTemperatureC(b.getType());
                    if (sourceC <= ambient) {
                        continue; // not a heat source (or cooler than ambient)
                    }
                    double reaching = ambient + (sourceC - ambient) * reachFactor(world, pos, dx, dy, dz);
                    if (reaching > hottest) {
                        hottest = reaching;
                    }
                }
            }
        }
        return hottest;
    }

    /**
     * Fraction (0..1) of a source's above-ambient heat that reaches the target,
     * given the offset to the source. Combines straight-line distance with a
     * count of intervening SOLID blocks (the thermal-mass proxy): every solid
     * block on the way is charged as extra distance, so a source behind a thick
     * wall barely warms the far side.
     */
    private double reachFactor(World world, NodePos target, int dx, int dy, int dz) {
        double distance = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
        double effectiveDistance = distance + solidInsulationBlocks * countSolidBetween(world, target, dx, dy, dz);
        // Exponential-ish falloff: halve every heatFalloffRadius of effective distance.
        return Math.pow(0.5, effectiveDistance / heatFalloffRadius);
    }

    /**
     * Count SOLID blocks strictly between the target and the source along the
     * straight line (3D Bresenham-ish step), excluding both endpoints. These are
     * the blocks heat has to conduct through — the wall's thickness.
     */
    private int countSolidBetween(World world, NodePos target, int dx, int dy, int dz) {
        int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        if (steps <= 1) {
            return 0; // adjacent: nothing between
        }
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        int solid = 0;
        for (int s = 1; s < steps; s++) {
            int x = target.x() + Math.round((float) dx * s / steps);
            int y = target.y() + Math.round((float) dy * s / steps);
            int z = target.z() + Math.round((float) dz * s / steps);
            if (y < minY || y > maxY) {
                continue;
            }
            Material m = world.getBlockAt(x, y, z).getType();
            if (m.isSolid() && !isHeatSource(m)) {
                solid++;
            }
        }
        return solid;
    }

    /** The °C a given block type radiates as a heat source, or below-ambient if it isn't one. */
    private double sourceTemperatureC(Material m) {
        return switch (m) {
            case LAVA -> LAVA_C;
            case FIRE, SOUL_FIRE -> FIRE_C;
            case MAGMA_BLOCK -> MAGMA_C;
            default -> Double.NEGATIVE_INFINITY;
        };
    }

    private static boolean isHeatSource(Material m) {
        return m == Material.LAVA || m == Material.FIRE || m == Material.SOUL_FIRE || m == Material.MAGMA_BLOCK;
    }

    /**
     * Ambient temperature at this position from biome, nudged colder by an
     * adjacent ice/snow block. The Nether is hot everywhere; snowy/frozen biomes
     * sit near freezing; deserts run hot.
     */
    private double ambientC(World world, NodePos pos) {
        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        double ambient = biomeAmbientC(block.getBiome());

        // Adjacent ice/snow pulls the local ambient down toward freezing.
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        for (int[] face : FACES) {
            int y = pos.y() + face[1];
            if (y < minY || y > maxY) {
                continue;
            }
            Material m =
                    world.getBlockAt(pos.x() + face[0], y, pos.z() + face[2]).getType();
            if (isCold(m)) {
                ambient = Math.min(ambient, 0.0);
                break;
            }
        }
        return ambient;
    }

    private double biomeAmbientC(Biome biome) {
        String name = biome.name().toLowerCase(Locale.ROOT);
        if (name.contains("nether") || name.contains("basalt") || name.contains("crimson") || name.contains("warped")) {
            return 60.0;
        }
        if (name.contains("snow")
                || name.contains("frozen")
                || name.contains("ice")
                || name.contains("taiga")
                || name.equals("grove")
                || name.contains("peak")
                || name.contains("cold")) {
            return -10.0;
        }
        if (name.contains("desert") || name.contains("badlands") || name.contains("savanna")) {
            return 45.0;
        }
        return comfortC; // temperate default
    }

    private static boolean isCold(Material m) {
        return m == Material.ICE
                || m == Material.PACKED_ICE
                || m == Material.BLUE_ICE
                || m == Material.FROSTED_ICE
                || m == Material.SNOW
                || m == Material.SNOW_BLOCK
                || m == Material.POWDER_SNOW;
    }

    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
}
