package dev.gesp.structural.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.listener.ImpactProcessor;
import dev.gesp.structural.minecraft.listener.QueuedImpact;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.model.NodePos;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Arrow;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.ArrowMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.sound.AudioExperience;
import org.mockbukkit.mockbukkit.util.SpawnedParticle;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * E2E tests for per-settled-hit impact feedback (feature {@code impact-hit-feedback}).
 *
 * <pre>
 *   When a queued projectile impact settles and actually bites the wall (it cracked
 *   the struck block or punched through ≥1 block), the player should see a small
 *   burst of that block's particles and hear a soft hit sound — once, at the hit
 *   point. Ten arrows then look like ten little bites. Physics is unchanged.
 * </pre>
 *
 * <p>We observe particles via {@link WorldMock#getSpawnedParticles()} and the sound
 * via a nearby {@link PlayerMock}'s heard sounds (MockBukkit routes
 * {@code world.playSound} to every player in the world).
 */
@DisplayName("E2E: per-settled-hit impact feedback")
class ImpactHitFeedbackE2ETest {

    /** Particle count {@link dev.gesp.structural.minecraft.visual.CollapseEffects#playImpactHit} spawns per bite. */
    private static final int IMPACT_FEEDBACK_PARTICLE_COUNT = 6;

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private WorldMock world;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        world = server.addSimpleWorld("impact_feedback_world");
        player = server.addPlayer();
        player.teleport(world.getBlockAt(0, 65, 0).getLocation());
        // Default config ships impact-feedback on; tests that need it off flip it.
        plugin.getEffectsConfig().setImpactFeedbackEnabled(true);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Criterion 1: a damaging hit emits a particle burst + a hit sound at the
    //  hit location, in the struck block's material.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC1: a sub-lethal (cracking) hit emits one block-particle burst + a hit sound at the struck block")
    void crackingHitEmitsBurstAndSound() {
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.STONE);
        Block wall = world.getBlockAt(0, 65, 0);

        world.clearSpawnedParticles();
        player.clearSounds();

        // A weak hit cracks the stone without breaking it.
        fireArrow(wall, 3.0, 0.0, 0.0);
        server.getScheduler().performTicks(3);

        // The block survived but took damage (physics still ran).
        StructureGraph graph = plugin.getStructureManager().getGraph(world);
        NodePos pos = StructureManager.toBlockPos(wall);
        assertTrue(graph.hasBlock(pos), "a cracking hit must NOT remove the block");
        assertTrue(graph.getNode(pos).damage() > 0.0, "the hit must have dealt damage to the struck block");

        List<SpawnedParticle> burst = blockBurstsAt(wall);
        assertEquals(1, burst.size(), "exactly one block-particle burst at the struck block");
        assertEquals(Material.STONE, blockMaterialOf(burst.get(0)), "the burst uses the struck block's material");

        assertTrue(heardAnySound(), "a soft hit sound plays at the struck block");
    }

    @Test
    @DisplayName("AC1: a penetrating hit emits exactly one feedback burst at the hit point (no double-play)")
    void penetratingHitEmitsExactlyOneBurstAtHitPoint() {
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.GLASS); // fragile → punched through
        Block support = world.getBlockAt(0, 65, 0);

        world.clearSpawnedParticles();

        fireArrow(support, 12.0, 0.0, 0.0);
        server.getScheduler().performTicks(5);

        assertEquals(Material.AIR, support.getType(), "the fragile support was punched through");
        // playBlockCollapse for the removed block AND playImpactHit both spawn BLOCK
        // particles; the feedback burst must add exactly ONE burst at the hit point.
        List<SpawnedParticle> feedback = blockBurstsAt(support);
        assertEquals(
                1,
                feedback.size(),
                "the impact feedback adds exactly one burst at the hit point — not a second on penetration");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Criterion 2: no feedback for stale-dropped or zero-effect impacts.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC2: a stale-dropped impact emits no feedback burst and no sound")
    void staleDroppedImpactEmitsNoFeedback() {
        place(0, 64, 0, Material.BEDROCK);
        Block target = world.getBlockAt(0, 65, 0);
        target.setType(Material.GLASS);
        plugin.getStructureManager().onBlockPlaced(target);

        ImpactProcessor processor = plugin.getImpactProcessor();
        NodePos origin = StructureManager.toBlockPos(target);
        processor.enqueue(new QueuedImpact(world, origin, target.getLocation(), 1.0, 0.0, 0.0, 50.0, "ARROW"));

        // Target vanishes before its turn → impact is dropped stale.
        plugin.getStructureManager().onBlockBroken(target);
        target.setType(Material.AIR);

        world.clearSpawnedParticles();
        player.clearSounds();

        server.getScheduler().performTicks(5);

        assertTrue(blockBurstsAt(target).isEmpty(), "a stale-dropped impact must emit no particle burst");
        assertFalse(heardAnySound(), "a stale-dropped impact must emit no hit sound");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Criterion 3: the toggle off ⇒ no feedback, but damage still applies.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC3: with impact-feedback off, the hit still damages the block but emits no burst/sound")
    void toggleOffSuppressesFeedbackButKeepsDamage() {
        plugin.getEffectsConfig().setImpactFeedbackEnabled(false);

        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.STONE);
        Block wall = world.getBlockAt(0, 65, 0);

        world.clearSpawnedParticles();
        player.clearSounds();

        fireArrow(wall, 3.0, 0.0, 0.0);
        server.getScheduler().performTicks(3);

        // Physics is unaffected: the block still took damage.
        StructureGraph graph = plugin.getStructureManager().getGraph(world);
        NodePos pos = StructureManager.toBlockPos(wall);
        assertTrue(graph.getNode(pos).damage() > 0.0, "physics is unaffected: the block still takes damage");

        // No feedback was emitted.
        assertTrue(blockBurstsAt(wall).isEmpty(), "with the toggle off no feedback particle burst plays");
        assertFalse(heardAnySound(), "with the toggle off no hit sound plays");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Criterion 4: a volley of N damaging hits produces N feedback bursts.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC4: a volley of N damaging arrows on one wall produces N feedback bursts")
    void volleyProducesOneBurstPerDamagingHit() {
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.STONE);
        Block wall = world.getBlockAt(0, 65, 0);

        world.clearSpawnedParticles();

        int n = 3;
        for (int i = 0; i < n; i++) {
            fireArrow(wall, 3.0, 0.0, 0.0); // each a sub-lethal crack on the same block
            server.getScheduler().performTicks(2);
        }

        // The block survived all three (sub-lethal), so every hit was a crack-bite.
        assertEquals(Material.STONE, wall.getType(), "the wall survived the volley (each hit sub-lethal)");
        assertEquals(n, blockBurstsAt(wall).size(), "N damaging hits produce N feedback bursts");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  E2E scenario (from the brief): one cracking hit → exactly one burst +
    //  sound and damage up; same hit with the toggle off → damage, no burst.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("E2E: a tracked stone wall cracked by one arrow shows exactly one burst; toggle off ⇒ damage only")
    void e2eOneCrackingHitOnTrackedWall() {
        place(0, 64, 0, Material.BEDROCK);
        place(0, 65, 0, Material.STONE);
        Block wall = world.getBlockAt(0, 65, 0);
        StructureGraph graph = plugin.getStructureManager().getGraph(world);
        NodePos pos = StructureManager.toBlockPos(wall);
        double damageBefore = graph.getNode(pos).damage();

        world.clearSpawnedParticles();
        player.clearSounds();

        fireArrow(wall, 3.0, 0.0, 0.0);
        server.getScheduler().performTicks(3);

        assertEquals(1, blockBurstsAt(wall).size(), "exactly one feedback burst at the struck block");
        assertTrue(heardAnySound(), "the hit sound plays");
        double damageAfter = graph.getNode(pos).damage();
        assertTrue(damageAfter > damageBefore, "the block's damage increased — physics ran");

        // Same hit, feedback off: damage still applies, no burst.
        plugin.getEffectsConfig().setImpactFeedbackEnabled(false);
        world.clearSpawnedParticles();
        double damageBeforeOff = graph.getNode(pos).damage();
        fireArrow(wall, 3.0, 0.0, 0.0);
        server.getScheduler().performTicks(3);
        assertTrue(blockBurstsAt(wall).isEmpty(), "with the toggle off the same hit produces no burst");
        assertTrue(
                graph.getNode(pos).damage() > damageBeforeOff,
                "with the toggle off the same hit still damages the block");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * The impact-FEEDBACK bursts spawned at the centre of {@code block}. The feedback
     * burst is the {@link CollapseEffects#playImpactHit} signature: a BLOCK-particle
     * cluster of exactly {@link #IMPACT_FEEDBACK_PARTICLE_COUNT} at the block centre.
     * Filtering by that count excludes the separate {@code playBlockCollapse} burst
     * (a different count) that fires when a penetrated block is removed — so this
     * isolates "did the per-hit feedback fire here, and how many times".
     */
    private List<SpawnedParticle> blockBurstsAt(Block block) {
        double cx = block.getX() + 0.5;
        double cy = block.getY() + 0.5;
        double cz = block.getZ() + 0.5;
        return world.getSpawnedParticles().stream()
                .filter(p -> p.particle() == Particle.BLOCK)
                .filter(p -> p.data() instanceof BlockData)
                .filter(p -> p.count() == IMPACT_FEEDBACK_PARTICLE_COUNT)
                .filter(p ->
                        Math.abs(p.x() - cx) < 1.0e-6 && Math.abs(p.y() - cy) < 1.0e-6 && Math.abs(p.z() - cz) < 1.0e-6)
                .toList();
    }

    private Material blockMaterialOf(SpawnedParticle particle) {
        return ((BlockData) particle.data()).getMaterial();
    }

    private boolean heardAnySound() {
        List<AudioExperience> heard = player.getHeardSounds();
        return !heard.isEmpty();
    }

    private void place(int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(material);
        plugin.getStructureManager().onBlockPlaced(block);
    }

    private void fireArrow(Block hitBlock, double vx, double vy, double vz) {
        ArrowMock arrow = new ArrowMock(server, UUID.randomUUID()) {
            @Override
            public boolean isInBlock() {
                return false;
            }
        };
        server.registerEntity(arrow);
        arrow.setVelocity(new Vector(vx, vy, vz));
        arrow.setLocation(hitBlock.getLocation());
        Arrow asArrow = arrow;
        server.getPluginManager().callEvent(new ProjectileHitEvent(asArrow, hitBlock));
    }
}
