package dev.gesp.structural.scenario;

import dev.gesp.structural.blast.BlastContext;
import dev.gesp.structural.blast.BlastOcclusion;
import dev.gesp.structural.blast.BlastResult;
import dev.gesp.structural.blast.StruxExplosionEngine;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.graph.StructureGraph;
import dev.gesp.structural.impact.ImpactContext;
import dev.gesp.structural.impact.ImpactEngine;
import dev.gesp.structural.impact.ImpactResult;
import dev.gesp.structural.metrics.StruxMetrics;
import dev.gesp.structural.model.Node;
import dev.gesp.structural.model.NodePos;
import dev.gesp.structural.solver.CascadeEngine;
import dev.gesp.structural.solver.CascadeResult;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs one end-to-end physics scenario: take a structure, do something to it
 * (break a block, set off a blast), and capture the {@link ScenarioOutcome}.
 *
 * <pre>
 *   ScenarioOutcome out = Scenario.on(Structures.column(8))
 *       .named("column-break-base")
 *       .breakAt(new NodePos(0, 1, 0));
 * </pre>
 *
 * <p>Every run attaches a fresh {@link StruxMetrics} so the outcome carries the
 * engine's work-counts for the performance gate. The action mutates the graph in
 * place, so the captured {@code finalGraph} is the settled structure.
 */
public final class Scenario {

    private final StructureGraph graph;
    private final PhysicsConfig config;
    private String label;

    private Scenario(StructureGraph graph, PhysicsConfig config) {
        this.graph = graph;
        this.config = config;
    }

    /** Start a scenario on {@code graph} with default physics. */
    public static Scenario on(StructureGraph graph) {
        return new Scenario(graph, new PhysicsConfig());
    }

    /** Start a scenario with a custom physics config (for tuning experiments). */
    public static Scenario on(StructureGraph graph, PhysicsConfig config) {
        return new Scenario(graph, config);
    }

    /** Label the scenario; becomes the snapshot's {@code trigger:} line. */
    public Scenario named(String label) {
        this.label = label;
        return this;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  TRIGGERS
    // ─────────────────────────────────────────────────────────────────────

    /** Break the block at {@code pos} (as a player would) and settle. */
    public ScenarioOutcome breakAt(NodePos pos) {
        int initial = graph.size();
        boolean existed = graph.hasBlock(pos);

        StruxMetrics metrics = new StruxMetrics();
        CascadeEngine engine = new CascadeEngine(config).setMetrics(metrics);
        CascadeResult result = engine.cascade(graph, pos);

        // The broken block is the "directly removed" one; the cascade is secondary.
        List<NodePos> directlyRemoved = existed ? List.of(pos) : List.of();
        return new ScenarioOutcome(
                triggerLabel("break " + fmt(pos)),
                initial,
                directlyRemoved,
                result.collapsed(),
                List.of(),
                graph,
                metrics);
    }

    /** Set off an explosion of {@code power} centred at {@code center} and settle. */
    public ScenarioOutcome blast(NodePos center, double power) {
        return blast(center, power, BlastOcclusion.NONE);
    }

    /**
     * Set off an explosion of {@code power} centred at {@code center} with the given
     * occlusion mode and settle. {@link BlastOcclusion#RAYCAST} routes each in-range
     * target through the engine's line-of-sight cover count, so this is the trigger
     * that pins the occlusion physics in a golden snapshot.
     */
    public ScenarioOutcome blast(NodePos center, double power, BlastOcclusion occlusion) {
        int initial = graph.size();

        StruxMetrics metrics = new StruxMetrics();
        StruxExplosionEngine engine = new StruxExplosionEngine(config).setMetrics(metrics);
        BlastContext ctx = BlastContext.builder()
                .center(center)
                .power(power)
                .occlusion(occlusion)
                .build();
        BlastResult result = engine.process(graph, ctx);

        // Survivors that took persistent damage but did not fall.
        List<NodePos> damagedSurvivors = new ArrayList<>();
        for (Node node : graph.getAllNodes()) {
            if (!node.isGrounded() && node.damage() > 0.0) {
                damagedSurvivors.add(node.pos());
            }
        }
        return new ScenarioOutcome(
                triggerLabel("blast power=" + trim(power) + " @ " + fmt(center)),
                initial,
                result.destroyed(),
                result.collapsed(),
                damagedSurvivors,
                graph,
                metrics);
    }

    /**
     * Fire a kinetic impact of {@code energy} into {@code origin}, travelling
     * along {@code (dirX,dirY,dirZ)}, and settle. Mirrors a projectile or ram
     * strike routed through {@link ImpactEngine}.
     */
    public ScenarioOutcome impact(NodePos origin, double dirX, double dirY, double dirZ, double energy) {
        int initial = graph.size();

        StruxMetrics metrics = new StruxMetrics();
        ImpactEngine engine = new ImpactEngine(config).setMetrics(metrics);
        ImpactContext ctx = ImpactContext.builder()
                .origin(origin)
                .direction(dirX, dirY, dirZ)
                .energy(energy)
                .build();
        ImpactResult result = engine.process(graph, ctx);

        // Survivors that took persistent crack damage but did not fall.
        List<NodePos> damagedSurvivors = new ArrayList<>();
        for (Node node : graph.getAllNodes()) {
            if (!node.isGrounded() && node.damage() > 0.0) {
                damagedSurvivors.add(node.pos());
            }
        }
        return new ScenarioOutcome(
                triggerLabel("impact energy=" + trim(energy) + " @ " + fmt(origin) + " dir(" + trim(dirX) + ","
                        + trim(dirY) + "," + trim(dirZ) + ")"),
                initial,
                result.penetrated(),
                result.collapsed(),
                damagedSurvivors,
                graph,
                metrics);
    }

    // ─────────────────────────────────────────────────────────────────────

    private String triggerLabel(String action) {
        return label != null ? label : action;
    }

    private static String fmt(NodePos p) {
        return "(" + p.x() + "," + p.y() + "," + p.z() + ")";
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }
}
