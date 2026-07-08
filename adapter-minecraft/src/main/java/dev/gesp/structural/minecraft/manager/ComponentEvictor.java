package dev.gesp.structural.minecraft.manager;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.persistence.StructureConverter;
import dev.gesp.structural.persistence.StructureData;
import java.util.Set;

/**
 * The pure-graph mechanism behind component memory eviction (SCALING.md §5–§6).
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │             EVICT A WHOLE COMPONENT, NEVER A CHUNK SLICE            │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  A structure does not respect chunk borders. Evicting a slice of a  │
 *   │  component while its other half stays live could strip a support    │
 *   │  path or ground anchor → spurious/missed collapse. So the eviction  │
 *   │  UNIT is a connected component: all its nodes leave the live graph  │
 *   │  together, parked in a StructureData sidecar, and come back         │
 *   │  together. Because the core is deterministic and StructureData      │
 *   │  round-trips full per-node persistent state, evict→restore is       │
 *   │  bit-identical to never having evicted (stress is recomputed).      │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>This class is deliberately Bukkit-free: it operates only on a {@link StructureGraph}
 * and a set of positions, so the bit-identical round-trip is provable without a server.
 * The chunk bookkeeping that decides <em>when</em> to call it lives in
 * {@link ChunkEvictionManager}.
 *
 * <p><b>Thermal guard.</b> {@link StructureData} does not carry the transient
 * {@code temperatureCapacityFactor} (thermal softening), so a round-trip would silently
 * reset it. Eviction therefore refuses any component holding a softened node. In practice
 * softening only happens near active heat → near players → loaded chunks, which are never
 * evicted anyway (the SCALING.md §5 caveat); the guard makes that assumption enforced
 * rather than assumed.
 */
public final class ComponentEvictor {

    // Deterministic work counters (mirrors the *Metrics pattern); budgeted by a test.
    private long evictions;
    private long nodesEvicted;
    private long rematerializations;
    private long nodesRematerialized;

    /**
     * Is every node of {@code component} at cold strength (no thermal softening)? A
     * component is only safe to round-trip through {@link StructureData} when it is —
     * otherwise softening would be lost. Missing nodes are ignored.
     */
    public static boolean isThermallyResident(StructureGraph graph, Set<NodePos> component) {
        for (NodePos pos : component) {
            Node node = graph.getNode(pos);
            if (node != null && node.temperatureCapacityFactor() != 1.0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Snapshot {@code component} to a sidecar and remove its nodes from the live graph.
     *
     * <p>Caller must have already established that the component is fully dormant (all its
     * chunks unloaded) and {@link #isThermallyResident thermally resident}. Returns the
     * sidecar to be parked until re-materialization.
     *
     * @param graph     the live world graph to evict from
     * @param component the exact set of positions forming one connected component
     * @param worldId   identifier stored in the sidecar
     * @return the sidecar snapshot (bit-identical restore source)
     */
    public StructureData evict(StructureGraph graph, Set<NodePos> component, String worldId) {
        StructureGraph slice = graph.copySubgraph(component);
        StructureData sidecar = StructureConverter.toData(slice, worldId);
        for (NodePos pos : component) {
            graph.removeBlock(pos);
        }
        evictions++;
        nodesEvicted += sidecar.blockCount();
        return sidecar;
    }

    /**
     * Merge a parked sidecar back into the live graph, exactly as it was evicted.
     *
     * @param graph   the live world graph to restore into
     * @param sidecar the snapshot produced by {@link #evict}
     */
    public void rematerialize(StructureGraph graph, StructureData sidecar) {
        StructureConverter.mergeInto(sidecar, graph);
        rematerializations++;
        nodesRematerialized += sidecar.blockCount();
    }

    public long evictions() {
        return evictions;
    }

    public long nodesEvicted() {
        return nodesEvicted;
    }

    public long rematerializations() {
        return rematerializations;
    }

    public long nodesRematerialized() {
        return nodesRematerialized;
    }

    @Override
    public String toString() {
        return "ComponentEvictor{evictions=" + evictions
                + ", nodesEvicted=" + nodesEvicted
                + ", rematerializations=" + rematerializations
                + ", nodesRematerialized=" + nodesRematerialized + '}';
    }
}
