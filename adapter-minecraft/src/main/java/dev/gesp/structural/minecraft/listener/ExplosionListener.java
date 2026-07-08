package dev.gesp.structural.minecraft.listener;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.protect.CollapseGuard;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.projectiles.ProjectileSource;

/**
 * Routes explosions through the strux blast model.
 *
 * <pre>
 *   Vanilla keeps cratering untracked terrain (we don't model that).
 *   For blocks strux DOES track, strux decides: direct destruction (crater),
 *   persistent damage (cracks), and the secondary collapse from lost supports.
 * </pre>
 *
 * <p><b>Deferred settle.</b> The actual blast solve does NOT run inside this event
 * handler. Several explosions in one tick (a TNT chain) would all solve in that one
 * tick and freeze the server. So at event time we only do the cheap, event-bound
 * work: gate (skip protected regions / disabled worlds), claim our tracked blocks
 * out of vanilla's destroy list (the event's block list is only mutable here), and
 * queue the blast. A {@link BlastProcessor} drains the queue a little each tick
 * under a wall-clock budget. Collapsed/destroyed blocks are then removed cleanly
 * (same as the break-cascade) so the world never drifts out of sync with the graph.
 */
public class ExplosionListener implements Listener {

    private final StructureManager structureManager;
    private final CollapseGuard guard;
    private final BlastProcessor blastProcessor;

    public ExplosionListener(StructureManager structureManager, CollapseGuard guard, BlastProcessor blastProcessor) {
        this.structureManager = structureManager;
        this.guard = guard;
        this.blastProcessor = blastProcessor;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        Entity source = event.getEntity();
        handle(event.getLocation(), event.blockList(), powerFor(source), actorFor(source));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        // A block-driven explosion (bed/respawn anchor) has no entity source to attribute.
        handle(event.getBlock().getLocation(), event.blockList(), 5.0, null);
    }

    /**
     * Cheap, event-bound work only: gate, claim tracked blocks out of vanilla's
     * destroy list (mutable only during the event), and enqueue the blast. The
     * {@link BlastProcessor} performs the solve a later tick under a budget.
     */
    private void handle(Location center, List<Block> vanillaBlocks, double power, String actorId) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        StructureGraph graph = structureManager.getGraph(world);
        if (graph == null || graph.isEmpty()) {
            return;
        }

        // Trigger gate: leave protected regions / disabled worlds to vanilla explosion handling.
        if (!guard.physicsAllowed(center)) {
            return;
        }

        // Claim our tracked blocks out of vanilla's destroy list NOW — the list is
        // only mutable during the event, so vanilla won't crater what strux owns.
        vanillaBlocks.removeIf(b -> graph.hasBlock(StructureManager.toBlockPos(b)));

        // Queue the blast; the processor settles it a little later, under budget.
        blastProcessor.enqueue(new QueuedBlast(world, center, power, actorId));
    }

    /**
     * Who set off this explosion, as a UUID string, or {@code null} when no responsible
     * entity can be named (a natural/anonymous blast).
     *
     * <pre>
     *   Primed TNT  → whoever primed it (TNTPrimed.getSource), so a player's TNT is
     *                 attributed to the player, not the anonymous TNT entity; if the
     *                 priming source is gone, fall back to the TNT entity itself.
     *   Fireball    → its shooter (a ghast / a player redirecting it), when that shooter
     *                 is an entity; a dispenser-fired fireball has a block source → null.
     *   Otherwise   → the exploding entity itself (a creeper is its own actor).
     * </pre>
     */
    private String actorFor(Entity entity) {
        if (entity == null) {
            return null;
        }
        if (entity instanceof TNTPrimed tnt) {
            Entity igniter = tnt.getSource();
            return idOf(igniter != null ? igniter : tnt);
        }
        if (entity instanceof Fireball fireball) {
            ProjectileSource shooter = fireball.getShooter();
            return shooter instanceof Entity shooterEntity ? idOf(shooterEntity) : null;
        }
        return idOf(entity);
    }

    private String idOf(Entity entity) {
        return entity == null ? null : entity.getUniqueId().toString();
    }

    private double powerFor(Entity entity) {
        if (entity instanceof Creeper creeper) {
            return creeper.isPowered() ? 6.0 : 3.0;
        }
        if (entity instanceof TNTPrimed) {
            return 4.0;
        }
        if (entity instanceof Fireball) {
            return 4.0;
        }
        return 4.0;
    }
}
