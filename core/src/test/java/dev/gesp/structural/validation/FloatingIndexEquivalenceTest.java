package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.gesp.structural.TestMaterials;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.NodePos;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link StructureGraph#getFloatingBlocks()} and
 * {@link StructureGraph#getBlocksConnectedToGround()} are now answered from a
 * maintained chunk-connectivity index rather than a fresh flood-fill. This test
 * pins that the index stays in lock-step with every topology edit — add, break,
 * connect, disconnect — by comparing the graph's verdict to an independent BFS over
 * its own public neighbour view, after every operation across thousands of random
 * edits. A single missed hook would diverge here.
 */
@DisplayName("Floating detection via the chunk index == a fresh BFS, after every edit")
class FloatingIndexEquivalenceTest {

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Random grid edits keep getFloatingBlocks identical to BFS")
    void gridEditsStayInSync() {
        Random rng = new Random(0xF10A7L);
        for (int trial = 0; trial < 200; trial++) {
            StructureGraph g = new StructureGraph();
            int span = 5;
            // A ground layer so blocks have something to attach to.
            for (int x = 0; x < span; x++) {
                for (int z = 0; z < span; z++) {
                    g.addGroundBlock(new NodePos(x, 0, z));
                }
            }
            for (int step = 0; step < 40; step++) {
                NodePos p = new NodePos(rng.nextInt(span), 1 + rng.nextInt(4), rng.nextInt(span));
                if (g.hasBlock(p)) {
                    g.removeBlock(p);
                } else {
                    g.addBlock(p, TestMaterials.MEDIUM, false);
                }
                assertSameFloating(g, "grid trial " + trial + " step " + step);
            }
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Random generic connect/disconnect keeps getFloatingBlocks identical to BFS")
    void genericEdgesStayInSync() {
        Random rng = new Random(0x6E91CL);
        for (int trial = 0; trial < 200; trial++) {
            StructureGraph g = new StructureGraph();
            List<NodePos> pool = new ArrayList<>();
            // A scattering of nodes across several chunks; a few are bedrock.
            for (int i = 0; i < 10; i++) {
                NodePos p = new NodePos(rng.nextInt(20), rng.nextInt(20), rng.nextInt(20));
                if (!g.hasBlock(p)) {
                    if (rng.nextDouble() < 0.25) {
                        g.addGroundBlock(p);
                    } else {
                        g.addNode(p, TestMaterials.MEDIUM, false);
                    }
                    pool.add(p);
                }
            }
            for (int step = 0; step < 40; step++) {
                NodePos a = pool.get(rng.nextInt(pool.size()));
                NodePos b = pool.get(rng.nextInt(pool.size()));
                if (g.hasBlock(a) && g.hasBlock(b)) {
                    if (rng.nextBoolean()) {
                        g.connect(a, b);
                    } else {
                        g.disconnect(a, b);
                    }
                }
                assertSameFloating(g, "generic trial " + trial + " step " + step);
            }
        }
    }

    /** The graph's index-backed verdict must equal a fresh BFS over its public neighbour view. */
    private static void assertSameFloating(StructureGraph g, String where) {
        Set<NodePos> reachable = new HashSet<>(g.getGroundNodes());
        Queue<NodePos> queue = new ArrayDeque<>(reachable);
        while (!queue.isEmpty()) {
            NodePos cur = queue.poll();
            for (NodePos n : g.getNeighbors(cur)) {
                if (reachable.add(n)) {
                    queue.add(n);
                }
            }
        }
        Set<NodePos> expectedFloating = new HashSet<>(g.getAllPositions());
        expectedFloating.removeAll(reachable);

        assertEquals(expectedFloating, g.getFloatingBlocks(), "floating set diverged (" + where + ")");
        assertEquals(reachable, g.getBlocksConnectedToGround(), "connected set diverged (" + where + ")");

        // The single-block query must agree with the BFS verdict for every node — this
        // is what exercises the incremental-reachability skip path across all the edits.
        for (NodePos n : g.getAllPositions()) {
            assertEquals(
                    reachable.contains(n),
                    g.isConnectedToGround(n),
                    () -> "isConnectedToGround diverged at " + n + " (" + where + ")");
        }
    }
}
