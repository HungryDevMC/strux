package dev.gesp.structural.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.gesp.structural.api.SolverCallback;
import dev.gesp.structural.blast.BlastContext;
import dev.gesp.structural.blast.BlastResult;
import dev.gesp.structural.blast.StruxExplosionEngine;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.impact.ImpactContext;
import dev.gesp.structural.impact.ImpactEngine;
import dev.gesp.structural.impact.ImpactResult;
import dev.gesp.structural.model.CollapsedNode;
import dev.gesp.structural.model.MaterialSpec;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.model.ThermalClass;
import dev.gesp.structural.persistence.StructureConverter;
import dev.gesp.structural.persistence.StructureData;
import dev.gesp.structural.recording.ReplayEngine.Divergence;
import dev.gesp.structural.recording.ReplayEngine.ReplayResult;
import dev.gesp.structural.solver.CascadeEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure-core tests for {@link ReplayEngine}, pinning the three verification
 * blind spots the adapter recordings exposed:
 *
 * <ol>
 *   <li>a blast that diverges in BOTH its destroyed and collapsed sets must
 *       report BOTH divergences, not short-circuit on the first;</li>
 *   <li>a recorded {@code collapsed} set is adapter-augmented with a whole-world
 *       floating sweep — replay must run the same sweep so a deterministic blast
 *       does not flag divergent;</li>
 *   <li>the recorded {@code damaged} map is compared (with the legacy
 *       collapsed∩damaged overlap subtracted first).</li>
 * </ol>
 *
 * <p>Plus impact-event fidelity: a penetrating impact removes more than the
 * origin block, so the recording carries {@code penetrated[]} and per-block
 * {@code pathDamage}; replay must reproduce those graph effects or the
 * pre-state drifts and every later event mis-compares.
 *
 * <p>These build the recording from REAL engine output so the only thing under
 * test is whether replay reproduces it.
 */
@DisplayName("ReplayEngine: verification blind spots")
class ReplayEngineTest {

    private static final MaterialSpec STONE = new MaterialSpec(1.0, 6.0);

    /**
     * Same blastResistance (1.0) as STONE — so penetration physics is identical —
     * but a high maxLoad so a tall column carries no ambient stress overload. That
     * keeps the penetration test's collapses purely penetration/floating driven,
     * isolating the path-damage round-trip from unrelated stress collapses.
     */
    private static final MaterialSpec TOUGH = new MaterialSpec(1.0, 1000.0);

    private final PhysicsConfig config = new PhysicsConfig();

    // ─────────────────────────────────────────────────────────────────────
    //  #1 — a blast diverging in both sets reports BOTH divergences
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("#1 a blast diverging in destroyed AND collapsed reports both, not just the first")
    void blastReportsAllDivergencesForOneEvent() {
        // A simple grounded wall. We will hand replay a recording whose destroyed
        // and collapsed sets are BOTH wrong, and assert it reports two divergences
        // for the one event (today it early-returns after destroyed).
        StructureGraph graph = wall();
        StructureData initial = StructureConverter.toData(graph, "w");

        // Deliberately bogus sets: a destroyed position that the blast will not
        // destroy, and a collapsed position that will not collapse.
        BlastEvent bogus = new BlastEvent(
                0L,
                1L,
                new NodePos(1, 1, 0),
                4.0,
                "SPHERE",
                List.of(new NodePos(99, 99, 99)), // never destroyed
                List.of(new NodePos(88, 88, 88)), // never collapses
                Map.of());

        RecordingSession session = sessionOf(initial, bogus);
        ReplayResult result = new ReplayEngine(config).replay(session, ReplayEngine.ReplayListener.NONE);

        List<Divergence> seq1 = divergencesFor(result, 1L);
        Set<String> types = new HashSet<>();
        for (Divergence d : seq1) {
            types.add(d.eventType());
        }
        assertTrue(types.contains("BLAST_DESTROYED"), "destroyed mismatch must be reported; got " + types);
        assertTrue(
                types.contains("BLAST_COLLAPSED"),
                "collapsed mismatch must ALSO be reported for the same event; got " + types);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  #2 — replay's blast sweep is SCOPED, exactly like the live BlastProcessor
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("#2 a blast's scoped sweep leaves a far out-of-scope floater alone (no phantom collapse)")
    void blastScopedSweepDoesNotClaimFarFloater() {
        // The live BlastProcessor follows the engine with a SCOPED ground-refresh over
        // the blast's affected region only — NOT a whole-world sweep. A pre-existing
        // floater far from the blast is outside that scope, so the live path never
        // collapses it and the recorded collapsed excludes it. Replay must mirror the
        // scoping: a whole-world sweep here would force-drop the far island, inventing
        // a collapse the recording never had and drifting the graph.
        StructureGraph graph = groundedStubPlusFarFloatingIsland();
        StructureData initial = StructureConverter.toData(graph, "w");

        BlastEvent event = recordBlast(graph, new NodePos(0, 1, 0), 4.0);
        // The far island is OUT of the blast's scope, so the live scoped refresh never
        // touches it — it must NOT be in the recorded collapsed set.
        assertFalse(
                event.collapsed().contains(new NodePos(50, 50, 50)),
                "the scoped blast sweep must not reach the far island");

        RecordingSession session = sessionOf(initial, event);
        ReplayResult result = new ReplayEngine(config).replay(session, ReplayEngine.ReplayListener.NONE);

        assertTrue(
                divergencesFor(result, 1L).isEmpty(),
                "the scoped-sweep blast must verify clean, got: " + divergencesFor(result, 1L));
        // The far island was floating before the blast and is still floating after —
        // replay must leave it standing (as the live path does), reported as an
        // invariant violation, not silently swept into a phantom collapse.
        assertTrue(
                result.finalGraph().hasBlock(new NodePos(50, 50, 50)),
                "the out-of-scope floater is left standing, not swept");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  #3 — the damaged map is compared (with legacy overlap subtracted)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("#3 a wrong recorded damaged map is reported as a divergence")
    void wrongDamagedMapIsReported() {
        StructureGraph graph = wall();
        StructureData initial = StructureConverter.toData(graph, "w");

        BlastEvent real = recordBlast(graph, new NodePos(1, 1, 0), 4.0);
        // Corrupt one damage value so the recorded map no longer matches replay.
        Map<NodePos, Double> tampered = new HashMap<>(real.damaged());
        if (tampered.isEmpty()) {
            // Force a damaged entry if the blast happened to crack nothing.
            tampered.put(new NodePos(5, 1, 0), 0.5);
        } else {
            NodePos first = tampered.keySet().iterator().next();
            tampered.put(first, Math.min(1.0, tampered.get(first) + 0.25));
        }
        BlastEvent tamperedEvent = new BlastEvent(
                real.timestampMs(),
                real.sequenceId(),
                real.center(),
                real.power(),
                real.shape(),
                real.destroyed(),
                real.collapsed(),
                tampered);

        RecordingSession session = sessionOf(initial, tamperedEvent);
        ReplayResult result = new ReplayEngine(config).replay(session, ReplayEngine.ReplayListener.NONE);

        Divergence damaged = divergencesFor(result, real.sequenceId()).stream()
                .filter(d -> d.eventType().equals("BLAST_DAMAGED"))
                .findFirst()
                .orElseThrow(
                        () -> new AssertionError("a wrong damaged value must surface as a BLAST_DAMAGED divergence"));
        // The only difference is a changed VALUE on a position present in both maps,
        // so the message must name a level mismatch — not a missing/extra position.
        assertTrue(
                damaged.message().contains("1 block(s) cracked to a different level"),
                "a changed crack LEVEL must be reported as a level mismatch: " + damaged.message());
        assertFalse(damaged.message().contains("more cracked"), "no position is missing: " + damaged.message());
        assertFalse(damaged.message().contains("unexpected cracked"), "no position is extra: " + damaged.message());
    }

    @Test
    @DisplayName("#3 a replayed crack at a position the recording omits is reported as extra")
    void damagedExtraReported() {
        StructureGraph graph = wall();
        StructureData initial = StructureConverter.toData(graph, "w");

        BlastEvent real = recordBlast(graph, new NodePos(1, 1, 0), 4.0);
        // Drop one real survivor from the recorded map → replay produces it = "extra".
        Map<NodePos, Double> thinned = new HashMap<>(real.damaged());
        NodePos dropped = thinned.keySet().iterator().next();
        thinned.remove(dropped);
        BlastEvent thinnedEvent = new BlastEvent(
                real.timestampMs(),
                real.sequenceId(),
                real.center(),
                real.power(),
                real.shape(),
                real.destroyed(),
                real.collapsed(),
                thinned);

        ReplayResult result =
                new ReplayEngine(config).replay(sessionOf(initial, thinnedEvent), ReplayEngine.ReplayListener.NONE);
        Divergence damaged = divergencesFor(result, real.sequenceId()).stream()
                .filter(d -> d.eventType().equals("BLAST_DAMAGED"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("a dropped survivor must surface as an extra crack"));
        assertTrue(
                damaged.message().contains("1 unexpected cracked block(s)"),
                "a survivor replay produces but the file omits is reported as extra: " + damaged.message());
    }

    @Test
    @DisplayName("#3 legacy collapsed∩damaged overlap does NOT cause a false damaged divergence")
    void legacyDamagedOverlapDoesNotFalseFlag() {
        StructureGraph graph = wall();
        StructureData initial = StructureConverter.toData(graph, "w");

        BlastEvent real = recordBlast(graph, new NodePos(1, 1, 0), 4.0);
        // Simulate a PRE-fix (legacy) recording: every collapsed block ALSO appears
        // in damaged with some stale crack value (the bug fixed this morning).
        Map<NodePos, Double> legacyDamaged = new HashMap<>(real.damaged());
        for (NodePos c : real.collapsed()) {
            legacyDamaged.put(c, 0.42); // stale value that no longer corresponds to a survivor
        }
        BlastEvent legacy = new BlastEvent(
                real.timestampMs(),
                real.sequenceId(),
                real.center(),
                real.power(),
                real.shape(),
                real.destroyed(),
                real.collapsed(),
                legacyDamaged);

        RecordingSession session = sessionOf(initial, legacy);
        ReplayResult result = new ReplayEngine(config).replay(session, ReplayEngine.ReplayListener.NONE);

        boolean damagedFalseFlag = divergencesFor(result, real.sequenceId()).stream()
                .anyMatch(d -> d.eventType().equals("BLAST_DAMAGED"));
        assertFalse(
                damagedFalseFlag,
                "the collapsed∩damaged overlap alone must not trip a damaged divergence (legacy accommodation)");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Impact fidelity — a penetrating impact round-trips penetrated + pathDamage
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a penetrating impact reproduces penetrated[] + pathDamage so the pre-state never drifts")
    void penetratingImpactKeepsPreStateInStep() {
        // A penetrating impact cracks NON-origin blocks along its path. Origin-only
        // replay never touches them, so their accumulated cracks vanish and a later
        // hit on one of them diverges. We chain two hits to prove the path damage
        // round-trips:
        //
        //   HIT1: horizontal shot at (0,5,0). Punches through the origin and cracks
        //         the next block (1,5,0) to 0.25 (a non-origin survivor).
        //   HIT2: a point impact on (1,5,0). With the 0.25 pre-crack it just tips
        //         over into penetration; from a pristine 0.0 it would only reach
        //         0.85 and SURVIVE. So HIT2's recorded penetration is reproducible
        //         only if HIT1's path damage to (1,5,0) was replayed.
        StructureGraph graph = penetrableRow();
        StructureData initial = StructureConverter.toData(graph, "w");

        ImpactEvent hit1 = recordImpact(graph, new NodePos(0, 5, 0), 1, 0, 0, 5.0);
        assertTrue(hit1.penetrated().contains(new NodePos(0, 5, 0)), "HIT1 must punch through the origin");
        assertTrue(
                hit1.pathDamage().containsKey(new NodePos(1, 5, 0)),
                "HIT1 must crack the non-origin block (1,5,0) — the path-damage carrier");

        ImpactEvent hit2 = recordImpact(graph, new NodePos(1, 5, 0), 0, 0, 0, 3.4);
        assertTrue(
                hit2.penetrated().contains(new NodePos(1, 5, 0)),
                "HIT2 must finish off (1,5,0) — only possible if HIT1's crack persisted");

        RecordingSession session = sessionOf(initial, hit1, hit2);
        ReplayResult result = new ReplayEngine(config).replay(session, ReplayEngine.ReplayListener.NONE);

        assertTrue(
                result.divergences().isEmpty(),
                "two chained penetrating impacts must verify clean, got: " + result.divergences());
        assertFalse(result.hasInvariantViolations(), "no floaters should remain");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Branch coverage for the non-blast/impact event paths and helpers
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a clean break with the right collapsed list verifies with no divergence")
    void blockBreakMatchesRecordedCollapse() {
        StructureGraph graph = column(); // ground + 3 stacked
        StructureData initial = StructureConverter.toData(graph, "w");

        // Knock out the bottom support: the two above lose their path to ground.
        BlockBreakEvent breakEvent = new BlockBreakEvent(
                0L, 1L, new NodePos(0, 1, 0), "STONE", List.of(new NodePos(0, 2, 0), new NodePos(0, 3, 0)));

        ReplayResult result =
                new ReplayEngine(config).replay(sessionOf(initial, breakEvent), ReplayEngine.ReplayListener.NONE);
        assertTrue(result.divergences().isEmpty(), "a faithful break must verify clean, got: " + result.divergences());
    }

    @Test
    @DisplayName("a break whose recorded collapse is wrong is reported")
    void blockBreakWrongCollapseIsReported() {
        StructureGraph graph = column();
        StructureData initial = StructureConverter.toData(graph, "w");

        // Record NO collapse though the break actually drops two blocks.
        BlockBreakEvent breakEvent = new BlockBreakEvent(0L, 1L, new NodePos(0, 1, 0), "STONE", List.of());
        ReplayResult result =
                new ReplayEngine(config).replay(sessionOf(initial, breakEvent), ReplayEngine.ReplayListener.NONE);

        assertTrue(
                result.divergences().stream().anyMatch(d -> d.eventType().equals("BLOCK_BREAK")),
                "a wrong break collapse must be reported");
    }

    @Test
    @DisplayName("breaking a block that is already gone reports BLOCK_BREAK not-found")
    void blockBreakOnMissingBlockReported() {
        StructureGraph graph = column();
        StructureData initial = StructureConverter.toData(graph, "w");

        BlockBreakEvent breakEvent = new BlockBreakEvent(0L, 1L, new NodePos(99, 99, 99), "STONE", List.of());
        ReplayResult result =
                new ReplayEngine(config).replay(sessionOf(initial, breakEvent), ReplayEngine.ReplayListener.NONE);

        assertEquals(1, result.divergences().size());
        Divergence d = result.divergences().get(0);
        assertEquals("BLOCK_BREAK", d.eventType());
        assertTrue(d.message().contains("not found"), "message names the missing block: " + d.message());
    }

    @Test
    @DisplayName("placing a new grounded block with no collapse verifies clean")
    void blockPlaceMatchesRecordedCollapse() {
        StructureGraph graph = column();
        StructureData initial = StructureConverter.toData(graph, "w");

        BlockPlaceEvent place = new BlockPlaceEvent(0L, 1L, new NodePos(0, 4, 0), "STONE", 1.0, 6.0, List.of());
        ReplayResult result =
                new ReplayEngine(config).replay(sessionOf(initial, place), ReplayEngine.ReplayListener.NONE);
        assertTrue(result.divergences().isEmpty(), "a clean place must verify, got: " + result.divergences());
    }

    @Test
    @DisplayName("a recorded grounded anchor placed away from y=0 replays grounded (does not fall as FLOATING)")
    void blockPlaceHonorsRecordedGroundedFlag() {
        StructureGraph graph = column();
        StructureData initial = StructureConverter.toData(graph, "w");

        // An isolated anchor placed at altitude with NO neighboring support. The
        // live path grounded it (a bedrock/foundation block), so the recording
        // carries grounded=true and an empty collapsed set. Replay must add it
        // grounded; the y==0 heuristic would add it non-grounded, the settle would
        // drop it as FLOATING, and replay would falsely diverge.
        BlockPlaceEvent place = new BlockPlaceEvent(
                0L,
                1L,
                new NodePos(50, 50, 50),
                "BEDROCK",
                0.0,
                Double.MAX_VALUE,
                1.0,
                1.0,
                ThermalClass.INERT,
                true,
                List.of(),
                null);
        ReplayResult result =
                new ReplayEngine(config).replay(sessionOf(initial, place), ReplayEngine.ReplayListener.NONE);

        assertTrue(
                result.divergences().isEmpty(),
                "a recorded grounded anchor must stay put, got: " + result.divergences());
        assertTrue(result.finalGraph().getNode(new NodePos(50, 50, 50)).isGrounded(), "the placed anchor is grounded");
    }

    @Test
    @DisplayName("a placed block's full material spec (blast/fire/thermal) survives into the replay graph")
    void blockPlaceRebuildsFullMaterialSpec() {
        StructureGraph graph = column();
        StructureData initial = StructureConverter.toData(graph, "w");

        // A steel beam with non-default resistances, placed on top of the column so
        // it is supported and does not collapse. Replay must rebuild its spec from
        // the recorded fields, not default blast/fire to 1.0 and thermal to INERT.
        BlockPlaceEvent place = new BlockPlaceEvent(
                0L,
                1L,
                new NodePos(0, 4, 0),
                "STEEL_BEAM",
                1.0,
                6.0,
                4.0,
                8.0,
                ThermalClass.STEEL,
                false,
                List.of(),
                null);
        ReplayResult result =
                new ReplayEngine(config).replay(sessionOf(initial, place), ReplayEngine.ReplayListener.NONE);

        MaterialSpec spec = result.finalGraph().getNode(new NodePos(0, 4, 0)).spec();
        assertEquals(4.0, spec.blastResistance(), 1e-9, "recorded blast resistance must survive the place");
        assertEquals(8.0, spec.fireResistance(), 1e-9, "recorded fire resistance must survive the place");
        assertEquals(ThermalClass.STEEL, spec.thermalClass(), "recorded thermal class must survive the place");
    }

    @Test
    @DisplayName("replay re-simulates under the session's recorded PhysicsConfig, not the engine's own")
    void replayHonorsRecordedPhysicsConfig() {
        // A cantilever arm whose root overload depends on momentMultiplier. We extend
        // it by one block (a place event) and let the settle decide. Under a high
        // multiplier the arm overloads and sheds blocks; under the default it holds.
        // We record the place under the HIGH-moment config and replay it with a
        // DEFAULT-config engine: only by honoring the session's config does replay
        // reproduce the recorded collapse instead of falsely diverging.
        PhysicsConfig stiff = new PhysicsConfig();
        stiff.setMomentMultiplier(8.0);

        StructureGraph base = cantilever();
        StructureData initial = StructureConverter.toData(base, "w");
        NodePos tip = new NodePos(4, 3, 0);

        List<NodePos> underStiff = placeAndSettle(initial, tip, stiff);
        List<NodePos> underDefault = placeAndSettle(initial, tip, config);
        assertNotEquals(
                new HashSet<>(underDefault),
                new HashSet<>(underStiff),
                "the test is only meaningful if the two configs collapse different blocks");

        BlockPlaceEvent place = new BlockPlaceEvent(0L, 1L, tip, "STONE", 1.0, 6.0, underStiff);
        RecordingSession session = sessionOf(initial, place);
        session.setPhysicsConfig(stiff);

        ReplayResult result = new ReplayEngine(config).replay(session, ReplayEngine.ReplayListener.NONE);
        assertTrue(
                result.divergences().isEmpty(),
                "replay must re-simulate with the session's stiff config, got: " + result.divergences());
    }

    @Test
    @DisplayName("placing onto an occupied position reports a duplicate-place divergence")
    void blockPlaceOnOccupiedReported() {
        StructureGraph graph = column();
        StructureData initial = StructureConverter.toData(graph, "w");

        BlockPlaceEvent place = new BlockPlaceEvent(0L, 1L, new NodePos(0, 1, 0), "STONE", 1.0, 6.0, List.of());
        ReplayResult result =
                new ReplayEngine(config).replay(sessionOf(initial, place), ReplayEngine.ReplayListener.NONE);

        assertEquals(1, result.divergences().size());
        assertEquals("BLOCK_PLACE", result.divergences().get(0).eventType());
        assertTrue(result.divergences().get(0).message().contains("already exists"));
    }

    @Test
    @DisplayName("a legacy (origin-only) impact applies damageDealt to the origin and can destroy it")
    void legacyImpactOriginOnly() {
        StructureGraph graph = column();
        StructureData initial = StructureConverter.toData(graph, "w");

        // No path fields → legacy branch. destroyed=true removes the origin, the two
        // above lose support and fall — record that and it must verify clean.
        ImpactEvent legacy = new ImpactEvent(
                0L,
                1L,
                new NodePos(0, 1, 0),
                "ARROW",
                5.0,
                1.0,
                true,
                List.of(new NodePos(0, 2, 0), new NodePos(0, 3, 0)));
        ReplayResult result =
                new ReplayEngine(config).replay(sessionOf(initial, legacy), ReplayEngine.ReplayListener.NONE);
        assertTrue(
                result.divergences().isEmpty(), "legacy origin-only impact must verify, got: " + result.divergences());
    }

    @Test
    @DisplayName("a legacy impact that only cracks the origin (survives) verifies clean")
    void legacyImpactCracksOriginOnly() {
        StructureGraph graph = column();
        StructureData initial = StructureConverter.toData(graph, "w");

        // destroyed=false, small crack, no collapse → exercises the legacy add-damage
        // path without removal.
        ImpactEvent legacy = new ImpactEvent(0L, 1L, new NodePos(0, 2, 0), "ARROW", 1.0, 0.3, false, List.of());
        ReplayResult result =
                new ReplayEngine(config).replay(sessionOf(initial, legacy), ReplayEngine.ReplayListener.NONE);
        assertTrue(
                result.divergences().isEmpty(), "a surviving legacy crack must verify, got: " + result.divergences());
    }

    @Test
    @DisplayName("an impact on an already-gone block is silently accepted (no divergence)")
    void impactOnMissingBlockIsNotADivergence() {
        StructureGraph graph = column();
        StructureData initial = StructureConverter.toData(graph, "w");

        ImpactEvent gone = new ImpactEvent(0L, 1L, new NodePos(42, 42, 42), "ARROW", 5.0, 0.5, false, List.of());
        ReplayResult result =
                new ReplayEngine(config).replay(sessionOf(initial, gone), ReplayEngine.ReplayListener.NONE);
        assertTrue(result.divergences().isEmpty(), "a stale impact target is not a divergence");
    }

    @Test
    @DisplayName("a fire-damage event that destroys its block and cascades verifies clean")
    void fireDamageDestroysAndCascades() {
        StructureGraph graph = column();
        StructureData initial = StructureConverter.toData(graph, "w");

        // Burn through the bottom support → the two above fall.
        FireDamageEvent fire = new FireDamageEvent(
                0L,
                1L,
                new NodePos(0, 1, 0),
                "STONE",
                1.0,
                1.0,
                true,
                List.of(new NodePos(0, 2, 0), new NodePos(0, 3, 0)));
        ReplayResult result =
                new ReplayEngine(config).replay(sessionOf(initial, fire), ReplayEngine.ReplayListener.NONE);
        assertTrue(
                result.divergences().isEmpty(),
                "a faithful fire burn-through must verify, got: " + result.divergences());
    }

    @Test
    @DisplayName("a fire-damage event whose recorded collapse is wrong is reported")
    void fireDamageWrongCollapseReported() {
        StructureGraph graph = column();
        StructureData initial = StructureConverter.toData(graph, "w");

        FireDamageEvent fire = new FireDamageEvent(0L, 1L, new NodePos(0, 1, 0), "STONE", 1.0, 1.0, true, List.of());
        ReplayResult result =
                new ReplayEngine(config).replay(sessionOf(initial, fire), ReplayEngine.ReplayListener.NONE);
        assertTrue(
                result.divergences().stream().anyMatch(d -> d.eventType().equals("FIRE_DAMAGE")),
                "a wrong fire collapse must be reported");
    }

    @Test
    @DisplayName("fire damage on a block that is already gone is not a divergence")
    void fireDamageOnMissingBlock() {
        StructureGraph graph = column();
        StructureData initial = StructureConverter.toData(graph, "w");

        FireDamageEvent fire = new FireDamageEvent(0L, 1L, new NodePos(7, 7, 7), "STONE", 1.0, 1.0, false, List.of());
        ReplayResult result =
                new ReplayEngine(config).replay(sessionOf(initial, fire), ReplayEngine.ReplayListener.NONE);
        assertTrue(result.divergences().isEmpty(), "a stale fire target is not a divergence");
    }

    @Test
    @DisplayName("a CASCADE event is informational and never reports a divergence")
    void cascadeEventIsInformational() {
        StructureGraph graph = column();
        StructureData initial = StructureConverter.toData(graph, "w");

        CascadeEvent cascade = new CascadeEvent(0L, 1L, new NodePos(0, 1, 0), "OVERLOAD", List.of());
        ReplayResult result =
                new ReplayEngine(config).replay(sessionOf(initial, cascade), ReplayEngine.ReplayListener.NONE);
        assertTrue(result.divergences().isEmpty(), "a cascade event is never a divergence");
    }

    @Test
    @DisplayName("#3 a recorded crack at a position replay does not crack is reported (missing/extra)")
    void damagedMissingAndExtraReported() {
        StructureGraph graph = wall();
        StructureData initial = StructureConverter.toData(graph, "w");

        BlastEvent real = recordBlast(graph, new NodePos(1, 1, 0), 4.0);
        // Add a phantom cracked block replay will NOT produce → "expected more cracked".
        Map<NodePos, Double> tampered = new HashMap<>(real.damaged());
        tampered.put(new NodePos(500, 1, 0), 0.4);
        BlastEvent tamperedEvent = new BlastEvent(
                real.timestampMs(),
                real.sequenceId(),
                real.center(),
                real.power(),
                real.shape(),
                real.destroyed(),
                real.collapsed(),
                tampered);

        ReplayResult result =
                new ReplayEngine(config).replay(sessionOf(initial, tamperedEvent), ReplayEngine.ReplayListener.NONE);
        Divergence damaged = result.divergences().stream()
                .filter(d -> d.eventType().equals("BLAST_DAMAGED"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("a phantom cracked block must be reported"));
        assertTrue(
                damaged.message().contains("more cracked"),
                "the message names the missing crack: " + damaged.message());
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private List<Divergence> divergencesFor(ReplayResult result, long seq) {
        List<Divergence> out = new ArrayList<>();
        for (Divergence d : result.divergences()) {
            if (d.sequenceId() == seq) {
                out.add(d);
            }
        }
        return out;
    }

    private RecordingSession sessionOf(StructureData initial, StruxEvent... events) {
        RecordingSession session = new RecordingSession("test", 0L, "w", initial);
        for (StruxEvent e : events) {
            session.addEvent(e);
        }
        return session;
    }

    /**
     * Record a blast exactly as the live BlastProcessor does: run the engine and
     * record its collapse. The live path also runs a SCOPED ground-refresh over the
     * blast's affected region, but the engine's settle already drained every floater
     * there, so that refresh adds nothing — recorded {@code collapsed} equals
     * {@code result.collapsed()}. The collapsed blocks are subtracted from the damaged
     * map to keep the three sets disjoint.
     */
    private BlastEvent recordBlast(StructureGraph graph, NodePos center, double power) {
        StruxExplosionEngine engine = new StruxExplosionEngine(config);
        BlastResult result = engine.process(
                graph, BlastContext.builder().center(center).power(power).build());

        List<NodePos> allCollapsed = new ArrayList<>(result.collapsed());

        Map<NodePos, Double> damaged = new HashMap<>(result.damaged());
        for (NodePos c : allCollapsed) {
            damaged.remove(c);
        }
        return new BlastEvent(
                0L, 1L, center, power, "SPHERE", new ArrayList<>(result.destroyed()), allCollapsed, damaged);
    }

    /**
     * Record an impact exactly as the live ImpactProcessor does: run the engine and
     * record its in-scope collapse — NO follow-up floating sweep (the live impact
     * path runs none); carry penetrated + the per-block path damage so replay can
     * reproduce the graph effects.
     */
    private ImpactEvent recordImpact(
            StructureGraph graph, NodePos origin, double dx, double dy, double dz, double energy) {
        double damageBefore = graph.getNode(origin).damage();
        ImpactEngine engine = new ImpactEngine(config);
        ImpactResult result = engine.process(
                graph,
                ImpactContext.builder()
                        .origin(origin)
                        .direction(dx, dy, dz)
                        .energy(energy)
                        .build());

        List<NodePos> allCollapsed = new ArrayList<>(result.collapsed());

        double after = damageBefore;
        if (result.penetrated().contains(origin)) {
            after = 1.0;
        } else if (result.damaged().containsKey(origin)) {
            after = result.damaged().get(origin);
        }

        return new ImpactEvent(
                0L,
                1L,
                origin,
                "ARROW",
                energy,
                after - damageBefore,
                !result.penetrated().isEmpty(),
                allCollapsed,
                new ArrayList<>(result.penetrated()),
                new HashMap<>(result.damaged()));
    }

    /**
     * Add {@code tip} to a copy of {@code initial} and settle it under {@code cfg},
     * returning the collapsed positions — the live place path's outcome for that
     * config, used to build a recording and as the per-config ground truth.
     */
    private List<NodePos> placeAndSettle(StructureData initial, NodePos tip, PhysicsConfig cfg) {
        StructureGraph g = StructureConverter.toGraph(initial);
        g.addBlock(tip, STONE, false);
        return new CascadeEngine(cfg)
                .settle(g, SolverCallback.NONE).stream().map(CollapsedNode::pos).toList();
    }

    // ── graph fixtures ────────────────────────────────────────────────────

    /**
     * A grounded column (y=0 ground, y=1..3) carrying a horizontal arm at y=3 that
     * reaches out to x=3. Extending the arm overloads the root's moment only when the
     * moment multiplier is high enough — a config-sensitive cantilever.
     */
    private StructureGraph cantilever() {
        StructureGraph g = new StructureGraph();
        g.addBlock(new NodePos(0, 0, 0), STONE, true);
        for (int y = 1; y <= 3; y++) {
            g.addBlock(new NodePos(0, y, 0), STONE, false);
        }
        for (int x = 1; x <= 3; x++) {
            g.addBlock(new NodePos(x, 3, 0), STONE, false);
        }
        return g;
    }

    /** A grounded foot with three blocks stacked on it: ground(y=0) + y=1..3. */
    private StructureGraph column() {
        StructureGraph g = new StructureGraph();
        g.addBlock(new NodePos(0, 0, 0), STONE, true);
        for (int y = 1; y <= 3; y++) {
            g.addBlock(new NodePos(0, y, 0), STONE, false);
        }
        return g;
    }

    /** A short grounded wall, 6 wide, 3 tall, on a ground row. */
    private StructureGraph wall() {
        StructureGraph g = new StructureGraph();
        for (int x = 0; x < 6; x++) {
            g.addBlock(new NodePos(x, 0, 0), STONE, true); // ground row
            for (int y = 1; y < 4; y++) {
                g.addBlock(new NodePos(x, y, 0), STONE, false);
            }
        }
        return g;
    }

    /**
     * A tiny grounded stub to blast, plus a small tracked island far away with no
     * path to ground. The blast's scoped settle never reaches the island; only the
     * whole-world sweep (the adapter's refreshGroundAndCollapse) removes it. This
     * is the minimal faithful reproduction of "recorded collapsed is sweep-
     * augmented": the island stands for any region a prior event orphaned that the
     * scoped settle missed and the next sweep finally claims.
     */
    private StructureGraph groundedStubPlusFarFloatingIsland() {
        StructureGraph g = new StructureGraph();
        g.addBlock(new NodePos(0, 0, 0), STONE, true);
        g.addBlock(new NodePos(0, 1, 0), STONE, false);
        // Far, ungrounded, self-contained island — a pre-existing floater.
        g.addBlock(new NodePos(50, 50, 50), STONE, false);
        g.addBlock(new NodePos(50, 51, 50), STONE, false);
        return g;
    }

    /** Six grounded columns (x=0..5, y=0 ground, y=1..5 above) — a solid block the
     * projectile bores into horizontally. Each member keeps a path to ground down
     * its own column, so penetration holes leave a clean, non-floating post-state. */
    private StructureGraph penetrableRow() {
        StructureGraph g = new StructureGraph();
        for (int x = 0; x < 6; x++) {
            g.addBlock(new NodePos(x, 0, 0), TOUGH, true); // ground
            for (int y = 1; y <= 5; y++) {
                g.addBlock(new NodePos(x, y, 0), TOUGH, false);
            }
        }
        return g;
    }
}
