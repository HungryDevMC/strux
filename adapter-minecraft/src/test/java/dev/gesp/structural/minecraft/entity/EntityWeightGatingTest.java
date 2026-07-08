package dev.gesp.structural.minecraft.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.config.EffectsConfig;
import dev.gesp.structural.minecraft.entity.EntityWeightTask.EntityWeightConfig;
import dev.gesp.structural.minecraft.hook.WarZoneService;
import dev.gesp.structural.minecraft.listener.CascadeResumeManager;
import dev.gesp.structural.minecraft.listener.DelayedCollapseManager;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.material.MaterialRegistry;
import dev.gesp.structural.minecraft.perf.TaskTimings;
import dev.gesp.structural.minecraft.protect.ChunkVerdict;
import dev.gesp.structural.minecraft.protect.CollapseGuard;
import dev.gesp.structural.minecraft.protect.NoopCollapseLogger;
import dev.gesp.structural.minecraft.protect.ProtectionService;
import dev.gesp.structural.minecraft.visual.CollapseEffects;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.ChickenMock;
import org.mockbukkit.mockbukkit.entity.IronGolemMock;
import org.mockbukkit.mockbukkit.entity.ZombieMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Gating + equivalence for the periodic standing-weight scan.
 *
 * <pre>
 *   The waste: EntityWeightTask iterated every entity in every world every 10
 *   ticks checking whether it stood on a weak block — even when the structure
 *   had NO weak blocks at all (the common case). Hundreds of mobs scanned for
 *   nothing.
 *
 *   The fix: a per-world set of weak nodes, cached against the StructureManager
 *   revision. Empty set → skip the entity scan entirely (zero entity work).
 *   Otherwise an O(1) membership test replaces the per-entity weakness re-derive.
 *   Physics — who tips what — is unchanged.
 * </pre>
 */
@DisplayName("EntityWeightTask: revision-cached weak set + empty-set fast path")
class EntityWeightGatingTest {

    private ServerMock server;
    private WorldMock world;
    private StructureManager structureManager;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("entity_weight_world");
        structureManager = new StructureManager(new MaterialRegistry(), new PhysicsConfig());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /**
     * A task whose entity source is a fixed list we control. The entity-source
     * call is counted so we can prove the scan was skipped (never consulted) on a
     * healthy world.
     */
    private final class TestTask extends EntityWeightTask {
        final AtomicInteger entitySourceCalls = new AtomicInteger();
        // Positions the standing-weight check decided to tip (the tip decision is
        // the equivalence-relevant outcome; the cascade itself is core physics and
        // tested elsewhere). Recorded instead of run so a unit test needs no scheduler.
        final List<NodePos> tipped = new ArrayList<>();
        private List<Entity> entities = new ArrayList<>();

        TestTask(EntityWeightConfig config) {
            super(
                    MockBukkit.createMockPlugin("EntityWeightTestPlugin"),
                    structureManager,
                    new EntityMassRegistry(),
                    new PhysicsConfig(),
                    delayedCollapseManager(),
                    resumeManager(delayedCollapseManager()),
                    allowAllGuard(),
                    config,
                    new TaskTimings());
        }

        void setEntities(List<Entity> e) {
            this.entities = e;
        }

        @Override
        protected Iterable<Entity> entitiesIn(World w) {
            entitySourceCalls.incrementAndGet();
            return entities;
        }

        @Override
        protected void triggerCollapse(World w, StructureGraph graph, NodePos pos) {
            tipped.add(pos);
        }
    }

    private DelayedCollapseManager delayedCollapseManager() {
        org.bukkit.plugin.Plugin plugin = MockBukkit.createMockPlugin("EntityWeightDcmPlugin");
        CollapseGuard guard = allowAllGuard();
        CollapseEffects effects = new CollapseEffects(new EffectsConfig(), plugin);
        return new DelayedCollapseManager(
                plugin, structureManager, new EffectsConfig(), effects, guard, new TaskTimings());
    }

    private CascadeResumeManager resumeManager(DelayedCollapseManager dcm) {
        return new CascadeResumeManager(
                MockBukkit.createMockPlugin("EntityWeightResumePlugin"),
                structureManager,
                dcm,
                Logger.getLogger("EntityWeightResume"),
                100,
                new TaskTimings());
    }

    private CollapseGuard allowAllGuard() {
        ProtectionService allow = new ProtectionService() {
            @Override
            public boolean physicsAllowed(Location loc) {
                return true;
            }

            @Override
            public ChunkVerdict chunkVerdict(World w, int cx, int cz) {
                return ChunkVerdict.ALL_ALLOWED;
            }

            @Override
            public String describe() {
                return "allow-all";
            }
        };
        return new CollapseGuard(allow, WarZoneService.ALLOW_ALL, NoopCollapseLogger.INSTANCE);
    }

    /** Default config: standing scan on, fall impact on, thresholds at the production defaults. */
    private EntityWeightConfig standingConfig() {
        return new EntityWeightConfig(true, 10, 0.7, 0.5, true, true, 1.0, 2.0, 15.0);
    }

    /**
     * Add a floating node at {@code pos} with the given capacity, backed by a real
     * solid block (a tracked node always has a real block, and the scan skips air
     * under an entity). Returns the node.
     */
    private Node addFloatingNode(NodePos pos, double maxLoad) {
        StructureGraph graph = structureManager.getOrCreateGraph(world);
        graph.addNode(pos, new MaterialSpec(1.0, maxLoad), false);
        world.getBlockAt(pos.x(), pos.y(), pos.z()).setType(Material.STONE);
        return graph.getNode(pos);
    }

    private void makeStressFraction(Node node, double stressFraction) {
        node.setVerticalStress(stressFraction * node.effectiveMaxLoad());
    }

    /** A zombie (mass 2.0) standing on the block at {@code stand} (its feet block). */
    private LivingEntity zombieAt(NodePos stand) {
        ZombieMock z = new ZombieMock(server, UUID.randomUUID());
        z.setLocation(new Location(world, stand.x() + 0.5, stand.y(), stand.z() + 0.5));
        z.setOnGround(true);
        return z;
    }

    /** A chicken (mass 0.3) standing on the block at {@code stand}. */
    private LivingEntity chickenAt(NodePos stand) {
        ChickenMock c = new ChickenMock(server, UUID.randomUUID());
        c.setLocation(new Location(world, stand.x() + 0.5, stand.y(), stand.z() + 0.5));
        c.setOnGround(true);
        return c;
    }

    /** An iron golem (mass 8.0) standing on the block at {@code stand}. */
    private LivingEntity ironGolemAt(NodePos stand) {
        IronGolemMock g = new IronGolemMock(server, UUID.randomUUID());
        g.setLocation(new Location(world, stand.x() + 0.5, stand.y(), stand.z() + 0.5));
        g.setOnGround(true);
        return g;
    }

    // ── Criterion 1 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("crit 1: no weak blocks → zero entity iteration even with entities present")
    void healthyWorldDoesZeroEntityWork() {
        // A sound floating node well below threshold (stress 10%, no damage).
        Node sound = addFloatingNode(new NodePos(0, 80, 0), 100.0);
        makeStressFraction(sound, 0.1);
        assertFalse(
                sound.stressPercent() >= 0.7 || sound.damage() >= 0.5,
                "precondition: the node must be sound (below both thresholds)");

        TestTask task = new TestTask(standingConfig());
        // Put a mob standing right on the (sound) block so the OLD code would scan it.
        task.setEntities(List.of(zombieAt(new NodePos(0, 81, 0))));

        task.run();

        assertEquals(0, task.lastPassEntityWork(), "no per-entity work on a world with no weak blocks");
        assertEquals(0, task.entitySourceCalls.get(), "entity source must not even be consulted when no node is weak");
        assertTrue(task.tipped.isEmpty(), "nothing tips on a healthy world");
    }

    // ── Criterion 2 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("crit 2: entity on a weak block can tip it; entity on a sound block cannot")
    void equivalenceWeakTipsSoundDoesNot() {
        // weak node: tiny capacity, damaged past threshold; a 2.0-mass zombie pushes it over.
        Node weak = addFloatingNode(new NodePos(1, 80, 1), 2.0);
        weak.addDamage(0.6); // damage 0.6 >= 0.5 threshold → weak; effectiveMaxLoad = 0.8
        // sound node: large capacity, low stress → never weak.
        Node sound = addFloatingNode(new NodePos(5, 80, 5), 100.0);
        makeStressFraction(sound, 0.1);
        structureManager.markDirty(world);

        // Standing-weight tip condition: stress + mass > effectiveMaxLoad.
        // weak: 0 + 2.0 > 0.8 → tips. sound: 10 + 2.0 = 12 > 100 → no.
        assertTrue(weak.stressValue() + 2.0 > weak.effectiveMaxLoad(), "weak node is tippable by a 2.0-mass mob");
        assertFalse(sound.stressValue() + 2.0 > sound.effectiveMaxLoad(), "sound node is not tippable");

        TestTask onWeak = new TestTask(standingConfig());
        onWeak.setEntities(List.of(zombieAt(new NodePos(1, 81, 1))));
        onWeak.run();
        assertEquals(1, onWeak.lastPassEntityWork(), "the weak set is non-empty, so the one entity is examined");
        assertEquals(
                List.of(new NodePos(1, 80, 1)),
                onWeak.tipped,
                "an entity on a weak block tips it → collapse triggered");

        TestTask onSound = new TestTask(standingConfig());
        onSound.setEntities(List.of(zombieAt(new NodePos(5, 81, 5))));
        onSound.run();
        // Standing on the sound node only — the weak node still exists, so the scan runs,
        // but the sound position is not a member, so nothing tips on its account.
        assertEquals(
                1, onSound.lastPassEntityWork(), "scan runs (weak set non-empty) but the sound block is not a member");
        assertTrue(
                onSound.tipped.isEmpty(),
                "an entity on a sound block tips nothing — identical to pre-change behaviour");
    }

    // ── Toggle: entity-weight.enabled ─────────────────────────────────────────
    // The OFF counterpart to crit 2. Every existing case runs with enabled=true;
    // this proves the master switch genuinely gates the behaviour, not just the
    // weak-set fast path. (Layer 2 of the config-matrix suite.)
    @Test
    @DisplayName("toggle: entity-weight.enabled=false → no work and no tip even under a heavy mob on a weak block")
    void disabledDoesNoWorkAndTipsNothing() {
        // Identical setup to the ON case that tips: a weak block a 2.0-mass zombie would topple.
        Node weak = addFloatingNode(new NodePos(1, 80, 1), 2.0);
        weak.addDamage(0.6); // weak: effectiveMaxLoad 0.8, a 2.0-mass mob tips it when enabled
        structureManager.markDirty(world);
        assertTrue(
                weak.stressValue() + 2.0 > weak.effectiveMaxLoad(), "precondition: this block WOULD tip when enabled");

        // Same config as standingConfig() but the master switch is off.
        EntityWeightConfig disabled = new EntityWeightConfig(false, 10, 0.7, 0.5, true, true, 1.0, 2.0, 15.0);
        TestTask task = new TestTask(disabled);
        task.setEntities(List.of(zombieAt(new NodePos(1, 81, 1))));

        task.run();

        assertEquals(0, task.lastPassEntityWork(), "disabled → zero per-entity work");
        assertEquals(0, task.entitySourceCalls.get(), "disabled → entity source never consulted");
        assertTrue(task.tipped.isEmpty(), "disabled → a mob that would tip a weak block tips nothing");
    }

    @Test
    @DisplayName("crit 2: an entity on a weak block but NOT heavy enough does not tip it")
    void equivalenceWeakButTooLight() {
        // weak (damaged) node with enough headroom that a light entity cannot tip it.
        Node weak = addFloatingNode(new NodePos(2, 80, 2), 100.0);
        weak.addDamage(0.6); // weak by damage; effectiveMaxLoad = 40
        makeStressFraction(weak, 0.0); // stress 0
        structureManager.markDirty(world);
        assertTrue(weak.damage() >= 0.5, "node is weak by damage threshold");
        assertFalse(weak.stressValue() + 0.3 > weak.effectiveMaxLoad(), "a 0.3-mass chicken cannot tip 40 capacity");

        TestTask task = new TestTask(standingConfig());
        task.setEntities(List.of(chickenAt(new NodePos(2, 81, 2))));
        task.run();

        assertEquals(1, task.lastPassEntityWork(), "weak set non-empty → the entity is examined");
        assertTrue(task.tipped.isEmpty(), "too-light entity on a weak block does not trigger collapse");
    }

    // ── Criterion 3 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("crit 3: weak set rebuilds when the revision bumps after a block crosses threshold")
    void weakSetRebuildsOnRevisionChange() {
        Node node = addFloatingNode(new NodePos(3, 80, 3), 100.0);
        makeStressFraction(node, 0.1); // sound
        structureManager.markDirty(world); // initial revision

        TestTask task = new TestTask(standingConfig());
        task.setEntities(List.of(zombieAt(new NodePos(3, 81, 3))));

        task.run();
        assertEquals(0, task.lastPassEntityWork(), "while sound, the scan is skipped");

        // A new placement/break pushes this block over the stress threshold AND bumps revision.
        makeStressFraction(node, 0.8); // now 80% >= 70% threshold
        structureManager.markDirty(world);

        task.run();
        assertEquals(1, task.lastPassEntityWork(), "after the revision bump the now-weak block is in scope");
    }

    @Test
    @DisplayName("crit 3: without a revision bump a newly-weak block is NOT re-scanned (cache frozen)")
    void cacheFrozenUntilRevisionBumps() {
        Node node = addFloatingNode(new NodePos(4, 80, 4), 100.0);
        makeStressFraction(node, 0.1);
        structureManager.markDirty(world);

        TestTask task = new TestTask(standingConfig());
        task.setEntities(List.of(zombieAt(new NodePos(4, 81, 4))));
        task.run(); // caches the (empty) weak set at this revision

        makeStressFraction(node, 0.8); // weak now, but NO markDirty → revision unchanged
        task.run();
        assertEquals(0, task.lastPassEntityWork(), "frozen cache means no rescan until the revision changes");
    }

    // ── Criterion 4 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("crit 4: the weak set is bounded by weak nodes and removed nodes drop out")
    void weakSetBoundedAndRemovedNodesDropOut() {
        // Two weak, one sound.
        Node weakA = addFloatingNode(new NodePos(10, 80, 10), 2.0);
        weakA.addDamage(0.6);
        Node weakB = addFloatingNode(new NodePos(11, 80, 11), 2.0);
        weakB.addDamage(0.6);
        Node sound = addFloatingNode(new NodePos(12, 80, 12), 100.0);
        makeStressFraction(sound, 0.1);
        structureManager.markDirty(world);

        TestTask task = new TestTask(standingConfig());
        // One entity per node + an extra entity over nothing.
        task.setEntities(List.of(
                zombieAt(new NodePos(10, 81, 10)),
                zombieAt(new NodePos(11, 81, 11)),
                zombieAt(new NodePos(12, 81, 12)),
                zombieAt(new NodePos(99, 81, 99))));
        task.run();
        // All four entities are examined (the set is non-empty), but only the two weak
        // positions are members — bounded by the weak nodes, not by total nodes.
        assertEquals(4, task.lastPassEntityWork(), "every entity examined once when the weak set is non-empty");

        // Remove both weak nodes and bump revision → the set must empty out entirely.
        structureManager.removeBlockDirect(world, new NodePos(10, 80, 10));
        structureManager.removeBlockDirect(world, new NodePos(11, 80, 11));
        structureManager.markDirty(world);

        task.run();
        assertEquals(
                0,
                task.lastPassEntityWork(),
                "with both weak nodes removed the set empties and the scan is skipped again");
    }

    // ── E2E ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("e2e: healthy world is free, then a weak block under a mob brings it into scope and collapses")
    void e2eHealthyThenWeakUnderMob() {
        // Sound world with a mob standing on it.
        Node node = addFloatingNode(new NodePos(0, 64, 0), 2.0);
        makeStressFraction(node, 0.1);
        structureManager.markDirty(world);

        TestTask task = new TestTask(standingConfig());
        LivingEntity mob = ironGolemAt(new NodePos(0, 65, 0)); // mass 8.0, feet on y=65, block below y=64
        task.setEntities(List.of(mob));

        task.run();
        assertEquals(0, task.lastPassEntityWork(), "healthy world: entity scan skipped");
        assertTrue(task.tipped.isEmpty(), "healthy world: nothing tips");

        // Damage the block past the threshold (e.g. a blast) → it becomes weak, revision bumps.
        node.addDamage(0.7); // damage 0.7 >= 0.5 → weak; effectiveMaxLoad = 2.0 * 0.3 = 0.6
        structureManager.markDirty(world);
        assertTrue(node.stressValue() + 8.0 > node.effectiveMaxLoad(), "iron golem tips the now-weak block");

        task.run();
        assertEquals(1, task.lastPassEntityWork(), "weak block now in scope, the mob is examined");
        assertEquals(
                List.of(new NodePos(0, 64, 0)),
                task.tipped,
                "the standing weight tipped the now-weak block under the mob");
    }
}
