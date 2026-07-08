package dev.gesp.structural.minecraft.listener;

import dev.gesp.structural.model.NodePos;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * One projectile hit captured at event time, waiting in the {@link ImpactProcessor}
 * queue to be settled on a later tick.
 *
 * <pre>
 *   The event handler can't afford to settle a huge structure on the server
 *   thread — one bad hit could freeze the whole game. So instead of solving
 *   right there, we snapshot everything the solve needs (where it hit, how hard,
 *   which way it was going) into one of these and drop it in a queue. A repeating
 *   task drains the queue a little each tick, so no single tick is ever frozen.
 * </pre>
 *
 * <p>Nothing here points back at the live projectile entity: by the time we
 * process the impact the arrow may have despawned, so everything needed is
 * copied in by value.
 *
 * @param world         the world the hit happened in
 * @param origin        the block the projectile struck (the settle's start point)
 * @param hitLocation   where the hit landed (used to centre the collapse batch + effects)
 * @param dirX          travel direction X (drives penetration ray-cast)
 * @param dirY          travel direction Y
 * @param dirZ          travel direction Z
 * @param energy        kinetic energy carried into the structure
 * @param projectileType the projectile's {@code EntityType} name (for the recording log)
 * @param actorId       opaque identifier of who fired the projectile (the shooter's UUID as a
 *                      string), or {@code null} when the shooter is unknown / not an entity —
 *                      captured at event time because the projectile may despawn before settle
 */
public record QueuedImpact(
        World world,
        NodePos origin,
        Location hitLocation,
        double dirX,
        double dirY,
        double dirZ,
        double energy,
        String projectileType,
        String actorId) {

    /** Unattributed impact (no shooter) — the pre-schema-v2 shape. */
    public QueuedImpact(
            World world,
            NodePos origin,
            Location hitLocation,
            double dirX,
            double dirY,
            double dirZ,
            double energy,
            String projectileType) {
        this(world, origin, hitLocation, dirX, dirY, dirZ, energy, projectileType, null);
    }
}
