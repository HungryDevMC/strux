package dev.gesp.structural.minecraft.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.minecraft.StructuralIntegrityPlugin;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Pins that a truncated-cascade resume stays SCOPED to the disturbed region — the
 * part the outcome-only E2E tests can't see (a whole-graph resume reaches the same
 * final blocks, just by doing far more work). Two angles:
 *
 * <ul>
 *   <li>{@link CascadeResumeManager#enqueue(org.bukkit.World, Set)} stores the exact
 *       region, unions a second disturbance in, and only widens to the whole-graph
 *       fallback when a caller offers no region (empty scope). Asserted by reflecting
 *       the in-flight job's scope.
 *   <li>{@link StructureManager#resumeCascade(org.bukkit.World, Set)} settles ONLY the
 *       seeded region: a floater inside the scope collapses, an identical floater
 *       outside it is left standing — whereas the empty-scope (whole-graph) form
 *       collapses both. This is the behaviour the {@code seedScope} branch controls.
 * </ul>
 */
@DisplayName("Cascade resume is scoped to the disturbed region (adapter wiring)")
class CascadeResumeScopeTest {

    private static final MaterialSpec LIGHT = new MaterialSpec(1.0, 100.0);

    private ServerMock server;
    private StructuralIntegrityPlugin plugin;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(StructuralIntegrityPlugin.class);
        world = server.addSimpleWorld("resume_scope_world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Reflect the in-flight resume job's scope for {@code world} (null if none pending). */
    @SuppressWarnings("unchecked")
    private Set<NodePos> jobScope(CascadeResumeManager mgr) throws Exception {
        Field pending = CascadeResumeManager.class.getDeclaredField("pending");
        pending.setAccessible(true);
        Map<UUID, ?> jobs = (Map<UUID, ?>) pending.get(mgr);
        Object job = jobs.get(world.getUID());
        if (job == null) {
            return null;
        }
        Field scope = job.getClass().getDeclaredField("scope");
        scope.setAccessible(true);
        return (Set<NodePos>) scope.get(job);
    }

    @Test
    @DisplayName("enqueue stores the offered region, then unions a second disturbance in")
    void enqueueStoresAndUnionsScope() throws Exception {
        CascadeResumeManager mgr = plugin.getCascadeResumeManager();
        NodePos a = new NodePos(1, 1, 0);
        NodePos b = new NodePos(2, 1, 0);
        NodePos c = new NodePos(3, 1, 0);

        mgr.enqueue(world, Set.of(a, b));
        assertEquals(Set.of(a, b), jobScope(mgr), "the job must hold exactly the region it was given");

        mgr.enqueue(world, Set.of(c));
        assertEquals(
                Set.of(a, b, c),
                jobScope(mgr),
                "a second disturbance before the first drains must be UNIONED in, not lost or replaced");
    }

    @Test
    @DisplayName("An empty (whole-graph) enqueue widens the job and a later scoped one can't shrink it")
    void emptyScopeWidensToWholeGraphFallback() throws Exception {
        CascadeResumeManager mgr = plugin.getCascadeResumeManager();
        NodePos a = new NodePos(1, 1, 0);

        mgr.enqueue(world, Set.of(a));
        assertEquals(Set.of(a), jobScope(mgr), "starts scoped");

        // A caller with no region to offer (empty scope) means "whole graph" — the
        // conservative choice must win so nothing is missed.
        mgr.enqueue(world, Set.of());
        assertTrue(jobScope(mgr).isEmpty(), "an empty enqueue must widen the job to the whole-graph fallback");

        // ...and a later scoped enqueue cannot narrow it back (that would drop coverage).
        mgr.enqueue(world, Set.of(a));
        assertTrue(jobScope(mgr).isEmpty(), "once widened to whole-graph, a scoped enqueue can't shrink it again");
    }

    @Test
    @DisplayName("resumeCascade settles only the seeded region; the whole-graph form settles everything")
    void scopedResumeCascadeRespectsItsSeedRegion() {
        StructureManager manager = plugin.getStructureManager();
        StructureGraph graph = manager.getOrCreateGraph(world);

        // Two independent 2-tall stacks far apart. Cut BOTH bases so each has a
        // floater (the top block) that is no longer ground-connected.
        // Stack A around x=0, stack B around x=64 (different chunk).
        graph.addGroundBlock(new NodePos(0, 0, 0));
        graph.addBlock(new NodePos(0, 1, 0), LIGHT, false);
        graph.addBlock(new NodePos(0, 2, 0), LIGHT, false); // floater A once base cut
        graph.addGroundBlock(new NodePos(64, 0, 0));
        graph.addBlock(new NodePos(64, 1, 0), LIGHT, false);
        graph.addBlock(new NodePos(64, 2, 0), LIGHT, false); // floater B once base cut

        NodePos floaterA = new NodePos(0, 2, 0);
        NodePos floaterB = new NodePos(64, 2, 0);
        Set<NodePos> scopeA = graph.getDependentSubgraph(new NodePos(0, 1, 0));
        graph.removeBlock(new NodePos(0, 1, 0)); // strand floater A
        graph.removeBlock(new NodePos(64, 1, 0)); // strand floater B

        // Resume seeded with ONLY stack A's region: floater A collapses, floater B
        // (out of scope) is untouched. This is exactly what the seedScope branch does.
        CascadeEngine.SettleOutcome scoped = manager.resumeCascade(world, scopeA);
        assertTrue(scoped.collapsed().stream().anyMatch(n -> n.pos().equals(floaterA)), "in-scope floater collapses");
        assertFalse(graph.hasBlock(floaterA), "in-scope floater removed");
        assertTrue(graph.hasBlock(floaterB), "the out-of-scope floater must be LEFT STANDING by a scoped resume");

        // The empty-scope (whole-graph) form finishes the rest.
        CascadeEngine.SettleOutcome whole = manager.resumeCascade(world, Set.of());
        assertTrue(whole.collapsed().stream().anyMatch(n -> n.pos().equals(floaterB)), "whole-graph resume gets B too");
        assertFalse(graph.hasBlock(floaterB), "the whole-graph resume collapses the remaining floater");
    }

    @Test
    @DisplayName("A multi-tick resume carries the shrinking remaining region forward, never widening to whole-graph")
    void resumeCarriesRemainingScopeForwardStayingBounded() throws Exception {
        StructureManager manager = plugin.getStructureManager();
        manager.getConfig().setMaxCascadeSteps(2); // tiny cap → several capped resume passes
        StructureGraph graph = manager.getOrCreateGraph(world);

        // A tall stack whose base is cut: a long overloaded/floating frontier that
        // takes several capped passes to fully drain.
        graph.addGroundBlock(new NodePos(0, 0, 0));
        for (int y = 1; y <= 14; y++) {
            graph.addBlock(new NodePos(0, y, 0), LIGHT, false);
        }
        Set<NodePos> initial = graph.getDependentSubgraph(new NodePos(0, 1, 0));
        graph.removeBlock(new NodePos(0, 1, 0)); // strand the stack above

        CascadeResumeManager mgr = plugin.getCascadeResumeManager();
        mgr.enqueue(world, initial);

        // Drive the resume to completion. At EVERY intermediate pass the in-flight
        // scope must stay non-empty (bounded to the collapsing frontier). The
        // carry-forward bug (failing to feed remainingScope back) would let a pass
        // widen the job to the empty/whole-graph fallback — caught here.
        boolean ranAtLeastOnce = false;
        for (int i = 0; i < 100; i++) {
            Set<NodePos> scope = jobScope(mgr);
            if (scope == null) {
                break; // job retired — fully settled
            }
            assertFalse(
                    scope.isEmpty(),
                    "while a bounded collapse is still resuming, the job must never widen to the whole-graph fallback");
            mgr.run();
            ranAtLeastOnce = true;
        }
        assertTrue(ranAtLeastOnce, "the resume must have run at least one pass");
        assertTrue(graph.getFloatingBlocks().isEmpty(), "the stack fully collapsed across the resume passes");
    }
}
