package dev.gesp.structural.minecraft.manager;

import dev.gesp.structural.assess.CollapseAtlas;
import dev.gesp.structural.assess.StructureGrader;
import dev.gesp.structural.assess.StructureReport;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.solver.StressSolver;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.World;

/**
 * The read-side caches keyed on a world's change-revision.
 *
 * <p>A world's grade can only change when the world changes, so {@link #assessWorld}
 * returns a cached {@link StructureReport} (no solve, no grade) while the revision
 * is unchanged — this is what makes {@code %strux_grade%} / scoreboards near-free
 * to poll. The {@link CollapseAtlas} is cached per world too; it is rebuilt only if
 * the world's graph instance was swapped (e.g. reload), and self-invalidates on
 * topology change.
 *
 * <p><b>Rate limiting.</b> During a cascade, every event bumps the revision. A naive
 * cache that re-solves on every revision change would cause a scoreboard polling
 * {@code %strux_grade%} to trigger many solves per second — freezing the server. So
 * even when the revision IS different, we skip the re-solve if we solved less than
 * {@link #MIN_SOLVE_INTERVAL_MS} ago. The cached report is at most that stale, and
 * the scoreboard stays responsive during collapses.
 */
final class WorldAssessmentCache {

    /**
     * Default minimum interval between re-solves for the same world. Prevents
     * scoreboard polls from triggering many solves per second during a cascade.
     */
    static final long DEFAULT_MIN_SOLVE_INTERVAL_MS = 500;

    private final WorldGraphStore graphStore;
    private final StressSolver stressSolver;
    private final long minSolveIntervalMs;

    private final Map<UUID, CachedReport> reportCache = new HashMap<>();

    private record CachedReport(long revision, long solvedAt, StructureReport report) {}

    // Collapse atlas per world (lazily built, self-invalidating on topology change).
    private final Map<UUID, CollapseAtlas> atlases = new HashMap<>();

    WorldAssessmentCache(WorldGraphStore graphStore, StressSolver stressSolver) {
        this(graphStore, stressSolver, DEFAULT_MIN_SOLVE_INTERVAL_MS);
    }

    WorldAssessmentCache(WorldGraphStore graphStore, StressSolver stressSolver, long minSolveIntervalMs) {
        this.graphStore = graphStore;
        this.stressSolver = stressSolver;
        this.minSolveIntervalMs = minSolveIntervalMs;
    }

    /**
     * The collapse atlas for a world, bound to its current graph. Rebuilt only if
     * the world's graph instance was swapped (e.g. reload); the atlas itself
     * self-invalidates on topology change.
     */
    CollapseAtlas atlasFor(World world) {
        StructureGraph graph = graphStore.getGraph(world);
        if (graph == null) {
            return null;
        }
        CollapseAtlas atlas = atlases.get(world.getUID());
        if (atlas == null || atlas.graph() != graph) {
            atlas = new CollapseAtlas(graph);
            atlases.put(world.getUID(), atlas);
        }
        return atlas;
    }

    /**
     * Solve and grade every tracked structure in a world.
     *
     * @return the structural grade report (S grade / zeros if nothing tracked)
     */
    StructureReport assessWorld(World world) {
        StructureGraph graph = graphStore.getGraph(world);
        if (graph == null || graph.isEmpty()) {
            return StructureReport.empty();
        }
        // FREEZE: a world's grade can only change when the world changes. Return
        // the cached report (no solve, no grade) while the revision is unchanged —
        // this is what makes %strux_grade% / scoreboards near-free to poll.
        long rev = graphStore.revision(world);
        UUID id = world.getUID();
        CachedReport cached = reportCache.get(id);
        if (cached != null && cached.revision == rev) {
            return cached.report;
        }
        // Rate-limit: even if the revision changed, don't re-solve if we solved
        // recently. Prevents cascades from triggering many solves per second.
        long now = System.currentTimeMillis();
        if (cached != null && minSolveIntervalMs > 0 && (now - cached.solvedAt) < minSolveIntervalMs) {
            return cached.report;
        }
        stressSolver.solveAll(graph);
        StructureReport report = StructureGrader.assess(graph);
        reportCache.put(id, new CachedReport(rev, now, report));
        return report;
    }
}
