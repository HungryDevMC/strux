package dev.gesp.structural.validation;

import static org.junit.jupiter.api.Assertions.*;

import dev.gesp.structural.api.CollapseReason;
import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.assess.CollapseAtlas;
import dev.gesp.structural.assess.StructureGrade;
import dev.gesp.structural.assess.StructureReport;
import dev.gesp.structural.blast.BlastCallback;
import dev.gesp.structural.blast.BlastContext;
import dev.gesp.structural.blast.BlastOcclusion;
import dev.gesp.structural.blast.BlastResult;
import dev.gesp.structural.engine.StruxEngine;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@link StruxEngine} facade: a host should be able to build, simulate and
 * grade a structure through the one object without touching the internals.
 */
@DisplayName("StruxEngine: the embedding facade")
class StruxEngineTest {

    private static final MaterialSpec STONE = new MaterialSpec(3.0, 100.0);

    @Test
    @DisplayName("Build → solve → grade reads back through the facade")
    void buildSolveGrade() {
        StruxEngine engine = new StruxEngine();
        engine.addGround(0, 0, 0).addBlock(0, 1, 0, STONE).addBlock(0, 2, 0, STONE);

        StructureReport report = engine.assess();
        assertEquals(2, report.assessedNodes());
        assertEquals(StructureGrade.S, report.grade(), "two stone blocks barely stress each other");
        assertTrue(engine.stressAt(new NodePos(0, 1, 0)) >= 0, "tracked block reports stress");
    }

    @Test
    @DisplayName("Breaking a support cascades the blocks above")
    void breakCascades() {
        StruxEngine engine = new StruxEngine();
        engine.addGround(0, 0, 0);
        for (int y = 1; y <= 5; y++) {
            engine.addBlock(0, y, 0, STONE);
        }
        engine.solve();

        CascadeResult result = engine.breakBlock(0, 1, 0);
        assertEquals(4, result.collapsedNodes().size(), "the 4 blocks above the broken support fall");
        assertEquals(1, engine.size(), "only the ground node remains");
    }

    @Test
    @DisplayName("Reinforcement raises capacity through the facade")
    void reinforceThroughFacade() {
        StruxEngine engine = new StruxEngine();
        engine.addGround(0, 0, 0).addBlock(0, 1, 0, new MaterialSpec(2.0, 10.0));
        engine.reinforce(new NodePos(0, 1, 0), 3.0);
        engine.solve();
        // capacity tripled, so a light block is nowhere near its limit
        assertTrue(engine.stressAt(new NodePos(0, 1, 0)) < 0.5);
    }

    @Test
    @DisplayName("removeBlock edits the graph without running physics")
    void removeBlockIsAHostEdit() {
        StruxEngine engine = new StruxEngine();
        engine.addGround(0, 0, 0).addBlock(0, 1, 0, STONE).addBlock(0, 2, 0, STONE);

        engine.removeBlock(new NodePos(0, 2, 0));

        assertEquals(2, engine.size(), "the removed block is gone; nothing else cascades");
        assertTrue(engine.stressAt(new NodePos(0, 2, 0)) < 0, "absent block reports -1");
    }

    @Test
    @DisplayName("detonate craters the structure through the facade")
    void detonateThroughFacade() {
        StruxEngine engine = new StruxEngine();
        engine.addGround(0, 0, 0);
        engine.addBlock(0, 1, 0, STONE);
        engine.solve();

        BlastContext ctx = BlastContext.builder()
                .center(new NodePos(0, 1, 0))
                .power(10.0)
                .occlusion(BlastOcclusion.NONE)
                .build();
        BlastResult result = engine.detonate(ctx);

        assertTrue(result.destroyed().contains(new NodePos(0, 1, 0)), "the epicenter block is craters");
        assertEquals(1, engine.size(), "only the ground node survives the blast");
    }

    @Test
    @DisplayName("detonate fires per-block callbacks")
    void detonateWithCallback() {
        StruxEngine engine = new StruxEngine();
        engine.addGround(0, 0, 0);
        engine.addBlock(0, 1, 0, STONE);
        engine.solve();

        boolean[] sawDestroy = {false};
        var cb = new BlastCallback() {
            @Override
            public void onDirectDestroy(NodePos pos) {
                sawDestroy[0] = true;
            }
        };
        BlastContext ctx = BlastContext.builder()
                .center(new NodePos(0, 1, 0))
                .power(10.0)
                .occlusion(BlastOcclusion.NONE)
                .build();
        engine.detonate(ctx, cb);

        assertTrue(sawDestroy[0], "the blast callback should receive the direct destroy");
    }

    @Test
    @DisplayName("breakBlock with a callback reports the same collapse as without")
    void breakBlockWithCallback() {
        StruxEngine engine = new StruxEngine();
        engine.addGround(0, 0, 0);
        for (int y = 1; y <= 3; y++) {
            engine.addBlock(0, y, 0, STONE);
        }
        engine.solve();

        int[] steps = {0};
        var cb = new SolverCallback() {
            @Override
            public void onStressUpdated(Map<NodePos, Double> stressMap) {}

            @Override
            public void onCascadeStep(CollapsedNode node, int stepNumber, CollapseReason reason) {
                // Skip the trigger callback - only count actual collapsed blocks
                if (reason != CollapseReason.TRIGGER) {
                    steps[0]++;
                }
            }

            @Override
            public void onCascadeComplete(List<CollapsedNode> allCollapsed) {}
        };
        CascadeResult result = engine.breakBlock(new NodePos(0, 1, 0), cb);

        assertEquals(2, result.collapsedNodes().size(), "the 2 blocks above fall");
        assertEquals(2, steps[0], "the callback saw one step per collapsed block (excluding trigger)");
    }

    @Test
    @DisplayName("predictCollapse counts the connectivity loss without simulating")
    void predictCollapseDoesNotMutate() {
        StruxEngine engine = new StruxEngine();
        engine.addGround(0, 0, 0);
        for (int y = 1; y <= 4; y++) {
            engine.addBlock(0, y, 0, STONE);
        }
        engine.solve();

        int predicted = engine.predictCollapse(new NodePos(0, 1, 0));

        assertEquals(3, predicted, "removing the lowest block orphans the 3 above it");
        assertEquals(5, engine.size(), "prediction is non-destructive — nothing was removed");
    }

    @Test
    @DisplayName("Internal accessors expose the live graph, atlas, metrics and config")
    void internalAccessors() {
        StruxEngine engine = new StruxEngine();
        engine.addGround(0, 0, 0).addBlock(0, 1, 0, STONE);
        engine.solve();

        assertNotNull(engine.config(), "config is exposed");
        assertNotNull(engine.metrics(), "metrics are exposed");
        assertEquals(2, engine.graph().size(), "graph accessor returns the live graph");

        CollapseAtlas atlas = engine.collapseAtlas();
        assertSame(engine.graph(), atlas.graph(), "the atlas wraps the engine's own graph");
    }
}
