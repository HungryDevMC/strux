package dev.gesp.structural.minecraft.listener;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.protect.CollapseGuard;
import dev.gesp.structural.model.NodePos;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

/**
 * Routes projectile/ram hits through the strux impact model.
 *
 * <pre>
 *   The damage a hit does is NOT a per-projectile table. We read the
 *   projectile's live velocity and its physical mass, compute kinetic energy
 *   E = ½·m·v², and hand that single scalar to the core ImpactEngine. The
 *   engine spends it against material toughness: a light, slow hit only cracks
 *   the surface (and ten of them accumulate to break it); a heavy, fast hit
 *   punches through and undermines what's behind.
 * </pre>
 *
 * <p>Velocity lives only in the game world, so the energy is computed here; the
 * unit-agnostic core merely spends it.
 *
 * <p><b>Why the handler only queues.</b> Settling the structure can be heavy, and
 * on a pathological build one settle once froze the server for tens of seconds.
 * Nothing in the event consumes the settle result, so the hit is naturally
 * deferrable: we snapshot what the settle needs into a {@link QueuedImpact} and
 * hand it to the {@link ImpactProcessor}, which drains the queue under a per-tick
 * wall-clock budget. A whole volley lands in one tick but settles in FIFO order
 * across as many ticks as the budget needs — no single tick is ever frozen.
 *
 * <p><b>One projectile, one impact.</b> Minecraft re-fires {@link ProjectileHitEvent}
 * for the same arrow more than once — an arrow that embeds in or slides along a block
 * keeps reporting hits, each carrying its frozen landing velocity. Left unchecked, one
 * stuck arrow re-applies block damage tick after tick (a real recording showed two
 * arrows logging three byte-identical impacts each). We guard against that two ways:
 * an arrow already resting in a block ({@link AbstractArrow#isInBlock()}) is dropped
 * outright, and every projectile's UUID is remembered the first time it deals a block
 * impact so any later re-fire of the same projectile is ignored.
 */
public class ProjectileImpactListener implements Listener {

    /**
     * Upper bound on the remembered-projectile set so it can never grow without
     * limit on a busy server. One entry per projectile that has dealt a block
     * impact is plenty; when the cap is reached the oldest UUID is evicted. A
     * projectile lives only a handful of seconds, so an evicted-then-re-firing
     * arrow is vanishingly unlikely and would at worst deal one extra impact.
     */
    private static final int MAX_REMEMBERED_PROJECTILES = 4096;

    private final StructureManager structureManager;
    private final ImpactProcessor processor;
    private final CollapseGuard guard;
    private final boolean enabled;
    private final double energyScale;

    /**
     * Projectiles that have already dealt a block impact. Insertion-ordered so the
     * oldest entry is evicted first when the cap is hit. Touched only on the main
     * thread (Bukkit events are single-threaded), so it needs no synchronisation.
     */
    private final Set<UUID> impactedProjectiles = new LinkedHashSet<>();

    /**
     * Cap on {@link #impactedProjectiles}. A field (not the constant directly) only
     * so a test can shrink it and exercise the eviction path without firing
     * thousands of arrows; production always uses {@link #MAX_REMEMBERED_PROJECTILES}.
     */
    private int maxRememberedProjectiles = MAX_REMEMBERED_PROJECTILES;

    public ProjectileImpactListener(
            StructureManager structureManager,
            ImpactProcessor processor,
            CollapseGuard guard,
            boolean enabled,
            double energyScale) {
        this.structureManager = structureManager;
        this.processor = processor;
        this.guard = guard;
        this.enabled = enabled;
        this.energyScale = energyScale;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!enabled) {
            return;
        }
        Block hitBlock = event.getHitBlock();
        if (hitBlock == null) {
            return; // hit an entity or fizzled in the air — nothing structural
        }
        Projectile projectile = event.getEntity();
        // An arrow already embedded in (or sliding along) a block is at rest: its hit
        // already landed. Minecraft keeps re-firing the event for it, so dropping it
        // here stops a stuck arrow from chipping the block every tick.
        if (projectile instanceof AbstractArrow arrow && arrow.isInBlock()) {
            return;
        }
        World world = hitBlock.getWorld();

        StructureGraph graph = structureManager.getGraph(world);
        NodePos origin = StructureManager.toBlockPos(hitBlock);
        if (graph == null || !graph.hasBlock(origin)) {
            return; // not a block strux is tracking
        }
        // Trigger gate: leave protected regions / disabled worlds alone.
        if (!guard.physicsAllowed(hitBlock.getLocation())) {
            return;
        }

        Vector velocity = projectile.getVelocity();
        double speed = velocity.length(); // blocks/tick
        double energy = 0.5 * massFor(projectile) * speed * speed * energyScale;
        if (energy <= 0.0) {
            return;
        }

        // One projectile deals a block impact at most once. The first hit that gets
        // this far claims the projectile's UUID; any later re-fire of the same
        // projectile finds it already claimed and bails. Done last so a hit dropped
        // earlier (untracked block, protected region) doesn't burn the one impact.
        if (!claimImpact(projectile.getUniqueId())) {
            return;
        }

        // Capture everything the settle needs and defer it — the heavy work runs
        // on the processor's budgeted tick, not on this event thread. The shooter
        // is read here (not at settle) because the projectile may despawn first.
        processor.enqueue(new QueuedImpact(
                world,
                origin,
                hitBlock.getLocation(),
                velocity.getX(),
                velocity.getY(),
                velocity.getZ(),
                energy,
                projectile.getType().name(),
                shooterId(projectile)));
    }

    /**
     * The UUID of whoever fired this projectile, as a string, or {@code null} when the shooter
     * is unknown or not an entity (a dispenser-fired arrow has a {@code BlockProjectileSource},
     * which has no UUID to attribute).
     */
    private String shooterId(Projectile projectile) {
        ProjectileSource shooter = projectile.getShooter();
        return shooter instanceof Entity entity ? entity.getUniqueId().toString() : null;
    }

    /**
     * Claim a projectile's one block impact. Returns {@code true} the first time a
     * given UUID is seen (the caller may proceed) and {@code false} on every later
     * call for the same UUID (a duplicate re-fire — drop it). The remembered set is
     * bounded by {@link #MAX_REMEMBERED_PROJECTILES}, evicting the oldest entry when
     * full so it never leaks on a long-running server.
     */
    private boolean claimImpact(UUID projectileId) {
        if (!impactedProjectiles.add(projectileId)) {
            return false; // already dealt its impact — this is a duplicate hit
        }
        if (impactedProjectiles.size() > maxRememberedProjectiles) {
            var oldest = impactedProjectiles.iterator();
            oldest.next();
            oldest.remove();
        }
        return true;
    }

    /**
     * Shrink the remembered-projectile cap. Visible for testing the eviction path
     * without firing {@link #MAX_REMEMBERED_PROJECTILES}+1 arrows; production never
     * calls this and keeps the full cap.
     */
    public void setMaxRememberedProjectiles(int cap) {
        this.maxRememberedProjectiles = cap;
    }

    /**
     * Physical mass of a projectile, in the same units the material registry
     * uses. This IS a hard-coded property of the ammunition (an iron-tipped
     * arrow really is heavier than a snowball) — but the <em>damage</em> is not:
     * it emerges from {@code ½·m·v²} and the target material's toughness.
     */
    private double massFor(Entity entity) {
        String type = entity.getType().name();
        return switch (type) {
            case "TRIDENT" -> 4.0; // heavy iron — the dedicated wall-punching bolt
                // A loosed arrow is anti-personnel: light enough that it pocks timber
                // and chips weak blocks but can't bore through a stone wall. Heavy
                // structural damage is the trident/ballista's job, not the longbow's.
            case "ARROW", "SPECTRAL_ARROW" -> 0.15;
            case "FIREBALL", "SMALL_FIREBALL", "DRAGON_FIREBALL", "WIND_CHARGE" -> 2.0;
            case "SNOWBALL", "EGG", "ENDER_PEARL" -> 0.4;
            default -> entity instanceof AbstractArrow ? 0.15 : 1.0;
        };
    }
}
