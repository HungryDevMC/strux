package dev.gesp.structural.model;

/**
 * A single node in a structure, with its stress information.
 *
 * <p>A "node" is the generic unit the engine reasons about. The consumer
 * decides what it represents: a single block, a prefab, a truss joint, a
 * vertex — anything with a position, physical properties, and connections to
 * other nodes. The solver never assumes what a node "is".
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                          WHAT IS A NODE?                           │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Think of it as a unit that knows:                                 │
 *   │    • WHERE it is (position)                                        │
 *   │    • ITS PROPERTIES (mass and max load)                            │
 *   │    • HOW MUCH STRESS it's under (stress value)                     │
 *   │                                                                     │
 *   │                                                                     │
 *   │         [NODE]  [NODE]  [NODE]                                     │
 *   │            │        │        │                                     │
 *   │            └────────┼────────┘                                     │
 *   │                     ▼                                              │
 *   │            ┌─────────────────┐                                     │
 *   │            │   THIS NODE     │                                     │
 *   │            │   ───────────   │                                     │
 *   │            │   stress: 45%   │  ← 45% of its max capacity          │
 *   │            │   [████████░░]  │    (still safe, but working)        │
 *   │            └─────────────────┘                                     │
 *   │                     │                                              │
 *   │                     ▼                                              │
 *   │            ════════════════════                                    │
 *   │                  GROUND                                            │
 *   │                                                                     │
 *   │                                                                     │
 *   │  STRESS LEVELS:                                                    │
 *   │                                                                     │
 *   │    0-60%   [██████░░░░]  Safe - no visible damage                  │
 *   │   60-80%   [████████░░]  Warning - cracks appear                   │
 *   │   80-95%   [██████████]  Danger - node is struggling               │
 *   │   95-100%  [!BREAKING!]  Critical - about to collapse!             │
 *   │    >100%   💥 COLLAPSED  Node has failed, falls down               │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public final class Node {

    private final NodePos pos;
    private final MaterialSpec spec;
    private final boolean grounded;

    /**
     * Vertical stress: node's own weight plus weighted share of load from above.
     * This is what the existing BFS solver calculates.
     */
    private double verticalStress;

    /**
     * Moment stress: torque from horizontal arms with no pillar beneath.
     * moment = weight × distance (linear, not exponential).
     */
    private double momentStress;

    /**
     * Persistent structural damage in [0, 1]. Unlike stress (which is recomputed
     * from scratch every solve), damage accumulates and survives re-solves — it
     * is NOT touched by {@link #resetStress()}. Damage lowers the node's
     * effective load capacity, so a sufficiently damaged node fails under its
     * own existing gravity load. This is what explosions (and repeated hits)
     * write to; gravity stress stays a separate, transient quantity.
     */
    private double damage;

    /**
     * Persistent reinforcement multiplier on this node's load capacity, ≥ 1.0
     * (1.0 = unreinforced). A support beam / rebar raises this so the node can
     * carry more before failing. Like {@link #damage} — and unlike stress — it
     * survives re-solves and is NOT touched by {@link #resetStress()}. It scales
     * {@link #effectiveMaxLoad()} multiplicatively, so reinforcement and damage
     * compose honestly: a reinforced block still degrades by the same fraction
     * when hit, it just starts from a higher capacity.
     */
    private double reinforcement = 1.0;

    /**
     * Transient temperature-strength multiplier on load capacity, in (0, 1]. 1.0
     * (the default) means "no thermal softening" — full strength. The adapter
     * sets it each scan from the block's current temperature via
     * {@code ThermalStrength.capacityFactor}, mirroring how entity/snow LOAD is a
     * transient the adapter feeds in: it is NEVER persisted and is NOT touched by
     * {@link #resetStress()}. It scales {@link #effectiveMaxLoad()}
     * multiplicatively alongside reinforcement and damage, so a hot block carries
     * less without any of those being changed.
     *
     * <p>Default 1.0 is the whole flag-off story: when temperature strength is
     * disabled the adapter never sets this, so it stays 1.0 and
     * {@code effectiveMaxLoad} is byte-identical to before.
     */
    private double temperatureCapacityFactor = 1.0;

    /**
     * Create a new node.
     *
     * @param pos      Where the node is located
     * @param spec     The physical properties (mass and maxLoad)
     * @param grounded Is this node a foundation? (connected to ground)
     */
    public Node(NodePos pos, MaterialSpec spec, boolean grounded) {
        this.pos = pos;
        this.spec = spec;
        this.grounded = grounded;
        this.verticalStress = 0.0;
        this.momentStress = 0.0;
    }

    /**
     * Convenience: create a ground/foundation node.
     */
    public static Node ground(NodePos pos) {
        return new Node(pos, MaterialSpec.GROUND, true);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  GETTERS
    // ─────────────────────────────────────────────────────────────────────

    public NodePos pos() {
        return pos;
    }

    public MaterialSpec spec() {
        return spec;
    }

    /**
     * How heavy is this node?
     */
    public double mass() {
        return spec.mass();
    }

    /**
     * How much weight can this node hold before breaking, when undamaged.
     */
    public double maxLoad() {
        return spec.maxLoad();
    }

    /**
     * Effective capacity after accounting for reinforcement and accumulated
     * damage: {@code maxLoad × reinforcement × (1 − damage)}. Ground is never
     * weakened or reinforced.
     */
    public double effectiveMaxLoad() {
        if (spec.isGround()) {
            return spec.maxLoad();
        }
        return spec.maxLoad() * reinforcement * temperatureCapacityFactor * (1.0 - damage);
    }

    /**
     * The transient temperature-strength multiplier (1.0 = no thermal softening).
     */
    public double temperatureCapacityFactor() {
        return temperatureCapacityFactor;
    }

    /**
     * Set the transient temperature-strength multiplier. Only the adapter's
     * temperature task should call this; it is clamped to (0, 1] (a factor never
     * strengthens a block, and a zero would make the block unconditionally
     * overloaded — which the curve approaches but never reaches, so we floor at a
     * tiny positive value to keep {@code effectiveMaxLoad} positive and finite).
     * Not persisted; survives a solve but is meant to be refreshed each scan.
     */
    public void setTemperatureCapacityFactor(double factor) {
        if (Double.isNaN(factor)) {
            return;
        }
        this.temperatureCapacityFactor = Math.max(MIN_TEMPERATURE_FACTOR, Math.min(1.0, factor));
    }

    /**
     * Floor for the temperature factor: the Eurocode curves reach ~0 near a
     * material's failure temperature, but a literal 0 capacity would read as
     * NaN/∞ stress. We clamp to a tiny positive instead, so a block at its failure
     * temperature is "as good as gone" (overloaded by almost any load) without
     * tripping the divide-by-zero guard in {@link #stressPercent()}.
     */
    private static final double MIN_TEMPERATURE_FACTOR = 1e-6;

    /**
     * Reinforcement multiplier on load capacity (1.0 = unreinforced).
     */
    public double reinforcement() {
        return reinforcement;
    }

    /**
     * Set the reinforcement multiplier. Reinforcement only ever strengthens, so
     * values below 1.0 are clamped up to 1.0. Persistent across solves.
     */
    public void setReinforcement(double multiplier) {
        this.reinforcement = Math.max(1.0, multiplier);
    }

    /**
     * Accumulated structural damage (0 = pristine, 1 = destroyed).
     */
    public double damage() {
        return damage;
    }

    /**
     * Add structural damage (e.g. from a blast). Clamped to [0, 1] and
     * persistent across solves.
     */
    public void addDamage(double amount) {
        this.damage = Math.max(0.0, Math.min(1.0, this.damage + amount));
    }

    /**
     * Has this node taken enough damage to be considered destroyed?
     */
    public boolean isDestroyed() {
        return damage >= 1.0;
    }

    /**
     * Clear all accumulated damage, restoring full (reinforcement-adjusted)
     * capacity. Used by a repair action; reinforcement is left untouched.
     */
    public void repair() {
        this.damage = 0.0;
    }

    public boolean isGrounded() {
        return grounded;
    }

    /**
     * The actual load/stress on this node (in weight units).
     * Total stress = verticalStress + momentStress
     */
    public double stressValue() {
        return verticalStress + momentStress;
    }

    /**
     * Vertical stress component: own weight plus load from above.
     */
    public double verticalStress() {
        return verticalStress;
    }

    /**
     * Moment stress component: torque from unsupported horizontal arms.
     */
    public double momentStress() {
        return momentStress;
    }

    /**
     * Stress as a percentage of max capacity (0.0 to 1.0+).
     *
     * <pre>
     *   Example:
     *     Node with maxLoad = 60
     *     Current stress = 30
     *     stressPercent = 30/60 = 0.5 (50%)
     * </pre>
     */
    public double stressPercent() {
        if (spec.isGround()) {
            return 0.0; // Ground never stressed
        }
        double capacity = effectiveMaxLoad();
        if (capacity <= 0.0) {
            return Double.POSITIVE_INFINITY; // fully damaged -> always overloaded
        }
        return stressValue() / capacity;
    }

    /**
     * Is this node about to break? (stress > 100% of capacity)
     */
    public boolean isOverloaded() {
        return stressPercent() > 1.0;
    }

    /**
     * THE canonical "distress" of this node: a 0..1 scalar for how close it is to
     * trouble, taken as the worse of two honest reasons —
     *
     * <pre>
     *   distress = max( clamp(stressPercent, 0..1) , damage )
     * </pre>
     *
     * <ul>
     *   <li>{@code stressPercent} — how close it is to failing under its CURRENT
     *       load (a heavily-loaded wall is visibly working). Clamped to [0,1]; a
     *       non-finite (fully-damaged) stress reads as a full 1.0.
     *   <li>{@code damage} — accumulated micro-fracture that persists even when
     *       unloaded; it really IS cracked, so it counts.
     * </ul>
     *
     * <p>Ground is never distressed (returns 0). This is the single definition
     * every "is this block in trouble" question in the engine should ask. It is
     * allocation-free (returns one double, builds no object) so callers may invoke
     * it on the hot path.
     */
    public double distress() {
        if (grounded || spec.isGround()) {
            return 0.0;
        }
        double stress = stressPercent();
        double clampedStress = Double.isFinite(stress) ? Math.min(1.0, Math.max(0.0, stress)) : 1.0;
        return Math.max(clampedStress, damage);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SETTERS (used by the solver)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Set the vertical stress component. Only the solver should call this.
     */
    public void setVerticalStress(double verticalStress) {
        this.verticalStress = verticalStress;
    }

    /**
     * Set the moment stress component. Only the solver should call this.
     */
    public void setMomentStress(double momentStress) {
        this.momentStress = momentStress;
    }

    /**
     * Reset both stress components to zero. Called at start of solve pass.
     */
    public void resetStress() {
        this.verticalStress = 0.0;
        this.momentStress = 0.0;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  DISPLAY
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public String toString() {
        int percent = (int) (stressPercent() * 100);
        return "Node @ " + pos + " [" + percent + "% stress]";
    }
}
