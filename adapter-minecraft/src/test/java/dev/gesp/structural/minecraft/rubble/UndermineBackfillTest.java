package dev.gesp.structural.minecraft.rubble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.minecraft.StructuralIntegrityPlugin;
import dev.gesp.structural.minecraft.config.EffectsConfig;
import dev.gesp.structural.minecraft.material.MaterialRegistry;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.rubble.RubbleCalculator.RubbleCandidate;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.FallingBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Verifies the undermining presentation: when a dug-under-a-wall collapse spawns
 * rubble, the falling debris is biased sideways toward the dug-out void column so
 * the tunnel partially backfills, and the spawn count is capped.
 *
 * <p>This drives {@link RubbleHandler#processCollapse} directly with collapsed
 * nodes stacked off to one side of a void column — the geometry a wall coming down
 * over a freshly-dug tunnel produces. Survival is made deterministic (fall-damage
 * factor 0, base chance 1.0) so every collapsed node becomes rubble regardless of
 * the cosmetic scatter, leaving the backfill drift the only thing under test.
 */
@DisplayName("Undermine backfill: rubble drifts toward the dug void")
class UndermineBackfillTest {

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private WorldMock world;
    private PhysicsConfig physics;
    private EffectsConfig effects;
    private RubbleHandler handler;

    /** The dug-out column the player tunnelled into (at x=0). */
    private static final NodePos VOID_COLUMN = new NodePos(0, 65, 0);

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        world = server.addSimpleWorld("undermine_world");

        // Deterministic survival: factor 0 → survivalChance = blastRes/blastRes = 1.0,
        // so every collapsed node becomes rubble on every run (no flaky RNG gate).
        physics = new PhysicsConfig();
        physics.setRubbleEnabled(true);
        physics.setRubbleFallDamageFactor(0.0);
        physics.setRubbleBaseChance(1.0);
        physics.setRubbleMinChance(1.0);

        effects = new EffectsConfig();
        effects.setRubbleEnabled(true);
        effects.setUndermineBackfillRubble(true);
        effects.setMaxRubblePerCollapse(200);

        handler = new RubbleHandler(plugin, physics, effects, new MaterialRegistry());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** A wall standing to the +X side of the void column, collapsing onto it. */
    private List<CollapsedNode> wallEastOfVoid(int count) {
        MaterialSpec spec = new MaterialSpec(2.0, 30.0, 1.5, 1.0);
        List<CollapsedNode> nodes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // x = 3..3+count: clearly east of the void at x=0, so backfill must push -X.
            nodes.add(new CollapsedNode(new NodePos(3 + i, 70, 0), spec));
        }
        return nodes;
    }

    private List<FallingBlock> spawnedRubble() {
        return new ArrayList<>(world.getEntitiesByClass(FallingBlock.class));
    }

    @Test
    @DisplayName("Backfill on: every rubble entity drifts toward the void (negative X here)")
    void backfillBiasesTowardVoid() {
        List<CollapsedNode> wall = wallEastOfVoid(5);

        List<CollapsedNode> spawned = handler.processCollapse(
                world, wall, /* groundLevel */ 65, /* player */ null, (x, y, z) -> Material.STONE, VOID_COLUMN);

        assertEquals(5, spawned.size(), "every deterministic-survival node becomes rubble");
        List<FallingBlock> rubble = spawnedRubble();
        assertEquals(5, rubble.size(), "one FallingBlock per surviving node");

        // The wall is east of the void, so the backfill nudge (0.15) points -X. The
        // cosmetic scatter is at most +0.15, so the biased X-velocity can never be
        // positive: every piece drifts back toward the tunnel.
        for (FallingBlock fb : rubble) {
            assertTrue(
                    fb.getVelocity().getX() <= 1.0e-9,
                    "biased rubble must not drift away from the void (vx="
                            + fb.getVelocity().getX() + ")");
        }
    }

    @Test
    @DisplayName("Backfill off: rubble still spawns but is NOT pulled toward the void")
    void toggleOffSkipsBias() {
        effects.setUndermineBackfillRubble(false);
        List<CollapsedNode> wall = wallEastOfVoid(40);

        handler.processCollapse(world, wall, 65, null, (x, y, z) -> Material.STONE, VOID_COLUMN);

        List<FallingBlock> rubble = spawnedRubble();
        assertEquals(40, rubble.size(), "rubble still spawns with backfill off");

        // With backfill off the only horizontal motion is the cosmetic scatter, bounded
        // to ±0.15. The undermine nudge (0.15 toward -X) would push the biased path
        // below -0.15, so "every vx ≥ -0.15" deterministically proves no bias was
        // applied — no dependence on the random sign of any single draw.
        for (FallingBlock fb : rubble) {
            assertTrue(
                    fb.getVelocity().getX() >= -0.15 - 1.0e-9,
                    "backfill off → only scatter, no -X nudge (vx="
                            + fb.getVelocity().getX() + ")");
        }
    }

    @Test
    @DisplayName("A null void column (non-undermine collapse) applies no bias")
    void nullColumnAppliesNoBias() {
        List<CollapsedNode> wall = wallEastOfVoid(40);

        // The convenience overload passes a null column → plain scatter, even with the
        // toggle on. Same deterministic bound: no piece is pushed past the ±0.15 scatter.
        handler.processCollapse(world, wall, 65, null, (x, y, z) -> Material.STONE);

        for (FallingBlock fb : spawnedRubble()) {
            assertTrue(
                    fb.getVelocity().getX() >= -0.15 - 1.0e-9,
                    "no column → no undermine bias (vx=" + fb.getVelocity().getX() + ")");
        }
    }

    @Test
    @DisplayName("The rubble spawn count is capped so a big undermine can't litter entities")
    void rubbleCountIsCapped() {
        effects.setMaxRubblePerCollapse(3);
        List<CollapsedNode> wall = wallEastOfVoid(50);

        List<CollapsedNode> spawned =
                handler.processCollapse(world, wall, 65, null, (x, y, z) -> Material.STONE, VOID_COLUMN);

        assertEquals(3, spawned.size(), "no more than the cap many nodes are reported as rubble");
        assertEquals(3, spawnedRubble().size(), "at most cap-many FallingBlock entities are spawned");
    }

    @Test
    @DisplayName("The backfill nudge points at the void with a fixed gentle magnitude")
    void backfillNudgeIsNormalisedTowardVoid() {
        MaterialSpec spec = new MaterialSpec(2.0, 30.0, 1.5, 1.0);

        // A candidate due east of the void (dx = -4, dz = 0): the nudge is purely -X at
        // the fixed magnitude, with no Z component.
        var east = new RubbleCandidate(new CollapsedNode(new NodePos(4, 70, 0), spec), 5, 1.0);
        var nEast = handler.backfillNudge(east, VOID_COLUMN);
        assertEquals(-0.15, nEast.getX(), 1.0e-9, "east of void → nudge is exactly -X·0.15");
        assertEquals(0.0, nEast.getZ(), 1.0e-9, "no Z offset → no Z nudge");

        // A candidate due south of the void (dz = -4, dx = 0): the nudge is purely -Z,
        // proving the Z axis is wired (kills a += vs -= mutation on the Z spread).
        var south = new RubbleCandidate(new CollapsedNode(new NodePos(0, 70, 4), spec), 5, 1.0);
        var nSouth = handler.backfillNudge(south, VOID_COLUMN);
        assertEquals(0.0, nSouth.getX(), 1.0e-9, "no X offset → no X nudge");
        assertEquals(-0.15, nSouth.getZ(), 1.0e-9, "south of void → nudge is exactly -Z·0.15");

        // A far diagonal candidate (dx = dz = -3): same fixed magnitude (normalised),
        // split evenly between the axes — this pins the /dist normalisation and the
        // 0.15 magnitude (kills the distance-math and boundary mutants).
        var diag = new RubbleCandidate(new CollapsedNode(new NodePos(3, 70, 3), spec), 5, 1.0);
        var nDiag = handler.backfillNudge(diag, VOID_COLUMN);
        double expectedComponent = -0.15 / Math.sqrt(2.0);
        assertEquals(expectedComponent, nDiag.getX(), 1.0e-9, "diagonal nudge splits the fixed magnitude on X");
        assertEquals(expectedComponent, nDiag.getZ(), 1.0e-9, "diagonal nudge splits the fixed magnitude on Z");
        assertEquals(
                0.15,
                Math.hypot(nDiag.getX(), nDiag.getZ()),
                1.0e-9,
                "total nudge magnitude is the fixed 0.15 regardless of distance");
    }

    @Test
    @DisplayName("Rubble exactly in the void column gets no horizontal nudge")
    void backfillNudgeZeroInVoidColumn() {
        MaterialSpec spec = new MaterialSpec(2.0, 30.0, 1.5, 1.0);
        var inColumn = new RubbleCandidate(new CollapsedNode(new NodePos(0, 80, 0), spec), 15, 1.0);

        var nudge = handler.backfillNudge(inColumn, VOID_COLUMN);

        assertEquals(0.0, nudge.getX(), 1.0e-9, "same column → no X drift");
        assertEquals(0.0, nudge.getZ(), 1.0e-9, "same column → no Z drift");
    }

    @Test
    @DisplayName("Rubble disabled: nothing spawns at all")
    void rubbleDisabledSpawnsNothing() {
        effects.setRubbleEnabled(false);
        List<CollapsedNode> wall = wallEastOfVoid(10);

        List<CollapsedNode> spawned =
                handler.processCollapse(world, wall, 65, null, (x, y, z) -> Material.STONE, VOID_COLUMN);

        assertTrue(spawned.isEmpty(), "rubble disabled → no rubble reported");
        assertFalse(spawnedRubble().size() > 0, "rubble disabled → no FallingBlock entities");
    }
}
