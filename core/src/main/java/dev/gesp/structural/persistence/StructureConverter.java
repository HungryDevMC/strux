package dev.gesp.structural.persistence;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;

/**
 * Converts between StructureGraph (runtime) and StructureData (persistence).
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                      STRUCTURE CONVERTER                           │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Converts between:                                                 │
 *   │                                                                     │
 *   │    StructureGraph  ◄──────────►  StructureData                     │
 *   │    (in-memory,                   (serializable,                    │
 *   │     with stress)                  no stress)                       │
 *   │                                                                     │
 *   │                                                                     │
 *   │  SAVE:   StructureGraph  ──toData()──►  StructureData  ──► File   │
 *   │                                                                     │
 *   │  LOAD:   File  ──►  StructureData  ──toGraph()──►  StructureGraph │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public final class StructureConverter {

    private StructureConverter() {
        // Utility class
    }

    /**
     * Convert a StructureGraph to StructureData for persistence.
     *
     * @param graph   the runtime graph to convert
     * @param worldId identifier for this world
     * @return serializable structure data
     */
    public static StructureData toData(StructureGraph graph, String worldId) {
        StructureData data = new StructureData(worldId);

        for (Node node : graph.getAllNodes()) {
            BlockData block = new BlockData(
                    node.pos().x(),
                    node.pos().y(),
                    node.pos().z(),
                    node.spec().mass(),
                    node.spec().maxLoad(),
                    node.isGrounded(),
                    node.reinforcement(),
                    node.damage(),
                    node.spec().blastResistance(),
                    node.spec().fireResistance(),
                    node.spec().thermalClass());
            data.addBlock(block);
        }

        // Plain block worlds re-derive adjacency from positions on load and
        // never pay for edge storage. Only when the topology is NOT what the
        // grid would derive (explicit connect() graphs, severed joints) do we
        // record the exact edges — re-deriving those would rewire the build.
        if (!isGridDerivable(graph)) {
            data.setExplicitTopology(true);
            for (NodePos pos : graph.getAllPositions()) {
                for (NodePos neighbor : graph.getNeighbors(pos)) {
                    if (lexicographicallyBefore(pos, neighbor)) { // each undirected edge once
                        data.addEdge(new EdgeData(pos.x(), pos.y(), pos.z(), neighbor.x(), neighbor.y(), neighbor.z()));
                    }
                }
            }
        }

        return data;
    }

    /**
     * Convert StructureData back to a StructureGraph.
     *
     * Note: Stress values are NOT restored - they must be recalculated
     * by the solver after loading.
     *
     * @param data the persisted data to convert
     * @return runtime structure graph (stress values will be zero)
     */
    public static StructureGraph toGraph(StructureData data) {
        StructureGraph graph = new StructureGraph();
        mergeInto(data, graph);
        return graph;
    }

    /**
     * Merge a {@link StructureData} snapshot into an <em>existing</em> graph, in place.
     *
     * <p>Same restoration semantics as {@link #toGraph(StructureData)} — nodes with their
     * grounded flag, reinforcement, damage and material spec, plus explicit edges when the
     * snapshot carries them — but the blocks are added to {@code graph} rather than to a
     * fresh one. This is the re-materialization step for component memory eviction (adapter
     * side): a dormant component parked in a sidecar is merged back into the live world
     * graph on chunk load. Stress is not restored; it is recomputed by the solver on next
     * use. Additive and host-agnostic — no game types, no signature changes to existing
     * methods.
     *
     * @param data  the snapshot to restore
     * @param graph the live graph to merge the snapshot's blocks into
     */
    public static void mergeInto(StructureData data, StructureGraph graph) {
        boolean explicit = data.isExplicitTopology();
        for (BlockData block : data.getBlocks()) {
            NodePos pos = new NodePos(block.x(), block.y(), block.z());
            MaterialSpec spec = new MaterialSpec(
                    block.mass(),
                    block.maxLoad(),
                    block.blastResistance(),
                    block.fireResistance(),
                    block.thermalClass());
            if (explicit) {
                graph.addNode(pos, spec, block.grounded()); // edges come from the data, not the grid
            } else {
                graph.addBlock(pos, spec, block.grounded());
            }
            Node node = graph.getNode(pos);
            if (node != null) {
                if (block.reinforcement() != 1.0) {
                    node.setReinforcement(block.reinforcement());
                }
                if (block.damage() > 0.0) {
                    node.addDamage(block.damage()); // siege scars survive the restart
                }
            }
        }
        if (explicit) {
            for (EdgeData edge : data.getEdges()) {
                graph.connect(
                        new NodePos(edge.x1(), edge.y1(), edge.z1()), new NodePos(edge.x2(), edge.y2(), edge.z2()));
            }
        }
    }

    /**
     * Is this graph's adjacency exactly what {@code addBlock} would derive
     * from the positions — every face-adjacent pair connected, and no edge
     * that isn't face-adjacent? If so, edges need not be persisted.
     */
    private static boolean isGridDerivable(StructureGraph graph) {
        for (NodePos pos : graph.getAllPositions()) {
            for (NodePos neighbor : graph.getNeighbors(pos)) {
                if (manhattanDistance(pos, neighbor) != 1) {
                    return false; // a non-grid edge — the grid would never derive it
                }
            }
            for (NodePos adjacent : graph.getAdjacentPositions(pos)) {
                if (graph.hasBlock(adjacent) && !graph.getNeighbors(pos).contains(adjacent)) {
                    return false; // a severed joint — the grid would re-fuse it
                }
            }
        }
        return true;
    }

    private static int manhattanDistance(NodePos a, NodePos b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z());
    }

    private static boolean lexicographicallyBefore(NodePos a, NodePos b) {
        if (a.x() != b.x()) {
            return a.x() < b.x();
        }
        if (a.y() != b.y()) {
            return a.y() < b.y();
        }
        return a.z() < b.z();
    }
}
