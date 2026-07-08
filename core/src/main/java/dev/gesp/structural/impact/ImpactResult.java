package dev.gesp.structural.impact;

import dev.gesp.structural.model.NodePos;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The outcome of one impact, split so an adapter can render each effect
 * differently.
 *
 * @param penetrated    blocks the projectile punched clean through (shattered on
 *                      impact because they absorbed their full toughness worth of
 *                      energy), in path order from the surface inward
 * @param collapsed     blocks that fell afterwards in the secondary structural
 *                      cascade (lost support or were already weak enough to fail)
 * @param damaged       blocks that survived but took persistent crack damage
 *                      (position → new damage level 0..1)
 * @param affectedScope the region the scoped settle considered — the union of the
 *                      dependent subgraphs of the disturbed blocks plus the severed
 *                      stubs the settle expanded into. An adapter can run a scoped
 *                      ground-refresh ({@code findFloatingInScope}) over exactly this
 *                      region instead of rescanning the whole graph; it is a superset
 *                      of every position this impact could have made float. Captured at
 *                      the settle's MAXIMAL extent (before removals prune it), so
 *                      removed/collapsed positions are present — a scoped refresh
 *                      simply skips ones no longer in the graph.
 * @param truncated     true if the settle hit the step cap while collapse work still
 *                      remained — the adapter should continue the settle on subsequent
 *                      ticks until the structure is stable
 */
public record ImpactResult(
        List<NodePos> penetrated,
        List<NodePos> collapsed,
        Map<NodePos, Double> damaged,
        Set<NodePos> affectedScope,
        boolean truncated) {

    public ImpactResult {
        penetrated = penetrated == null ? List.of() : List.copyOf(penetrated);
        collapsed = collapsed == null ? List.of() : List.copyOf(collapsed);
        damaged = damaged == null ? Map.of() : Map.copyOf(damaged);
        affectedScope = affectedScope == null ? Set.of() : Set.copyOf(affectedScope);
    }

    public static ImpactResult empty() {
        return new ImpactResult(List.of(), List.of(), Map.of(), Set.of(), false);
    }

    /** Total blocks removed from the world (punched through + cascade). */
    public int totalRemoved() {
        return penetrated.size() + collapsed.size();
    }
}
