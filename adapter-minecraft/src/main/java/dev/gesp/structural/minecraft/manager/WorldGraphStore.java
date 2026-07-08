package dev.gesp.structural.minecraft.manager;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.World;

/**
 * The per-world graph store and its change-revision counter.
 *
 * <p>Holds the {@code Map<UUID, StructureGraph>} that {@link StructureManager}
 * used to own directly, plus the per-world {@code revision} that bumps on every
 * structural change. Read-only consumers (world grade, the stress visualizer's
 * scan) cache against the revision and skip their work entirely while a world is
 * unchanged — so a server full of static builds costs almost nothing per tick.
 *
 * <p>The graph map is NOT thread-safe; like before, it is only ever mutated on
 * the main thread. The persistence coordinator's off-thread load publishes back
 * through {@link #put} on the main thread only.
 */
final class WorldGraphStore {

    private final Map<UUID, StructureGraph> worldGraphs = new HashMap<>();

    // FREEZE: a per-world revision that bumps on every structural change. Read-only
    // consumers (world grade, the stress visualizer's scan) cache against it and
    // skip their work entirely while a world is unchanged.
    private final Map<UUID, Long> worldRevision = new HashMap<>();

    /** Get the graph for a world (may be null). */
    StructureGraph getGraph(World world) {
        return worldGraphs.get(world.getUID());
    }

    /** Get or create a graph for a world. */
    StructureGraph getOrCreateGraph(World world) {
        return worldGraphs.computeIfAbsent(world.getUID(), k -> new StructureGraph());
    }

    /**
     * Install a graph for a world. Used by the persistence load path's
     * main-thread publish — the single off-thread-originated writer of the map.
     */
    void put(UUID worldId, StructureGraph graph) {
        worldGraphs.put(worldId, graph);
    }

    /**
     * MAIN-THREAD ONLY. Merge a freshly-loaded persisted graph into the world's
     * live graph — <b>live wins</b> on any shared position — instead of replacing it.
     *
     * <p>The old boot path did an unconditional {@link #put}: a plugin that
     * registered structures (via {@code addBlockDirect}) during {@code onEnable},
     * before the async disk read landed, had those nodes silently wiped by the
     * stale saved graph a tick later. This merges instead:
     *
     * <ul>
     *   <li>{@code loaded} null/empty → the live graph is left exactly as-is;</li>
     *   <li>no live graph yet (or an empty one) → {@code loaded} becomes the live
     *       graph wholesale — the same effect the old {@code put} had, for the
     *       common fresh-boot case where nothing was registered first;</li>
     *   <li>otherwise every position already live is kept untouched (spec, grounded
     *       flag, damage, reinforcement, temperature factor, and its live edges),
     *       and every persisted-only position is added carrying its full saved
     *       state, with the exact edges {@code loaded} recorded for it reproduced
     *       via {@link StructureGraph#connect} (never grid-re-derived, so explicit /
     *       severed topology survives).</li>
     * </ul>
     *
     * <p>Because this is a pure function of {@code (live, loaded)} run on the main
     * thread, two loads racing for the same world (a {@code WorldLoadEvent} plus the
     * bulk boot load) are idempotent: the second finds every saved node already
     * present and changes nothing.
     */
    void mergeLoaded(UUID worldId, StructureGraph loaded) {
        if (loaded == null || loaded.isEmpty()) {
            return; // nothing to merge — leave any live graph untouched
        }
        StructureGraph live = worldGraphs.get(worldId);
        if (live == null || live.isEmpty()) {
            worldGraphs.put(worldId, loaded); // nothing live to protect — install wholesale
            return;
        }
        // Add persisted-only nodes with their full saved state. Live nodes at shared
        // positions are left untouched (live wins). Collect the added positions so we
        // can reproduce their edges once every node is present.
        List<NodePos> added = new ArrayList<>();
        for (NodePos pos : loaded.getAllPositions()) {
            if (live.hasBlock(pos)) {
                continue; // live wins on conflict
            }
            Node src = loaded.getNode(pos);
            live.addNode(pos, src.spec(), src.isGrounded());
            Node dst = live.getNode(pos);
            dst.addDamage(src.damage());
            dst.setReinforcement(src.reinforcement());
            dst.setTemperatureCapacityFactor(src.temperatureCapacityFactor());
            added.add(pos);
        }
        // Reproduce each added node's persisted edges to any node now present. Using
        // the recorded neighbours (not a grid derivation) keeps explicit/severed
        // topology exact. connect() is symmetric and idempotent, so the result does
        // not depend on iteration order.
        for (NodePos pos : added) {
            for (NodePos neighbor : loaded.getNeighbors(pos)) {
                if (live.hasBlock(neighbor)) {
                    live.connect(pos, neighbor);
                }
            }
        }
    }

    /** Get the raw graph by id (may be null). Used by the persistence save snapshot. */
    StructureGraph get(UUID worldId) {
        return worldGraphs.get(worldId);
    }

    /** A live view of the per-world graph entries (for the save-all snapshot). */
    Map<UUID, StructureGraph> entries() {
        return worldGraphs;
    }

    /** Clear all tracked structures (e.g., on plugin reload). */
    void clearAll() {
        worldGraphs.clear();
    }

    /** Clear structures for a specific world. */
    void clearWorld(World world) {
        worldGraphs.remove(world.getUID());
    }

    /** Total blocks tracked across all worlds. */
    int totalTrackedBlocks() {
        int total = 0;
        for (StructureGraph graph : worldGraphs.values()) {
            total += graph.size();
        }
        return total;
    }

    /**
     * Mark a world's structures as changed, bumping its revision. Read-only
     * caches (grade, visualizer scan) compare against this to know when to
     * refresh. Call after any structural mutation.
     */
    void markDirty(World world) {
        if (world != null) {
            worldRevision.merge(world.getUID(), 1L, Long::sum);
        }
    }

    /** Current change-revision for a world (0 if never touched). */
    long revision(World world) {
        return worldRevision.getOrDefault(world.getUID(), 0L);
    }
}
