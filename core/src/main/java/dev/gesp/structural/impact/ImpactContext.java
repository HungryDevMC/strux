package dev.gesp.structural.impact;

import dev.gesp.structural.model.NodePos;

/**
 * The description of one kinetic impact — an arrow, a catapult stone, a
 * battering-ram strike — fed to {@link ImpactEngine}.
 *
 * <pre>
 *   ImpactContext.builder()
 *       .origin(hitPos)               // the block the projectile struck
 *       .direction(vx, vy, vz)        // travel direction (need not be normalised)
 *       .energy(0.5 * mass * speed²)  // kinetic energy, in the same units as maxLoad
 *       .build();
 * </pre>
 *
 * <p>The engine is unit-agnostic: it has {@code mass} and {@code maxLoad} but no
 * length or time, so kinetic energy ({@code ½·m·v²}) cannot be expressed here.
 * The <em>caller</em> (the game adapter, where real velocity exists) computes the
 * energy scalar; the engine only spends it. A fast arrow and a slow boulder
 * differ purely by the {@code energy} they carry — nothing about the projectile
 * is hard-coded into the physics.
 *
 * @param origin    the block first struck (the surface hit)
 * @param dirX      travel direction X (used to ray-cast penetration)
 * @param dirY      travel direction Y
 * @param dirZ      travel direction Z
 * @param energy    kinetic energy carried into the structure (must be &gt; 0)
 */
public record ImpactContext(NodePos origin, double dirX, double dirY, double dirZ, double energy) {

    public ImpactContext {
        if (origin == null) {
            throw new IllegalArgumentException("origin is required");
        }
        if (energy <= 0) {
            throw new IllegalArgumentException("energy must be positive: " + energy);
        }
    }

    /** Length of the (un-normalised) direction vector; 0 means a point impact. */
    public double directionLength() {
        return Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder. Direction defaults to zero — a point impact that only
     * loads the {@code origin} block (no penetration); set it for a projectile
     * that should punch through along its trajectory.
     */
    public static final class Builder {
        private NodePos origin;
        private double dirX = 0.0;
        private double dirY = 0.0;
        private double dirZ = 0.0;
        private double energy;

        public Builder origin(NodePos origin) {
            this.origin = origin;
            return this;
        }

        public Builder direction(double dirX, double dirY, double dirZ) {
            this.dirX = dirX;
            this.dirY = dirY;
            this.dirZ = dirZ;
            return this;
        }

        public Builder energy(double energy) {
            this.energy = energy;
            return this;
        }

        public ImpactContext build() {
            return new ImpactContext(origin, dirX, dirY, dirZ, energy);
        }
    }
}
