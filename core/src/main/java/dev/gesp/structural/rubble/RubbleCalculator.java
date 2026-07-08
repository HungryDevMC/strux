package dev.gesp.structural.rubble;

import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.model.CollapsedNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Calculates which collapsed blocks survive to become rubble.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                    RUBBLE SURVIVAL LOGIC                           │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  When blocks collapse, some become rubble (falling entities)       │
 *   │  while others shatter on impact. Survival depends on:              │
 *   │                                                                     │
 *   │  1. BLAST RESISTANCE                                               │
 *   │     Higher = tougher block = more likely to survive                │
 *   │     Stone (1.5) survives better than Glass (0.3)                   │
 *   │                                                                     │
 *   │  2. FALL HEIGHT                                                    │
 *   │     Higher = more impact = less likely to survive                  │
 *   │     Short falls preserve most blocks, long falls destroy them      │
 *   │                                                                     │
 *   │  FORMULA:                                                          │
 *   │    survivalChance = blastRes / (blastRes + fallHeight × factor)    │
 *   │    finalChance = max(survivalChance × baseChance, minChance)       │
 *   │                                                                     │
 *   │  EXAMPLE (factor=0.5, baseChance=1.0, minChance=0.1):              │
 *   │                                                                     │
 *   │    Stone (blastRes=1.5) falling 10 blocks:                         │
 *   │      chance = 1.5 / (1.5 + 10×0.5) = 1.5 / 6.5 = 23%              │
 *   │                                                                     │
 *   │    Obsidian (blastRes=6.0) falling 10 blocks:                      │
 *   │      chance = 6.0 / (6.0 + 10×0.5) = 6.0 / 11.0 = 55%             │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class RubbleCalculator {

    private final PhysicsConfig config;
    private final Random random;

    public RubbleCalculator(PhysicsConfig config) {
        this.config = config;
        this.random = new Random();
    }

    public RubbleCalculator(PhysicsConfig config, Random random) {
        this.config = config;
        this.random = random;
    }

    /**
     * Calculate which collapsed blocks survive to become rubble.
     *
     * @param collapsedNodes the nodes that collapsed
     * @param groundLevel the Y level considered "ground" for fall calculations
     * @return list of nodes that should become rubble (falling entities)
     */
    public List<RubbleCandidate> calculateRubble(List<CollapsedNode> collapsedNodes, int groundLevel) {
        if (!config.isRubbleEnabled()) {
            return List.of();
        }

        List<RubbleCandidate> rubble = new ArrayList<>();

        for (CollapsedNode node : collapsedNodes) {
            int fallHeight = Math.max(0, node.y() - groundLevel);
            double survivalChance = calculateSurvivalChance(node.blastResistance(), fallHeight);

            if (random.nextDouble() < survivalChance) {
                rubble.add(new RubbleCandidate(node, fallHeight, survivalChance));
            }
        }

        return rubble;
    }

    /**
     * Calculate the survival chance for a block with given properties.
     *
     * @param blastResistance the block's blast resistance (higher = tougher)
     * @param fallHeight how far the block fell
     * @return probability (0.0 to 1.0) that the block survives as rubble
     */
    public double calculateSurvivalChance(double blastResistance, int fallHeight) {
        double factor = config.getRubbleFallDamageFactor();
        double baseChance = config.getRubbleBaseChance();
        double minChance = config.getRubbleMinChance();

        // survivalChance = blastRes / (blastRes + fallHeight × factor)
        double denominator = blastResistance + (fallHeight * factor);
        double rawChance = denominator > 0 ? blastResistance / denominator : 1.0;

        // Apply base chance multiplier and floor
        double finalChance = rawChance * baseChance;
        return Math.max(finalChance, minChance);
    }

    /**
     * Represents a block that survived collapse and should become rubble.
     *
     * @param node the collapsed node data
     * @param fallHeight how far it will fall to ground
     * @param survivalChance the probability that was rolled for this block
     */
    public record RubbleCandidate(CollapsedNode node, int fallHeight, double survivalChance) {

        /**
         * The position where rubble should spawn.
         */
        public int x() {
            return node.pos().x();
        }

        public int y() {
            return node.pos().y();
        }

        public int z() {
            return node.pos().z();
        }
    }
}
