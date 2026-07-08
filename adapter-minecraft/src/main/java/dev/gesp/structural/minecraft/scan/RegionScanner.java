package dev.gesp.structural.minecraft.scan;

import dev.gesp.structural.assess.StructureGrade;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.config.FoundationConfig;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.material.MaterialRegistry;
import dev.gesp.structural.minecraft.material.TerrainGrounding;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.StressSolver;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Retroactively registers an existing build into the structure graph, so blocks
 * that were placed by WorldEdit, world generation, or before the plugin loaded
 * gain structural integrity.
 *
 * <pre>
 *   1. Every solid block in the box becomes a node (ground materials become
 *      ground/foundation nodes).
 *   2. Each block touching solid terrain OUTSIDE the selection is anchored to a
 *      ground node there — so the build is grounded by what it actually rests
 *      on, at any height (no reliance on a fixed ground level).
 *   3. Stress is solved so future breaks/explosions cascade correctly, and any
 *      already-overstressed blocks are reported as a warning (not collapsed).
 * </pre>
 */
public class RegionScanner {

    /** Bounding-box volume above which a scan is refused (perf guard). */
    public static final long MAX_VOLUME = 50_000;

    private final StructureManager structureManager;
    private final MaterialRegistry materials;
    private final StressSolver solver;

    public RegionScanner(StructureManager structureManager, MaterialRegistry materials, PhysicsConfig config) {
        this.structureManager = structureManager;
        this.materials = materials;
        this.solver = new StressSolver(config);
    }

    public record Result(
            boolean tooLarge,
            long volume,
            int registered,
            int groundAnchors,
            int overstressed,
            StructureGrade grade,
            int peakPercent,
            int avgPercent) {
        static Result refused(long volume) {
            return new Result(true, volume, 0, 0, 0, StructureGrade.S, 0, 0);
        }
    }

    public Result scan(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > MAX_VOLUME) {
            return Result.refused(volume);
        }

        StructureGraph graph = structureManager.getOrCreateGraph(world);
        FoundationConfig foundation = structureManager.getFoundationConfig();
        List<NodePos> added = new ArrayList<>();

        // Pass 1: register every solid block in the box. A block becomes a ground
        // anchor when it is a ground material, the configured foundation block on
        // solid terrain, or (depth grounding) has enough solid natural terrain
        // straight below it — matching the place-path policy in StructureManager.
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (type.isAir() || !type.isSolid()) {
                        continue;
                    }
                    NodePos pos = new NodePos(x, y, z);
                    if (graph.hasBlock(pos)) {
                        continue; // already tracked
                    }
                    if (materials.isGround(type)
                            || TerrainGrounding.groundsAsFoundation(block, foundation, materials)) {
                        graph.addGroundBlock(pos);
                    } else {
                        graph.addBlock(pos, materials.getSpec(type), false);
                        added.add(pos);
                    }
                }
            }
        }

        // Pass 2: anchor to the terrain BELOW the build (not all 6 sides).
        // Only blocks directly below (y-1) become ground anchors - this is where
        // the structure actually rests. Side/top contacts don't support against gravity.
        int anchors = 0;
        for (NodePos p : added) {
            NodePos below = new NodePos(p.x(), p.y() - 1, p.z());
            if (graph.hasBlock(below)) {
                continue; // already tracked
            }
            Block nb = world.getBlockAt(below.x(), below.y(), below.z());
            if (nb.getType().isSolid()) {
                graph.addGroundBlock(below);
                anchors++;
            }
        }

        // Solve so the graph is consistent for future cascades; report (don't collapse).
        solver.solveAll(graph);
        int overstressed = 0;
        double peak = 0.0;
        double sum = 0.0;
        for (NodePos p : added) {
            Node n = graph.getNode(p);
            if (n == null) {
                continue;
            }
            double pct = n.stressPercent();
            if (!Double.isFinite(pct)) {
                pct = 2.0; // destroyed-equivalent, kept finite for the average
            }
            peak = Math.max(peak, pct);
            sum += pct;
            if (n.isOverloaded()) {
                overstressed++;
            }
        }
        int assessed = added.size();
        StructureGrade grade = StructureGrade.of(peak, overstressed);
        int peakPct = (int) Math.round(peak * 100);
        int avgPct = assessed > 0 ? (int) Math.round(sum / assessed * 100) : 0;
        return new Result(false, volume, assessed, anchors, overstressed, grade, peakPct, avgPct);
    }
}
