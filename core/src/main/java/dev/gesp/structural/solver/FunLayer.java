package dev.gesp.structural.solver;

import dev.gesp.structural.model.MaterialSpec;

/**
 * Transforms raw physics stress into gameplay-tuned stress.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                        FUN LAYER                                   │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  This layer sits between the physics solver output and game        │
 *   │  consequences (visual effects, collapse triggers, etc.)            │
 *   │                                                                     │
 *   │  PURPOSE:                                                          │
 *   │    • Compress the low end (0-40% all looks similar - "safe")       │
 *   │    • Exaggerate the high end (80-100% amplified for drama)         │
 *   │    • Apply material personality:                                   │
 *   │        - Stone: fails sharp and sudden                             │
 *   │        - Wood: degrades slowly, creaks before breaking             │
 *   │                                                                     │
 *   │  USAGE:                                                            │
 *   │    FunLayer fun = new FunLayer();                                  │
 *   │    double displayStress = fun.transform(node.stressPercent(), mat);│
 *   │    // Use displayStress for visuals and effects                    │
 *   │                                                                     │
 *   │  The physics solver always computes accurate stress.               │
 *   │  FunLayer only affects how it FEELS to the player.                 │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class FunLayer {

    /**
     * Transform physics stress into display/gameplay stress.
     *
     * Currently returns the input unchanged (identity function).
     * Override this method or modify the implementation to tune gameplay feel.
     *
     * @param physicsStress the raw stress percentage from the solver (0.0 to 1.0+)
     * @param material the material of the block (for material-specific behavior)
     * @return the transformed stress value for gameplay purposes
     */
    public double transform(double physicsStress, MaterialSpec material) {
        // Identity transformation for now - tune later for gameplay feel
        //
        // Future ideas:
        // - Compress 0-40% to 0-20% (everything looks safe)
        // - Linear 40-80% to 20-80% (gradual concern)
        // - Expand 80-100% to 80-100% with steeper curve (dramatic tension)
        // - Material modifiers: stone +10% visual stress, wood -10%
        return physicsStress;
    }

    /**
     * Get the visual stress category for effects and sounds.
     *
     * @param physicsStress the raw stress percentage from the solver
     * @return the stress category
     */
    public StressCategory categorize(double physicsStress) {
        if (physicsStress < 0.4) {
            return StressCategory.SAFE;
        } else if (physicsStress < 0.6) {
            return StressCategory.NOMINAL;
        } else if (physicsStress < 0.8) {
            return StressCategory.WARNING;
        } else if (physicsStress < 0.95) {
            return StressCategory.DANGER;
        } else if (physicsStress <= 1.0) {
            return StressCategory.CRITICAL;
        } else {
            return StressCategory.OVERLOADED;
        }
    }

    /**
     * Stress categories for visual/audio feedback.
     */
    public enum StressCategory {
        /** 0-40%: No visible stress, structure is comfortable */
        SAFE,
        /** 40-60%: Minor stress indicators, structure is working */
        NOMINAL,
        /** 60-80%: Visible cracks, creaking sounds */
        WARNING,
        /** 80-95%: Heavy damage visuals, loud stress sounds */
        DANGER,
        /** 95-100%: About to break, intense effects */
        CRITICAL,
        /** >100%: Block has failed, triggers collapse */
        OVERLOADED
    }
}
