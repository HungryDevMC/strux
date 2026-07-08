package dev.gesp.structural.scenario;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;

/**
 * Builders for the recognisable shapes our scenario tests fire at: columns,
 * towers, walls, bridges, cantilevers, houses. These mirror the structures the
 * existing validation tests and the committed {@code .schem} files exercise, but
 * in code so a test can describe a building in one line.
 *
 * <pre>
 *   StructureGraph g = Structures.tower(3, 3, 6);     // 3×3 footprint, 6 tall
 *   StructureGraph g = Structures.bridge(9, 3);       // span 9 between two 3-tall pillars
 * </pre>
 *
 * <p>All builders use grid adjacency ({@link StructureGraph#addBlock}) so blocks
 * auto-connect to their face neighbours, and lay the ground first then build
 * upward so connections form correctly. Coordinates start at the origin; only
 * {@code y} carries physical "up" meaning to the solver.
 */
public final class Structures {

    /** Default building material: HEAVY (mass 3, maxLoad 100) — stone-like. */
    public static final MaterialSpec DEFAULT = TestMaterials.HEAVY;

    private Structures() {} // No instances

    // ─────────────────────────────────────────────────────────────────────
    //  TERRAIN — large flat ground layers (for perf testing)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * A flat terrain slab: ground at y=0, then terrain blocks at y=1..depth.
     * All terrain connects to ground and to each other, forming one massive
     * connected component. This models siege gamemode terrain (140k+ blocks).
     *
     * @param width   extent in X
     * @param length  extent in Z
     * @param depth   layers of terrain above ground (1 = just terrain at y=1)
     */
    public static StructureGraph terrain(int width, int length, int depth) {
        return terrain(width, length, depth, DEFAULT);
    }

    public static StructureGraph terrain(int width, int length, int depth, MaterialSpec spec) {
        StructureGraph g = new StructureGraph();
        // Ground layer at y=0
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < length; z++) {
                g.addGroundBlock(new NodePos(x, 0, z));
            }
        }
        // Terrain layers at y=1..depth
        for (int y = 1; y <= depth; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < length; z++) {
                    g.addBlock(new NodePos(x, y, z), spec, false);
                }
            }
        }
        return g;
    }

    /**
     * Terrain with a structure built on top: tests scoped solving where a small
     * structure sits on a large terrain base.
     *
     * @param terrainWidth  terrain extent in X
     * @param terrainLength terrain extent in Z
     * @param structureSize tower footprint on top
     * @param structureHeight tower height above terrain
     */
    public static StructureGraph terrainWithStructure(
            int terrainWidth, int terrainLength, int structureSize, int structureHeight) {
        return terrainWithStructure(terrainWidth, terrainLength, structureSize, structureHeight, DEFAULT);
    }

    public static StructureGraph terrainWithStructure(
            int terrainWidth, int terrainLength, int structureSize, int structureHeight, MaterialSpec spec) {
        StructureGraph g = terrain(terrainWidth, terrainLength, 1, spec);
        // Build a tower in the center of the terrain
        int startX = terrainWidth / 2 - structureSize / 2;
        int startZ = terrainLength / 2 - structureSize / 2;
        for (int y = 2; y <= structureHeight + 1; y++) {
            for (int x = startX; x < startX + structureSize; x++) {
                for (int z = startZ; z < startZ + structureSize; z++) {
                    g.addBlock(new NodePos(x, y, z), spec, false);
                }
            }
        }
        return g;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  COLUMN — a single vertical stack on one ground block
    // ─────────────────────────────────────────────────────────────────────

    /** A 1-wide column {@code height} blocks tall, sitting on ground at y=0. */
    public static StructureGraph column(int height) {
        return column(height, DEFAULT);
    }

    public static StructureGraph column(int height, MaterialSpec spec) {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= height; y++) {
            g.addBlock(new NodePos(0, y, 0), spec, false);
        }
        return g;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  TOWER — a solid w×d footprint extruded h blocks tall
    // ─────────────────────────────────────────────────────────────────────

    public static StructureGraph tower(int width, int depth, int height) {
        return tower(width, depth, height, DEFAULT);
    }

    public static StructureGraph tower(int width, int depth, int height, MaterialSpec spec) {
        StructureGraph g = new StructureGraph();
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                g.addGroundBlock(new NodePos(x, 0, z));
            }
        }
        for (int y = 1; y <= height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    g.addBlock(new NodePos(x, y, z), spec, false);
                }
            }
        }
        return g;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  WALL — a 1-thick slab in the x/y plane (z=0)
    // ─────────────────────────────────────────────────────────────────────

    public static StructureGraph wall(int length, int height) {
        return wall(length, height, DEFAULT);
    }

    public static StructureGraph wall(int length, int height, MaterialSpec spec) {
        StructureGraph g = new StructureGraph();
        for (int x = 0; x < length; x++) {
            g.addGroundBlock(new NodePos(x, 0, 0));
        }
        for (int y = 1; y <= height; y++) {
            for (int x = 0; x < length; x++) {
                g.addBlock(new NodePos(x, y, 0), spec, false);
            }
        }
        return g;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  BRIDGE — two grounded pillars joined by a horizontal deck (beam)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Two pillars {@code pillarHeight} tall at x=0 and x=span-1, joined by a
     * deck along the top. Supported at both ends, so the deck is a beam.
     */
    public static StructureGraph bridge(int span, int pillarHeight) {
        return bridge(span, pillarHeight, DEFAULT);
    }

    public static StructureGraph bridge(int span, int pillarHeight, MaterialSpec spec) {
        StructureGraph g = new StructureGraph();
        int left = 0;
        int right = span - 1;
        g.addGroundBlock(new NodePos(left, 0, 0));
        g.addGroundBlock(new NodePos(right, 0, 0));
        // Two pillars
        for (int y = 1; y <= pillarHeight; y++) {
            g.addBlock(new NodePos(left, y, 0), spec, false);
            g.addBlock(new NodePos(right, y, 0), spec, false);
        }
        // Deck across the top (fills between the pillar tops)
        for (int x = left; x <= right; x++) {
            g.addBlock(new NodePos(x, pillarHeight, 0), spec, false);
        }
        return g;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  CANTILEVER — a grounded pillar with a horizontal arm sticking out
    // ─────────────────────────────────────────────────────────────────────

    /**
     * A pillar {@code pillarHeight} tall at x=0, with an arm of {@code armLength}
     * blocks reaching out along +x at the top. Supported on one end only.
     */
    public static StructureGraph cantilever(int pillarHeight, int armLength) {
        return cantilever(pillarHeight, armLength, DEFAULT);
    }

    public static StructureGraph cantilever(int pillarHeight, int armLength, MaterialSpec spec) {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= pillarHeight; y++) {
            g.addBlock(new NodePos(0, y, 0), spec, false);
        }
        for (int x = 1; x <= armLength; x++) {
            g.addBlock(new NodePos(x, pillarHeight, 0), spec, false);
        }
        return g;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HOUSE — a hollow box (perimeter walls + flat roof) on a floor pad
    // ─────────────────────────────────────────────────────────────────────

    /**
     * A hollow {@code size}×{@code size} house, {@code wallHeight} tall, with a
     * flat roof one block above the walls. Floor pad is ground. Small spans keep
     * it stable as-built, so a blast's effect is the crater plus what it
     * undermines — not a pre-existing collapse.
     */
    public static StructureGraph house(int size, int wallHeight) {
        return house(size, wallHeight, DEFAULT);
    }

    public static StructureGraph house(int size, int wallHeight, MaterialSpec spec) {
        StructureGraph g = new StructureGraph();
        int max = size - 1;
        // Floor pad = ground
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                g.addGroundBlock(new NodePos(x, 0, z));
            }
        }
        // Perimeter walls
        for (int y = 1; y <= wallHeight; y++) {
            for (int x = 0; x < size; x++) {
                for (int z = 0; z < size; z++) {
                    boolean perimeter = (x == 0 || x == max || z == 0 || z == max);
                    if (perimeter) {
                        g.addBlock(new NodePos(x, y, z), spec, false);
                    }
                }
            }
        }
        // Flat roof one block above the walls
        int roofY = wallHeight + 1;
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                g.addBlock(new NodePos(x, roofY, z), spec, false);
            }
        }
        return g;
    }
}
