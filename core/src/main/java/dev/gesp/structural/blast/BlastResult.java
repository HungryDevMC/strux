package dev.gesp.structural.blast;

import dev.gesp.structural.model.NodePos;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The outcome of an explosion, split so an adapter can render each effect
 * differently.
 *
 * <p>The three outcome sets are pairwise disjoint: a block is shattered directly,
 * OR it fell in the cascade, OR it survived weakened — never two at once. A block
 * cracked by the shockwave that then collapses is reported only in
 * {@code collapsed} (it is gone, not a weakened survivor).
 *
 * @param destroyed     blocks shattered directly by the blast (the crater)
 * @param collapsed     blocks that fell afterwards in the structural cascade
 * @param damaged       blocks that survived but took persistent damage
 *                      (position → new damage level 0..1). Disjoint from
 *                      {@code destroyed} and {@code collapsed}.
 * @param finalStress   post-settle stress fraction of every surviving block
 * @param affectedScope the region the scoped settle considered — the union of the
 *                      dependent subgraphs of the disturbed blocks plus the severed
 *                      stubs the settle expanded into. An adapter can run a scoped
 *                      ground-refresh ({@code findFloatingInScope}) over exactly this
 *                      region instead of rescanning the whole graph; it is a superset
 *                      of every position this blast could have made float. Captured at
 *                      the settle's MAXIMAL extent (before removals prune it), so
 *                      removed/collapsed positions are present — a scoped refresh
 *                      simply skips ones no longer in the graph.
 */
public record BlastResult(
        List<NodePos> destroyed,
        List<NodePos> collapsed,
        Map<NodePos, Double> damaged,
        Map<NodePos, Double> finalStress,
        Set<NodePos> affectedScope) {

    public BlastResult {
        destroyed = destroyed == null ? List.of() : List.copyOf(destroyed);
        collapsed = collapsed == null ? List.of() : List.copyOf(collapsed);
        damaged = damaged == null ? Map.of() : Map.copyOf(damaged);
        finalStress = finalStress == null ? Map.of() : Map.copyOf(finalStress);
        affectedScope = affectedScope == null ? Set.of() : Set.copyOf(affectedScope);
    }

    public static BlastResult empty() {
        return new BlastResult(List.of(), List.of(), Map.of(), Map.of(), Set.of());
    }

    /** Total blocks removed from the world (crater + cascade). */
    public int totalRemoved() {
        return destroyed.size() + collapsed.size();
    }
}
