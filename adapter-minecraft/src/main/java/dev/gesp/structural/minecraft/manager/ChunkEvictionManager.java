package dev.gesp.structural.minecraft.manager;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.config.MemoryEvictionConfig;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.persistence.StructureData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Chunk-driven bookkeeping that decides <em>when</em> a dormant structure is evicted from
 * the live graph, and restores it lazily on load (SCALING.md §5–§6, "Pro durability").
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │            THE UNIT IS THE COMPONENT, THE TRIGGER IS THE CHUNK      │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  We track which chunk COLUMNS (x>>4, z>>4) are loaded, per world.   │
 *   │  A component's touched columns come from its node positions. A      │
 *   │  component is evicted only when EVERY column it touches is          │
 *   │  unloaded — so support can never be evicted out from under a live   │
 *   │  block (anything connected to a live block shares its component,    │
 *   │  which therefore still touches a loaded column and stays resident). │
 *   │                                                                     │
 *   │  Eviction is deferred grace-ticks after a ChunkUnloadEvent and      │
 *   │  re-checks the column is still unloaded, so a player travelling     │
 *   │  back and forth doesn't thrash. All work is on the main thread, so  │
 *   │  no async write can race a reload.                                  │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>The Bukkit event bridge is {@code ChunkEvictionListener}; the graph mechanism is
 * {@link ComponentEvictor}. This class is driven entirely through
 * {@link #onChunkLoad}/{@link #onChunkUnload}/{@link #runGraceCheck}, which are directly
 * callable from tests (no scheduler dependency).
 */
public final class ChunkEvictionManager {

    private final StructureManager structureManager;
    private final ComponentEvictor evictor;
    private final MemoryEvictionConfig config;
    private final Plugin plugin; // nullable in tests; drives the grace delay when present

    /** Currently loaded chunk columns, per world. */
    private final Map<UUID, Set<Long>> loadedColumns = new HashMap<>();

    /** Parked components, indexed by every column they touch (per world). */
    private final Map<UUID, Map<Long, List<Sidecar>>> evictedIndex = new HashMap<>();

    /** One parked component: its snapshot plus the columns it occupied. */
    private record Sidecar(StructureData data, Set<Long> columns) {}

    public ChunkEvictionManager(
            StructureManager structureManager, ComponentEvictor evictor, MemoryEvictionConfig config, Plugin plugin) {
        this.structureManager = structureManager;
        this.evictor = evictor;
        this.config = config;
        this.plugin = plugin;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  CHUNK EVENTS
    // ─────────────────────────────────────────────────────────────────────

    /** A chunk loaded: mark its column resident and restore anything parked there. */
    public void onChunkLoad(World world, int cx, int cz) {
        if (!config.isEnabled()) {
            return;
        }
        UUID id = world.getUID();
        long col = packColumn(cx, cz);
        loadedColumns.computeIfAbsent(id, k -> new HashSet<>()).add(col);
        rematerializeColumn(world, col);
    }

    /** A chunk unloaded: drop its column and schedule an eviction grace-check. */
    public void onChunkUnload(World world, int cx, int cz) {
        if (!config.isEnabled()) {
            return;
        }
        UUID id = world.getUID();
        long col = packColumn(cx, cz);
        Set<Long> loaded = loadedColumns.get(id);
        if (loaded != null) {
            loaded.remove(col);
        }
        int grace = config.getGraceTicks();
        if (plugin != null && grace > 0) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> runGraceCheck(world, cx, cz), grace);
        } else {
            runGraceCheck(world, cx, cz);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  EVICTION (grace-check) & RE-MATERIALIZATION
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Evaluate the column for eviction. No-op if the column has reloaded since the unload
     * (churn guard). For each still-tracked node in the column, take its connected
     * component and evict it iff every column it touches is unloaded and it carries no
     * thermally-softened node.
     */
    public void runGraceCheck(World world, int cx, int cz) {
        if (!config.isEnabled()) {
            return;
        }
        UUID id = world.getUID();
        long col = packColumn(cx, cz);
        Set<Long> loaded = loadedColumns.getOrDefault(id, Set.of());
        if (loaded.contains(col)) {
            return; // reloaded during grace — leave it resident
        }
        StructureGraph graph = structureManager.getGraph(world);
        if (graph == null) {
            return;
        }

        List<NodePos> inColumn = new ArrayList<>();
        for (NodePos pos : graph.getAllPositions()) {
            if (packColumn(pos.x() >> 4, pos.z() >> 4) == col) {
                inColumn.add(pos);
            }
        }
        if (inColumn.isEmpty()) {
            return;
        }

        Set<NodePos> processed = new HashSet<>();
        boolean evictedAny = false;
        for (NodePos seed : inColumn) {
            if (processed.contains(seed)) {
                continue;
            }
            Set<NodePos> component = graph.componentOf(seed);
            processed.addAll(component);
            if (component.isEmpty()) {
                continue;
            }
            Set<Long> columns = columnsOf(component);
            if (touchesLoaded(loaded, columns)) {
                continue; // straddles a loaded chunk → stays fully resident
            }
            if (!ComponentEvictor.isThermallyResident(graph, component)) {
                continue; // thermal softening would not survive the round-trip → refuse
            }
            StructureData sidecar = evictor.evict(graph, component, id.toString());
            index(id, new Sidecar(sidecar, columns));
            evictedAny = true;
        }
        if (evictedAny) {
            structureManager.markDirty(world);
        }
    }

    /**
     * Restore any component parked at a position's column if it actually contains that
     * position — a defensive hook for events that somehow address an evicted position.
     */
    public void ensureResident(World world, NodePos pos) {
        if (!config.isEnabled()) {
            return;
        }
        rematerializeColumn(world, packColumn(pos.x() >> 4, pos.z() >> 4));
    }

    private void rematerializeColumn(World world, long col) {
        UUID id = world.getUID();
        Map<Long, List<Sidecar>> index = evictedIndex.get(id);
        if (index == null) {
            return;
        }
        List<Sidecar> here = index.get(col);
        if (here == null || here.isEmpty()) {
            return;
        }
        StructureGraph graph = structureManager.getOrCreateGraph(world);
        // Copy — restoring mutates the index (de-indexing across all the sidecar's columns).
        for (Sidecar sidecar : new ArrayList<>(here)) {
            evictor.rematerialize(graph, sidecar.data());
            deindex(id, sidecar);
        }
        structureManager.markDirty(world);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  INDEX HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private void index(UUID id, Sidecar sidecar) {
        Map<Long, List<Sidecar>> index = evictedIndex.computeIfAbsent(id, k -> new HashMap<>());
        for (long col : sidecar.columns()) {
            index.computeIfAbsent(col, k -> new ArrayList<>()).add(sidecar);
        }
    }

    private void deindex(UUID id, Sidecar sidecar) {
        Map<Long, List<Sidecar>> index = evictedIndex.get(id);
        if (index == null) {
            return;
        }
        for (long col : sidecar.columns()) {
            List<Sidecar> here = index.get(col);
            if (here != null) {
                here.remove(sidecar);
                if (here.isEmpty()) {
                    index.remove(col);
                }
            }
        }
    }

    private static Set<Long> columnsOf(Set<NodePos> component) {
        Set<Long> columns = new HashSet<>();
        for (NodePos pos : component) {
            columns.add(packColumn(pos.x() >> 4, pos.z() >> 4));
        }
        return columns;
    }

    private static boolean touchesLoaded(Set<Long> loaded, Set<Long> columns) {
        for (long col : columns) {
            if (loaded.contains(col)) {
                return true;
            }
        }
        return false;
    }

    /** Pack a chunk-column coordinate pair into one long key. */
    static long packColumn(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    /** Number of components currently parked in sidecars across all worlds (for tests/metrics). */
    public int parkedComponentCount() {
        Set<Sidecar> unique = new HashSet<>();
        for (Map<Long, List<Sidecar>> index : evictedIndex.values()) {
            for (List<Sidecar> here : index.values()) {
                unique.addAll(here);
            }
        }
        return unique.size();
    }
}
