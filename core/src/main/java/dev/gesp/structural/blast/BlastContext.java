package dev.gesp.structural.blast;

import dev.gesp.structural.model.NodePos;

/**
 * The description of one explosion, fed to {@link StruxExplosionEngine}.
 *
 * <pre>
 *   BlastContext.builder()
 *       .center(pos)
 *       .power(4.0)                 // ~TNT
 *       .falloff(BlastFalloff.QUADRATIC)
 *       .occlusion(BlastOcclusion.RAYCAST)
 *       .build();
 * </pre>
 *
 * @param center    where the blast originates (may be empty space)
 * @param power     blast strength; drives radius and intensity
 * @param shape     reach geometry
 * @param falloff   how intensity drops with distance
 * @param occlusion whether cover shields the blast
 */
public record BlastContext(
        NodePos center, double power, BlastShape shape, BlastFalloff falloff, BlastOcclusion occlusion) {

    public BlastContext {
        if (center == null) {
            throw new IllegalArgumentException("center is required");
        }
        if (power <= 0) {
            throw new IllegalArgumentException("power must be positive: " + power);
        }
        if (shape == null || falloff == null || occlusion == null) {
            throw new IllegalArgumentException("shape, falloff and occlusion are required");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder with sensible defaults (SPHERE, QUADRATIC, RAYCAST). */
    public static final class Builder {
        private NodePos center;
        private double power = 4.0;
        private BlastShape shape = BlastShape.SPHERE;
        private BlastFalloff falloff = BlastFalloff.QUADRATIC;
        private BlastOcclusion occlusion = BlastOcclusion.RAYCAST;

        public Builder center(NodePos center) {
            this.center = center;
            return this;
        }

        public Builder power(double power) {
            this.power = power;
            return this;
        }

        public Builder shape(BlastShape shape) {
            this.shape = shape;
            return this;
        }

        public Builder falloff(BlastFalloff falloff) {
            this.falloff = falloff;
            return this;
        }

        public Builder occlusion(BlastOcclusion occlusion) {
            this.occlusion = occlusion;
            return this;
        }

        public BlastContext build() {
            return new BlastContext(center, power, shape, falloff, occlusion);
        }
    }
}
