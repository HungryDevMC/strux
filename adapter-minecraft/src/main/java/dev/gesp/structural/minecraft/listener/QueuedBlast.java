package dev.gesp.structural.minecraft.listener;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * One explosion captured at event time, waiting in the {@link BlastProcessor}
 * queue to be settled on a later tick.
 *
 * <pre>
 *   The Bukkit explosion event can't afford to solve a huge structure on the
 *   server thread — several explosions in one tick (a TNT chain, a cannon volley)
 *   would all solve in that one tick and freeze the game. So at event time we only
 *   do the cheap, event-bound work (gate, claim our tracked blocks out of vanilla's
 *   destroy list) and snapshot everything the solve needs (which world, where the
 *   blast centred, how powerful) into one of these, dropped in a queue. A repeating
 *   task drains the queue a little each tick, so no single tick is ever frozen.
 * </pre>
 *
 * <p>Nothing here points back at the live explosion entity: by the time we settle
 * the blast the TNT/creeper is long gone, so everything needed is copied in by value.
 *
 * @param world  the world the explosion happened in
 * @param center where the blast centred (drives the core solve, the collapse batch + effects)
 * @param power  the explosion power (vanilla blast radius, fed to the strux blast model)
 * @param actorId opaque identifier of who set off the blast (the TNT igniter's / explosion
 *               source entity's UUID as a string), or {@code null} for a natural/anonymous
 *               explosion — copied in by value like everything else, since the source entity
 *               is long gone by the time the blast settles
 */
public record QueuedBlast(World world, Location center, double power, String actorId) {

    /** Unattributed blast (no actor) — the pre-schema-v2 shape. */
    public QueuedBlast(World world, Location center, double power) {
        this(world, center, power, null);
    }
}
