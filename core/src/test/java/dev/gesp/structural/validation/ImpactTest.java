package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.impact.ImpactContext;
import dev.gesp.structural.impact.ImpactEngine;
import dev.gesp.structural.impact.ImpactResult;
import dev.gesp.structural.metrics.StruxMetrics;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.StressSolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Kinetic impact: the engine spends a caller-supplied energy budget
 * ({@code ½·m·v²}) along the projectile path. The same mechanism produces
 * impact fatigue (low energy cracks, repeated hits accumulate) and penetration
 * (high energy punches through), driven only by energy vs material toughness —
 * never a per-projectile damage table.
 */
@DisplayName("Impact: energy budget cracks, accumulates, and penetrates")
class ImpactTest {

    /** A blast-resistance-1 block: absorbs penetrationCost (default 4.0) before shattering. */
    private static final MaterialSpec WALL = new MaterialSpec(2.0, 50.0);

    private static StructureGraph wallStrip(int length) {
        StructureGraph g = new StructureGraph();
        for (int x = 0; x < length; x++) {
            g.addGroundBlock(new NodePos(x, 0, 0));
            g.addBlock(new NodePos(x, 1, 0), WALL, false);
        }
        return g;
    }

    @Test
    @DisplayName("A low-energy point hit only cracks the struck block (no destruction)")
    void lowEnergyCracksSurface() {
        StructureGraph g = wallStrip(1);
        NodePos hit = new NodePos(0, 1, 0);

        // Energy 0.4 against toughness 4.0 → 10% damage, well below destruction.
        ImpactResult r = new ImpactEngine()
                .process(g, ImpactContext.builder().origin(hit).energy(0.4).build());

        assertTrue(g.hasBlock(hit), "a light hit must not destroy the block");
        assertEquals(0.1, g.getNode(hit).damage(), 1e-9, "10% of toughness delivered as damage");
        assertTrue(r.penetrated().isEmpty());
        assertTrue(r.damaged().containsKey(hit));
    }

    @Test
    @DisplayName("Impact fatigue: repeated sub-lethal hits accumulate until the block fails")
    void repeatedHitsAccumulateAndBreak() {
        StructureGraph g = wallStrip(1);
        NodePos hit = new NodePos(0, 1, 0);
        ImpactEngine engine = new ImpactEngine();

        // Energy 1.0 vs toughness 4.0 → exactly 0.25 damage per hit (binary-exact,
        // so the accumulation lands cleanly on 1.0). Three hits: cracked, standing.
        for (int i = 0; i < 3; i++) {
            engine.process(g, ImpactContext.builder().origin(hit).energy(1.0).build());
        }
        assertTrue(g.hasBlock(hit), "three 25% hits should not yet break it");
        assertEquals(0.75, g.getNode(hit).damage(), 1e-9);

        // The fourth pushes damage to exactly 1.0 → punched through on impact.
        ImpactResult r = engine.process(
                g, ImpactContext.builder().origin(hit).energy(1.0).build());
        assertFalse(g.hasBlock(hit), "the fourth hit breaks the accumulated crack");
        assertTrue(r.penetrated().contains(hit), "the killing hit is reported as penetration");
    }

    /** A free-standing vertical column of WALL blocks on ground at (0,0,0). */
    private static StructureGraph columnStrip(int height) {
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= height; y++) {
            g.addBlock(new NodePos(0, y, 0), WALL, false);
        }
        return g;
    }

    @Test
    @DisplayName("Damage-only hit on a heavily-loaded but standing block skips the whole-column settle")
    void damageOnlyHitSkipsTheSettle() {
        // A 20-tall column: the base carries the whole stack but is NOT over capacity.
        // Damaging it cannot move anything (no load/geometry/connectivity changed and it
        // doesn't cross its own failure line), so the engine must NOT re-solve the
        // 20-block region resting on it — the bug behind per-arrow server lag.
        StructureGraph g = columnStrip(20);
        NodePos base = new NodePos(0, 1, 0);
        new StressSolver().solveAll(g); // steady state: the base now holds its real cached stress
        Node baseNode = g.getNode(base);
        assertTrue(
                baseNode.stressPercent() > 0.5 && !baseNode.isOverloaded(),
                "precondition: the base is heavily loaded but still standing");

        StruxMetrics m = new StruxMetrics();
        ImpactResult r = new ImpactEngine()
                .setMetrics(m)
                .process(g, ImpactContext.builder().origin(base).energy(0.4).build());

        assertTrue(r.damaged().containsKey(base), "the light hit cracks the base");
        assertTrue(g.hasBlock(base), "but does not break it");
        assertTrue(r.collapsed().isEmpty(), "nothing collapses");
        assertEquals(0, m.solveInvocations, "damage that does not cross failure must NOT settle the dependent column");
        for (int y = 1; y <= 20; y++) {
            assertTrue(g.hasBlock(new NodePos(0, y, 0)), "the whole column is untouched");
        }
    }

    @Test
    @DisplayName("Damage-only hit on a block that IS over capacity still settles and collapses it")
    void damageOnlyHitOnFailingBlockStillSettles() {
        // A 30-tall column: the base is over its maxLoad, so once solved it reads
        // overloaded. A light hit there is still damage-only (no penetration), but the
        // guard must NOT skip the settle — the failing block has to collapse.
        StructureGraph g = columnStrip(30);
        NodePos base = new NodePos(0, 1, 0);
        new StressSolver().solveAll(g);
        assertTrue(g.getNode(base).isOverloaded(), "precondition: the base is over capacity");

        StruxMetrics m = new StruxMetrics();
        ImpactResult r = new ImpactEngine()
                .setMetrics(m)
                .process(g, ImpactContext.builder().origin(base).energy(0.4).build());

        assertTrue(m.solveInvocations > 0, "an over-capacity struck block must still trigger the settle");
        assertFalse(r.collapsed().isEmpty(), "the failing region collapses rather than being skipped");
        // (The full 30-tall column exceeds maxCascadeSteps, so the collapse truncates
        // from the top down rather than necessarily reaching the base this pass — the
        // point here is that the guard did NOT short-circuit the settle.)
    }

    @Test
    @DisplayName("Penetration: a high-energy hit punches through several blocks along its path")
    void highEnergyPenetratesAlongPath() {
        // A horizontal wall strip; fire along +X from the first block.
        StructureGraph g = wallStrip(8);
        ImpactResult r = new ImpactEngine()
                .process(
                        g,
                        ImpactContext.builder()
                                .origin(new NodePos(0, 1, 0))
                                .direction(1, 0, 0)
                                .energy(100.0) // far more than any single block's toughness
                                .build());

        // Toughness per block 4.0; 100 energy could bore ~25 deep but the cap is 6.
        assertEquals(6, r.penetrated().size(), "penetration is capped at impactMaxPenetration");
        for (int x = 0; x < 6; x++) {
            assertFalse(g.hasBlock(new NodePos(x, 1, 0)), "block " + x + " was punched through");
        }
        assertTrue(g.hasBlock(new NodePos(6, 1, 0)), "the cap stops the projectile before block 6");
    }

    @Test
    @DisplayName("Penetration depth scales with energy, not a hard-coded projectile type")
    void depthScalesWithEnergy() {
        StructureGraph g = wallStrip(8);
        // Enough to fully pay exactly two blocks (2 × 4.0) and no more.
        ImpactResult r = new ImpactEngine()
                .process(
                        g,
                        ImpactContext.builder()
                                .origin(new NodePos(0, 1, 0))
                                .direction(1, 0, 0)
                                .energy(8.0)
                                .build());

        assertEquals(2, r.penetrated().size(), "8 energy / 4 toughness = 2 blocks through");
        assertFalse(g.hasBlock(new NodePos(0, 1, 0)));
        assertFalse(g.hasBlock(new NodePos(1, 1, 0)));
        assertTrue(g.hasBlock(new NodePos(2, 1, 0)), "the third block survives");
    }

    @Test
    @DisplayName("Tougher material (higher blastResistance) resists penetration")
    void toughnessResistsPenetration() {
        StructureGraph g = new StructureGraph();
        // A bunker block: 4× the absorption budget of a plain wall.
        MaterialSpec bunker = new MaterialSpec(3.0, 100.0, 4.0);
        g.addGroundBlock(new NodePos(0, 0, 0));
        g.addBlock(new NodePos(0, 1, 0), bunker, false);

        // 8 energy fully cracks a resistance-1 block, but bunker needs 4×4=16.
        ImpactResult r = new ImpactEngine()
                .process(
                        g,
                        ImpactContext.builder()
                                .origin(new NodePos(0, 1, 0))
                                .energy(8.0)
                                .build());

        assertTrue(g.hasBlock(new NodePos(0, 1, 0)), "the bunker block shrugs off the hit");
        assertEquals(0.5, g.getNode(new NodePos(0, 1, 0)).damage(), 1e-9, "8/16 of its toughness as damage");
        assertTrue(r.penetrated().isEmpty());
    }

    @Test
    @DisplayName("Punching out a support triggers a secondary collapse")
    void penetrationUnderminesAndCollapses() {
        // A single column on the ground; punch out its base block horizontally.
        StructureGraph g = new StructureGraph();
        g.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 5; y++) {
            g.addBlock(new NodePos(0, y, 0), WALL, false);
        }

        ImpactResult r = new ImpactEngine()
                .process(
                        g,
                        ImpactContext.builder()
                                .origin(new NodePos(0, 1, 0))
                                .energy(8.0) // enough to shatter the base block
                                .build());

        assertTrue(r.penetrated().contains(new NodePos(0, 1, 0)), "base block punched out");
        assertEquals(4, r.collapsed().size(), "the four blocks above lose support and fall");
        assertTrue(g.getNode(new NodePos(0, 0, 0)) != null, "the ground holds");
    }

    @Test
    @DisplayName("impactDamageScale tunes how forgiving impacts are")
    void damageScaleIsConfigurable() {
        PhysicsConfig forgiving = new PhysicsConfig();
        forgiving.setImpactDamageScale(0.5); // half damage per hit

        StructureGraph g = wallStrip(1);
        NodePos hit = new NodePos(0, 1, 0);
        new ImpactEngine(forgiving)
                .process(g, ImpactContext.builder().origin(hit).energy(0.4).build());

        assertEquals(0.05, g.getNode(hit).damage(), 1e-9, "0.5 scale halves the 0.1 base damage");
    }
}
