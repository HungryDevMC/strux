package dev.gesp.structural.fire;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.model.MaterialSpec;

/**
 * Turns time spent in heat into persistent structural damage — the slow-burn
 * counterpart to the instantaneous blast and impact. Fire weakens a structure
 * the same honest way everything else does: it feeds {@code Node.damage}, which
 * lowers {@code effectiveMaxLoad}, so a burnt block eventually fails under the
 * load it was already carrying and the normal cascade takes over.
 *
 * <pre>
 *   direct burn (block is on fire):     fireDamagePerTick / fireResistance  per tick
 *   radiant heat (block next to fire):  direct × fireRadiantFactor          per tick
 * </pre>
 *
 * <p>Dividing by the material's {@code fireResistance} is the whole physics:
 * wood (low resistance) chars away fast, stone (high) barely notices, and a
 * metal frame — which never "burns" — can still be cooked down over a long
 * enough fire via the radiant term. No fake multipliers; just a rate per
 * material.
 *
 * <p>This is a pure calculator: it computes how much damage to add, but does not
 * decide <em>when</em> a block is in heat (that is the adapter's job, reading the
 * game world) nor apply the collapse (the existing settle path does that). That
 * keeps it unit-agnostic and trivially testable.
 */
public final class FireModel {

    private final PhysicsConfig config;

    public FireModel(PhysicsConfig config) {
        this.config = config;
    }

    /** Persistent damage a directly-burning block of this material takes over {@code ticks} ticks. */
    public double burnDamage(MaterialSpec spec, int ticks) {
        return ratePerTick(spec) * ticks;
    }

    /**
     * Persistent damage a block of this material takes from radiant heat (it is
     * next to fire/lava but not itself burning) over {@code ticks} ticks.
     */
    public double radiantDamage(MaterialSpec spec, int ticks) {
        return ratePerTick(spec) * config.getFireRadiantFactor() * ticks;
    }

    /** Per-tick direct-burn rate for this material: base rate softened by its fire resistance. */
    private double ratePerTick(MaterialSpec spec) {
        return config.getFireDamagePerTick() / spec.fireResistance();
    }
}
