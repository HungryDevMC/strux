package dev.gesp.structural.recording;

import dev.gesp.structural.model.NodePos;
import java.util.List;
import java.util.Map;

/**
 * Event recorded when a projectile or entity impacts a structure.
 *
 * @param timestampMs  wall-clock time
 * @param sequenceId   monotonic sequence for replay ordering
 * @param pos          position of the impacted block
 * @param projectileId projectile type identifier (e.g., "ARROW", "FIREBALL")
 * @param energy       kinetic energy of the impact
 * @param damageDealt  damage THIS hit applied to the impacted (origin) block (0.0-1.0):
 *                     the per-hit delta to its persistent damage, NOT its running total.
 *                     For a surviving block this is the crack this single hit added; for
 *                     the killing blow it is the remaining damage that finished the block
 *                     off (1.0 − its damage before the hit), so a destroying hit is never
 *                     recorded as 0. Replay uses the richer {@code pathDamage} when
 *                     present (a NEW recording); this scalar is the LEGACY origin-only
 *                     fallback for recordings that predate the path fields.
 * @param destroyed    whether the impact punched through at least one block
 *                     ({@code penetrated} is non-empty)
 * @param collapsed    positions of blocks that collapsed as a result
 * @param penetrated   blocks the projectile PUNCHED CLEAN THROUGH along its path
 *                     (removed from the structure), in path order. A high-energy
 *                     impact bores past the origin; recording only the origin would
 *                     drift the replay pre-state and poison every later comparison.
 *                     Empty for a non-penetrating hit or an old recording.
 * @param pathDamage   per-block ABSOLUTE post-hit damage (0..1) for every surviving
 *                     block the projectile cracked along its path (not the penetrated
 *                     ones). Replay sets each block to this level so accumulated
 *                     multi-hit cracks reproduce exactly. Empty for an old recording,
 *                     in which case replay falls back to applying {@code damageDealt}
 *                     to the origin alone.
 */
public record ImpactEvent(
        long timestampMs,
        long sequenceId,
        NodePos pos,
        String projectileId,
        double energy,
        double damageDealt,
        boolean destroyed,
        List<NodePos> collapsed,
        List<NodePos> penetrated,
        Map<NodePos, Double> pathDamage,
        String actorId)
        implements StruxEvent {

    public ImpactEvent {
        collapsed = collapsed == null ? List.of() : List.copyOf(collapsed);
        penetrated = penetrated == null ? List.of() : List.copyOf(penetrated);
        pathDamage = pathDamage == null ? Map.of() : Map.copyOf(pathDamage);
    }

    /** Attributed impact carrying the path fields but no actor — delegates with {@code actorId = null}. */
    public ImpactEvent(
            long timestampMs,
            long sequenceId,
            NodePos pos,
            String projectileId,
            double energy,
            double damageDealt,
            boolean destroyed,
            List<NodePos> collapsed,
            List<NodePos> penetrated,
            Map<NodePos, Double> pathDamage) {
        this(
                timestampMs,
                sequenceId,
                pos,
                projectileId,
                energy,
                damageDealt,
                destroyed,
                collapsed,
                penetrated,
                pathDamage,
                null);
    }

    /**
     * Legacy constructor for recordings that predate the path fields: no
     * penetrated list, no per-block path damage. Replay treats such an event as
     * origin-only (the old behaviour) via the empty path fields.
     */
    public ImpactEvent(
            long timestampMs,
            long sequenceId,
            NodePos pos,
            String projectileId,
            double energy,
            double damageDealt,
            boolean destroyed,
            List<NodePos> collapsed) {
        this(
                timestampMs,
                sequenceId,
                pos,
                projectileId,
                energy,
                damageDealt,
                destroyed,
                collapsed,
                List.of(),
                Map.of(),
                null);
    }

    @Override
    public EventType type() {
        return EventType.IMPACT;
    }
}
