package dev.gesp.structural.minecraft.cache;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.World;

/**
 * A per-world, revision-cached set of the nodes matching a fixed predicate.
 *
 * <pre>
 *   ┌──────────────────────────────────────────────────────────────────────┐
 *   │                   REVISION-CACHED NODE VIEW                           │
 *   ├──────────────────────────────────────────────────────────────────────┤
 *   │                                                                      │
 *   │  Several periodic tasks only care about a tiny subset of a world's   │
 *   │  nodes — the stressed ones, the cracked ones, the weak ones. That    │
 *   │  subset only changes when the structure changes, so each task used   │
 *   │  to keep its own cache keyed by the world's change-revision and      │
 *   │  rebuilt with a full scan only on a revision bump. This is that      │
 *   │  caching mechanism, extracted once:                                  │
 *   │                                                                      │
 *   │    static world (revision unchanged) → return the cached set, O(K)   │
 *   │    world changed (revision bumped)   → full scan, refilter, O(N)     │
 *   │                                                                      │
 *   │  Each consumer owns its own view with its own predicate; only the    │
 *   │  predicate differs.                                                  │
 *   │                                                                      │
 *   └──────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p><b>Why the revision signal is sufficient (the freeze-cache correctness
 * argument, stated once for all consumers).</b> The cached set can only go stale
 * if a node's predicate result changes without the world's revision bumping. The
 * predicates here read a node's {@code stressPercent}, {@code damage}, or crack
 * level. {@code damage} is only changed by blast/impact, which mark the world
 * dirty. {@code stressPercent}/crack level are derived from a re-solve, and every
 * re-solve path ({@code place/break/cascade/blast/impact/fire/reinforce/repair/
 * load}) calls {@code StructureManager.markDirty}, bumping the revision. There is
 * therefore no path that changes a node's predicate result without bumping the
 * revision, so the revision is a complete rebuild signal and the cache cannot go
 * stale.
 *
 * <p>Not thread-safe: like the per-task caches it replaces, it is touched only on
 * the main server thread.
 */
public final class RevisionCachedNodeView {

    private final StructureManager structureManager;
    private final Predicate<Node> filter;

    private final Map<UUID, Long> scannedRevision = new HashMap<>();
    private final Map<UUID, Set<NodePos>> cache = new HashMap<>();

    /**
     * Nodes scanned by the most recent {@link #nodes} call: the full graph size
     * when that call rebuilt the set, or {@code -1} when it reused the cache. Lets
     * a consumer charge the rebuild's true cost (the whole graph) on a pass that
     * actually rescanned, instead of the cheap cached-set size.
     */
    private int lastScanned = -1;

    public RevisionCachedNodeView(StructureManager structureManager, Predicate<Node> filter) {
        this.structureManager = structureManager;
        this.filter = filter;
    }

    /**
     * The cached set of positions whose node matches the predicate, rebuilt with a
     * full scan only when the world's revision has changed since the last rebuild.
     *
     * <p>After this call {@link #lastScanned()} reports the rebuild scan size, or
     * {@code -1} when the cache was reused.
     */
    public Set<NodePos> nodes(World world, StructureGraph graph) {
        UUID id = world.getUID();
        long rev = structureManager.revision(world);
        if (rev == scannedRevision.getOrDefault(id, -1L)) {
            Set<NodePos> cached = cache.get(id);
            if (cached != null) {
                lastScanned = -1; // reused the cache — no full scan this call
                return cached;
            }
        }
        Set<NodePos> matched = new HashSet<>();
        int scanned = 0;
        for (Node node : graph.getAllNodes()) {
            scanned++;
            if (filter.test(node)) {
                matched.add(node.pos());
            }
        }
        cache.put(id, matched);
        scannedRevision.put(id, rev);
        lastScanned = scanned; // a full rebuild scan happened this call
        return matched;
    }

    /**
     * Nodes scanned by the most recent {@link #nodes} call: the full graph size on
     * a rebuild, or {@code -1} when the cache was reused.
     */
    public int lastScanned() {
        return lastScanned;
    }
}
