package dev.gesp.structural.config;

/**
 * All the physics settings you can tweak.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                    PHYSICS CONFIG EXPLAINED                        │
 *   │                   (for 12-year-olds and up!)                       │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  WHAT IS STRESS?                                                   │
 *   │  ───────────────                                                   │
 *   │  Stress is how much "effort" a block needs to hold up other       │
 *   │  blocks. When stress goes over 100%, the block breaks!            │
 *   │                                                                     │
 *   │  There are TWO types of stress:                                    │
 *   │                                                                     │
 *   │                                                                     │
 *   │  1. VERTICAL STRESS (weight pushing down)                          │
 *   │  ─────────────────────────────────────────                         │
 *   │                                                                     │
 *   │      [C]  ← C's weight pushes down                                │
 *   │       │                                                            │
 *   │      [B]  ← B holds C's weight + its own                          │
 *   │       │                                                            │
 *   │      [A]  ← A holds B + C's weight + its own                      │
 *   │       │                                                            │
 *   │     [GND]                                                          │
 *   │                                                                     │
 *   │  Like stacking books - the bottom book feels all the weight!      │
 *   │                                                                     │
 *   │                                                                     │
 *   │  2. MOMENT STRESS (sideways pulling/torque)                        │
 *   │  ───────────────────────────────────────────                       │
 *   │                                                                     │
 *   │     [GND]──[A]──[B]──[C]──[D]                                     │
 *   │             ↑                                                      │
 *   │        A feels "pulled down" by B, C, D                           │
 *   │        (like holding a heavy bucket at arm's length)              │
 *   │                                                                     │
 *   │  This is called a CANTILEVER - blocks sticking out sideways.      │
 *   │  The block nearest the support has the most moment stress.        │
 *   │                                                                     │
 *   │                                                                     │
 *   │  BEAM vs CANTILEVER                                                │
 *   │  ───────────────────                                               │
 *   │                                                                     │
 *   │  CANTILEVER (one support):     BEAM (two supports):               │
 *   │                                                                     │
 *   │  [GND]──[A]──[B]──[C]         [GND]──[A]──[B]──[C]──[GND]         │
 *   │         └─── lots of                 └─── much less               │
 *   │              moment stress!               moment stress!          │
 *   │                                                                     │
 *   │  Beams are MUCH stronger because both ends share the load.        │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class PhysicsConfig {

    // ═══════════════════════════════════════════════════════════════════════
    //  MOMENT (CANTILEVER) SETTINGS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * How much moment stress matters.
     *
     * <pre>
     *   momentMultiplier = 1.0  → Normal (realistic physics)
     *   momentMultiplier = 0.5  → Half as much moment stress (more forgiving)
     *   momentMultiplier = 2.0  → Double moment stress (harder)
     *   momentMultiplier = 0.0  → No moment stress at all (cantilevers never break)
     * </pre>
     *
     * DEFAULT: 1.0 (realistic)
     */
    private double momentMultiplier = 1.0;

    /**
     * How much to reduce moment stress for beams (supported on both ends).
     *
     * <pre>
     *   beamMomentReduction = 1.0  → Beams have NO moment stress (current behavior)
     *   beamMomentReduction = 0.8  → Beams have 20% of normal moment stress
     *   beamMomentReduction = 0.5  → Beams have 50% of normal moment stress
     *   beamMomentReduction = 0.0  → Beams are treated same as cantilevers
     *
     *   WHY THIS MATTERS:
     *   Right now, beams have zero moment stress, so you can build a 34-block
     *   tower on a beam no problem. If you want beams to still feel SOME stress,
     *   lower this value.
     * </pre>
     *
     * DEFAULT: 1.0 (beams have no moment stress)
     */
    private double beamMomentReduction = 1.0;

    /**
     * Whether a beam's bending strength grows with the SQUARE of its section
     * depth — the textbook section modulus S = b·d²/6.
     *
     * <pre>
     *   bendingDepthEnabled = false → every beam fails at the same arm length,
     *                                 no matter how thick it is (legacy model)
     *   bendingDepthEnabled = true  → a beam d nodes deep (its vertical thickness,
     *                                 perpendicular to a horizontal span) carries
     *                                 the same applied moment at 1/d² the stress,
     *                                 so a deeper beam is genuinely stronger —
     *                                 exactly like a tall steel I-beam.
     *
     *   WHY IT IS OFF BY DEFAULT:
     *   Turning it on changes WHICH blocks survive a collapse for any structure
     *   with a beam more than one node thick (a one-thick beam is unaffected —
     *   d=1 ⇒ d²=1). It also redefines a horizontal cantilever's moment to bend
     *   in its own plane, so a thick deck behaves like a stack of independent
     *   beam layers rather than one lumped mass. That is a deliberate, visible
     *   physics change, so a server opts in.
     * </pre>
     *
     * DEFAULT: false (legacy: depth does not affect bending strength)
     */
    private boolean bendingDepthEnabled = false;

    // ═══════════════════════════════════════════════════════════════════════
    //  CASCADE (CHAIN REACTION) SETTINGS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Maximum number of blocks that can collapse in one chain reaction.
     *
     * <pre>
     *   WHY LIMIT THIS?
     *   If someone builds a HUGE structure and breaks one block, we don't
     *   want the server to freeze calculating thousands of collapses.
     *
     *   maxCascadeSteps = 50   → Max 50 blocks collapse per trigger
     *   maxCascadeSteps = 100  → Max 100 blocks collapse per trigger
     *   maxCascadeSteps = 10   → Only 10 blocks, very limited chain reactions
     * </pre>
     *
     * DEFAULT: 50
     */
    private int maxCascadeSteps = 50;

    // ═══════════════════════════════════════════════════════════════════════
    //  VISUAL SETTINGS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * How often to update stress particles (in server ticks).
     *
     * <pre>
     *   20 ticks = 1 second
     *
     *   visualUpdateTicks = 10  → Update every 0.5 seconds (smooth but more CPU)
     *   visualUpdateTicks = 20  → Update every 1 second (balanced)
     *   visualUpdateTicks = 40  → Update every 2 seconds (less CPU, choppy)
     * </pre>
     *
     * DEFAULT: 10 (every 0.5 seconds)
     */
    private int visualUpdateTicks = 10;

    /**
     * Whether blocks under critical stress (≥95%) visibly wobble for a moment
     * before they can fail — a purely visual telegraph that the block is about
     * to go.
     *
     * <pre>
     *   preCollapseShake = true   → critical blocks rock on their base (default)
     *   preCollapseShake = false  → no wobble (saves a few client-side entities)
     * </pre>
     *
     * DEFAULT: true
     */
    private boolean preCollapseShake = true;

    // ═══════════════════════════════════════════════════════════════════════
    //  DEBUG SETTINGS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Print detailed stress calculations to console.
     *
     * <pre>
     *   Useful for understanding why blocks are breaking or not breaking.
     *   Turn OFF for normal gameplay (lots of spam in console).
     * </pre>
     *
     * DEFAULT: false
     */
    private boolean debugLogging = false;

    // ═══════════════════════════════════════════════════════════════════════
    //  BLAST (EXPLOSION) SETTINGS
    // ═══════════════════════════════════════════════════════════════════════

    /** Blast radius = power × this. Bigger = larger crater per unit power. DEFAULT 1.5 */
    private double blastRadiusPerPower = 1.5;

    /**
     * Blast intensity at/above which a block is shattered outright (the crater);
     * below it the block only takes persistent damage (cracks). DEFAULT 2.0
     */
    private double destructionThreshold = 2.0;

    /** Fraction of sub-lethal blast intensity turned into persistent damage. DEFAULT 0.5 */
    private double damageScale = 0.5;

    /** Blast intensity lost per solid block of cover between center and target. DEFAULT 0.25 */
    private double occlusionAttenuation = 0.25;

    /**
     * How hard falling debris loads what it lands on. A collapsing node drops
     * its mass onto the first standing block below, adding damage of
     * {@code mass × dropHeight × this / target.maxLoad}. 0 = no debris loading
     * (collapses just vanish); higher = collapses pancake further down.
     * DEFAULT 0.0 (engine default off; game adapters opt in).
     */
    private double debrisImpactScale = 0.0;

    /** Debris must fall at least this many blocks before it loads anything. DEFAULT 2 */
    private int minImpactDrop = 2;

    // ═══════════════════════════════════════════════════════════════════════
    //  KINETIC IMPACT (PROJECTILE / RAM) SETTINGS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Energy a blast-resistance-1.0 block can absorb before it is cracked clean
     * through. A block's absorption budget is {@code blastResistance × this}; the
     * fraction of it an impact actually delivers becomes persistent damage. So a
     * projectile carrying less than this only cracks the surface (impact fatigue:
     * repeated hits accumulate), while one carrying several times this punches
     * through and keeps going. Tune against your adapter's projectile energies
     * ({@code ½·mass·speed²}). DEFAULT 4.0
     */
    private double impactPenetrationCost = 4.0;

    /**
     * Fraction of absorbed-energy/toughness turned into persistent damage. 1.0 =
     * a block that absorbs its full toughness is destroyed; lower makes impacts
     * more forgiving (more hits to break). DEFAULT 1.0
     */
    private double impactDamageScale = 1.0;

    /**
     * Hard cap on how many blocks one impact can bore through, even with huge
     * energy. Bounds both gameplay (a boulder can't tunnel across a whole base)
     * and the path march. DEFAULT 6
     */
    private int impactMaxPenetration = 6;

    // ═══════════════════════════════════════════════════════════════════════
    //  CRACK VISUAL SETTINGS (presentation only — no physics)
    // ═══════════════════════════════════════════════════════════════════════
    //
    //  A node is drawn cracked from its "distress" = max(stressPercent, damage):
    //  how hard it is working under load, or how much blast/impact damage it has
    //  accumulated, whichever is worse. These thresholds bucket that 0..1 scalar
    //  into HAIRLINE / CRACKED / CRUMBLING. They affect what players SEE, never
    //  when a block actually fails (that is stressPercent > 1.0, unchanged).

    /** Distress at/above which faint (hairline) cracks appear. DEFAULT 0.60 */
    private double crackHairlineThreshold = 0.60;

    /** Distress at/above which clear cracks appear. DEFAULT 0.78 */
    private double crackCrackedThreshold = 0.78;

    /** Distress at/above which heavy "about to crumble" cracks appear. DEFAULT 0.90 */
    private double crackCrumblingThreshold = 0.90;

    // ═══════════════════════════════════════════════════════════════════════
    //  FIRE (HEAT) DEGRADATION SETTINGS
    // ═══════════════════════════════════════════════════════════════════════
    //
    //  Sustained fire weakens a structure over time: a burning block loses load
    //  capacity each tick (persistent damage, like a blast), and blocks merely
    //  next to fire/lava lose it slowly (radiant heat). Both are divided by the
    //  material's fireResistance, so wood chars away fast while stone barely
    //  notices — and a metal frame can still be cooked down by a long enough fire.

    /**
     * Capacity-damage a fireResistance-1.0 block directly on fire takes PER game
     * tick. A material's actual rate is {@code this / fireResistance}. Sized so a
     * resistance-1 block reaches full damage in roughly half a minute of fire,
     * leaving a counterplay window (water, rain). DEFAULT 0.0006
     */
    private double fireDamagePerTick = 0.0006;

    /**
     * Radiant heat as a fraction of the direct-burn rate: a block merely adjacent
     * to fire/lava (not itself burning) degrades at {@code fireDamagePerTick ×
     * this / fireResistance}. This is what lets fire weaken non-flammable stone
     * and metal — slowly. DEFAULT 0.25
     */
    private double fireRadiantFactor = 0.25;

    // ═══════════════════════════════════════════════════════════════════════
    //  TEMPERATURE (HEAT/COLD) STRENGTH SETTINGS
    // ═══════════════════════════════════════════════════════════════════════
    //
    //  A block's load CAPACITY scales with its current temperature, using real
    //  engineering strength-vs-temperature curves (Eurocode steel/concrete, wood
    //  char). A steel beam in a fire sags; stone near lava softens. This is a
    //  TRANSIENT effect (the adapter sets each tracked block's temperature from
    //  the world); it is never persisted. OFF by default — when off, the capacity
    //  factor is a flat 1.0 and behaviour is byte-identical to before.

    /**
     * Master switch for temperature-based strength.
     *
     * <pre>
     *   temperatureStrengthEnabled = false → temperature never changes capacity
     *                                        (default — byte-identical to legacy)
     *   temperatureStrengthEnabled = true  → a hot block carries less, a block
     *                                        near its failure temp barely stands
     * </pre>
     *
     * DEFAULT: false
     */
    private boolean temperatureStrengthEnabled = false;

    /**
     * Lower edge of the "comfort band" in °C: at or below the curve's own first
     * anchor a block is at full strength anyway, but this documents the ambient
     * temperature the adapter assigns to an unheated block. DEFAULT 20.0
     */
    private double comfortTemperatureC = 20.0;

    /**
     * Thermal-shock onset: a sudden temperature DROP smaller than this (°C) cracks
     * nothing (the block equalises in time). DEFAULT 150.0
     */
    private double thermalShockOnsetC = 150.0;

    /**
     * Thermal-shock span: the drop, ABOVE the onset, at which shock cracking
     * saturates (°C). A drop of {@code onset + span} delivers the material's full
     * shock sensitivity as persistent damage. DEFAULT 500.0
     */
    private double thermalShockSpanC = 500.0;

    // ═══════════════════════════════════════════════════════════════════════
    //  RUBBLE (FALLING DEBRIS) SETTINGS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Whether collapsed blocks can become rubble (falling entities).
     *
     * <pre>
     *   rubbleEnabled = false → Collapsed blocks just disappear (default)
     *   rubbleEnabled = true  → Some collapsed blocks fall as physical entities
     * </pre>
     *
     * DEFAULT: false
     */
    private boolean rubbleEnabled = false;

    /**
     * How much fall height affects rubble survival chance.
     *
     * <pre>
     *   survivalChance = blastResistance / (blastResistance + fallHeight × rubbleFallDamageFactor)
     *
     *   rubbleFallDamageFactor = 0.1  → Falls barely affect survival (most rubble survives)
     *   rubbleFallDamageFactor = 0.5  → Moderate effect (default)
     *   rubbleFallDamageFactor = 1.0  → Heavy effect (long falls destroy most blocks)
     * </pre>
     *
     * DEFAULT: 0.5
     */
    private double rubbleFallDamageFactor = 0.5;

    /**
     * Base survival chance multiplier for rubble.
     *
     * <pre>
     *   rubbleBaseChance = 1.0  → 100% of calculated survival chance (default)
     *   rubbleBaseChance = 0.5  → 50% of blocks that would survive actually become rubble
     *   rubbleBaseChance = 0.3  → Only 30% become rubble (sparse debris)
     * </pre>
     *
     * DEFAULT: 1.0
     */
    private double rubbleBaseChance = 1.0;

    /**
     * Minimum survival chance for rubble (even fragile blocks from tall falls get this chance).
     *
     * <pre>
     *   rubbleMinChance = 0.0  → No minimum (some blocks will never survive)
     *   rubbleMinChance = 0.1  → At least 10% chance to become rubble (default)
     *   rubbleMinChance = 0.5  → At least 50% chance (lots of debris)
     * </pre>
     *
     * DEFAULT: 0.1
     */
    private double rubbleMinChance = 0.1;

    // ═══════════════════════════════════════════════════════════════════════
    //  CONSTRUCTORS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Create config with default values.
     */
    public PhysicsConfig() {}

    /**
     * Create config with all values specified.
     */
    public PhysicsConfig(
            double momentMultiplier,
            double beamMomentReduction,
            int maxCascadeSteps,
            int visualUpdateTicks,
            boolean debugLogging) {
        this.momentMultiplier = momentMultiplier;
        this.beamMomentReduction = beamMomentReduction;
        this.maxCascadeSteps = maxCascadeSteps;
        this.visualUpdateTicks = visualUpdateTicks;
        this.debugLogging = debugLogging;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  GETTERS
    // ═══════════════════════════════════════════════════════════════════════

    public double getMomentMultiplier() {
        return momentMultiplier;
    }

    public double getBeamMomentReduction() {
        return beamMomentReduction;
    }

    public boolean isBendingDepthEnabled() {
        return bendingDepthEnabled;
    }

    public int getMaxCascadeSteps() {
        return maxCascadeSteps;
    }

    public int getVisualUpdateTicks() {
        return visualUpdateTicks;
    }

    public boolean isPreCollapseShake() {
        return preCollapseShake;
    }

    public boolean isDebugLogging() {
        return debugLogging;
    }

    public double getBlastRadiusPerPower() {
        return blastRadiusPerPower;
    }

    public double getDestructionThreshold() {
        return destructionThreshold;
    }

    public double getDamageScale() {
        return damageScale;
    }

    public double getOcclusionAttenuation() {
        return occlusionAttenuation;
    }

    public double getDebrisImpactScale() {
        return debrisImpactScale;
    }

    public int getMinImpactDrop() {
        return minImpactDrop;
    }

    public double getImpactPenetrationCost() {
        return impactPenetrationCost;
    }

    public double getImpactDamageScale() {
        return impactDamageScale;
    }

    public int getImpactMaxPenetration() {
        return impactMaxPenetration;
    }

    public double getCrackHairlineThreshold() {
        return crackHairlineThreshold;
    }

    public double getCrackCrackedThreshold() {
        return crackCrackedThreshold;
    }

    public double getCrackCrumblingThreshold() {
        return crackCrumblingThreshold;
    }

    public double getFireDamagePerTick() {
        return fireDamagePerTick;
    }

    public double getFireRadiantFactor() {
        return fireRadiantFactor;
    }

    public boolean isTemperatureStrengthEnabled() {
        return temperatureStrengthEnabled;
    }

    public double getComfortTemperatureC() {
        return comfortTemperatureC;
    }

    public double getThermalShockOnsetC() {
        return thermalShockOnsetC;
    }

    public double getThermalShockSpanC() {
        return thermalShockSpanC;
    }

    public boolean isRubbleEnabled() {
        return rubbleEnabled;
    }

    public double getRubbleFallDamageFactor() {
        return rubbleFallDamageFactor;
    }

    public double getRubbleBaseChance() {
        return rubbleBaseChance;
    }

    public double getRubbleMinChance() {
        return rubbleMinChance;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  SETTERS (for config loading)
    // ═══════════════════════════════════════════════════════════════════════

    public void setMomentMultiplier(double momentMultiplier) {
        this.momentMultiplier = momentMultiplier;
    }

    public void setBeamMomentReduction(double beamMomentReduction) {
        this.beamMomentReduction = beamMomentReduction;
    }

    public void setBendingDepthEnabled(boolean bendingDepthEnabled) {
        this.bendingDepthEnabled = bendingDepthEnabled;
    }

    public void setMaxCascadeSteps(int maxCascadeSteps) {
        this.maxCascadeSteps = maxCascadeSteps;
    }

    public void setVisualUpdateTicks(int visualUpdateTicks) {
        this.visualUpdateTicks = visualUpdateTicks;
    }

    public void setPreCollapseShake(boolean preCollapseShake) {
        this.preCollapseShake = preCollapseShake;
    }

    public void setDebugLogging(boolean debugLogging) {
        this.debugLogging = debugLogging;
    }

    public void setBlastRadiusPerPower(double blastRadiusPerPower) {
        this.blastRadiusPerPower = blastRadiusPerPower;
    }

    public void setDestructionThreshold(double destructionThreshold) {
        this.destructionThreshold = destructionThreshold;
    }

    public void setDamageScale(double damageScale) {
        this.damageScale = damageScale;
    }

    public void setOcclusionAttenuation(double occlusionAttenuation) {
        this.occlusionAttenuation = occlusionAttenuation;
    }

    public void setDebrisImpactScale(double debrisImpactScale) {
        this.debrisImpactScale = debrisImpactScale;
    }

    public void setMinImpactDrop(int minImpactDrop) {
        this.minImpactDrop = minImpactDrop;
    }

    public void setImpactPenetrationCost(double impactPenetrationCost) {
        this.impactPenetrationCost = impactPenetrationCost;
    }

    public void setImpactDamageScale(double impactDamageScale) {
        this.impactDamageScale = impactDamageScale;
    }

    public void setImpactMaxPenetration(int impactMaxPenetration) {
        this.impactMaxPenetration = impactMaxPenetration;
    }

    public void setCrackHairlineThreshold(double crackHairlineThreshold) {
        this.crackHairlineThreshold = crackHairlineThreshold;
    }

    public void setCrackCrackedThreshold(double crackCrackedThreshold) {
        this.crackCrackedThreshold = crackCrackedThreshold;
    }

    public void setCrackCrumblingThreshold(double crackCrumblingThreshold) {
        this.crackCrumblingThreshold = crackCrumblingThreshold;
    }

    public void setFireDamagePerTick(double fireDamagePerTick) {
        this.fireDamagePerTick = fireDamagePerTick;
    }

    public void setFireRadiantFactor(double fireRadiantFactor) {
        this.fireRadiantFactor = fireRadiantFactor;
    }

    public void setTemperatureStrengthEnabled(boolean temperatureStrengthEnabled) {
        this.temperatureStrengthEnabled = temperatureStrengthEnabled;
    }

    public void setComfortTemperatureC(double comfortTemperatureC) {
        this.comfortTemperatureC = comfortTemperatureC;
    }

    public void setThermalShockOnsetC(double thermalShockOnsetC) {
        this.thermalShockOnsetC = thermalShockOnsetC;
    }

    public void setThermalShockSpanC(double thermalShockSpanC) {
        this.thermalShockSpanC = thermalShockSpanC;
    }

    public void setRubbleEnabled(boolean rubbleEnabled) {
        this.rubbleEnabled = rubbleEnabled;
    }

    public void setRubbleFallDamageFactor(double rubbleFallDamageFactor) {
        this.rubbleFallDamageFactor = rubbleFallDamageFactor;
    }

    public void setRubbleBaseChance(double rubbleBaseChance) {
        this.rubbleBaseChance = rubbleBaseChance;
    }

    public void setRubbleMinChance(double rubbleMinChance) {
        this.rubbleMinChance = rubbleMinChance;
    }

    @Override
    public String toString() {
        return "PhysicsConfig{" + "momentMultiplier="
                + momentMultiplier + ", beamMomentReduction="
                + beamMomentReduction + ", maxCascadeSteps="
                + maxCascadeSteps + ", visualUpdateTicks="
                + visualUpdateTicks + ", debugLogging="
                + debugLogging + '}';
    }
}
