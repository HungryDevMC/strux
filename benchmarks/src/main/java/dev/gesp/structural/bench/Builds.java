package dev.gesp.structural.bench;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;

/**
 * Minimal structure builders for the benchmarks. Deliberately a small copy of
 * the shapes in {@code :core}'s test {@code Structures} helper — that lives in
 * test sources, which the benchmark module cannot see.
 */
final class Builds {

    /** Stone-like: mass 3, maxLoad 100 — matches the test HEAVY material. */
    static final MaterialSpec HEAVY = new MaterialSpec(3.0, 100.0);

    private Builds() {}

    static StructureGraph column(int height) {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= height; y++) {
            g.addBlock(new NodePos(0, y, 0), HEAVY, false);
        }
        return g;
    }

    static StructureGraph tower(int width, int depth, int height) {
        StructureGraph g = new StructureGraph();
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                g.addGroundBlock(new NodePos(x, 0, z));
            }
        }
        for (int y = 1; y <= height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    g.addBlock(new NodePos(x, y, z), HEAVY, false);
                }
            }
        }
        return g;
    }

    /**
     * A "world" of {@code count} independent grounded columns, spaced two apart
     * so none are connected. Models many separate builds sharing one world graph.
     */
    static StructureGraph manyColumns(int count, int height) {
        StructureGraph g = new StructureGraph();
        for (int i = 0; i < count; i++) {
            int x = i * 2; // gap of 1 between columns → separate components
            g.addGroundBlock(new NodePos(x, 0, 0));
            for (int y = 1; y <= height; y++) {
                g.addBlock(new NodePos(x, y, 0), HEAVY, false);
            }
        }
        return g;
    }

    static StructureGraph bridge(int span, int pillarHeight) {
        StructureGraph g = new StructureGraph();
        int right = span - 1;
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addGroundBlock(new NodePos(right, 0, 0));
        for (int y = 1; y <= pillarHeight; y++) {
            g.addBlock(new NodePos(0, y, 0), HEAVY, false);
            g.addBlock(new NodePos(right, y, 0), HEAVY, false);
        }
        for (int x = 0; x <= right; x++) {
            g.addBlock(new NodePos(x, pillarHeight, 0), HEAVY, false);
        }
        return g;
    }

    /** Stone-like terrain: mass 2.5, maxLoad 50. */
    static final MaterialSpec STONE = new MaterialSpec(2.5, 50.0);

    /** Wood-like structure: mass 0.5, maxLoad 8 — weaker, more likely to cascade. */
    static final MaterialSpec WOOD = new MaterialSpec(0.5, 8.0);

    /**
     * Siege terrain: flat ground with 2 stone layers on top, plus a wooden tower.
     * This models the problematic case where terrain and structure are connected.
     *
     * @param terrainSize width/depth of the terrain grid
     * @param towerSize width/depth of the tower footprint
     * @param towerHeight height of the wooden tower above the terrain
     */
    static StructureGraph siegeTerrain(int terrainSize, int towerSize, int towerHeight) {
        StructureGraph g = new StructureGraph();

        // Ground layer
        for (int x = 0; x < terrainSize; x++) {
            for (int z = 0; z < terrainSize; z++) {
                g.addGroundBlock(new NodePos(x, 0, z));
            }
        }

        // Two stone layers (y=1 and y=2)
        for (int y = 1; y <= 2; y++) {
            for (int x = 0; x < terrainSize; x++) {
                for (int z = 0; z < terrainSize; z++) {
                    g.addBlock(new NodePos(x, y, z), STONE, false);
                }
            }
        }

        // Wooden tower on top (centered)
        int towerStart = (terrainSize - towerSize) / 2;
        for (int y = 3; y < 3 + towerHeight; y++) {
            for (int x = towerStart; x < towerStart + towerSize; x++) {
                for (int z = towerStart; z < towerStart + towerSize; z++) {
                    g.addBlock(new NodePos(x, y, z), WOOD, false);
                }
            }
        }

        return g;
    }
}
