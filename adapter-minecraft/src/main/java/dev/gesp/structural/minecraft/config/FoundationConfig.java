package dev.gesp.structural.minecraft.config;

import org.bukkit.Material;

/**
 * Configuration for anchoring structures to NATURAL TERRAIN.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                       FOUNDATION CONFIG                           │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Lets builds anchor to real ground without exposed bedrock.        │
 *   │                                                                     │
 *   │  depthGroundingEnabled  treat a block as grounded if it has N      │
 *   │                         solid terrain blocks straight below it      │
 *   │  minDepth               how many terrain blocks (N) must be below   │
 *   │  foundationBlock        a material that anchors when set on terrain │
 *   │                                                                     │
 *   │  DEFAULTS PRESERVE OLD BEHAVIOUR: depth grounding is OFF and there  │
 *   │  is no foundation block, so an existing install grounds builds      │
 *   │  exactly as before (bedrock + world-floor only).                   │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class FoundationConfig {

    // Off by default so existing installs are unchanged: enabling depth grounding
    // would silently re-ground every build that sits on dirt/stone.
    private boolean depthGroundingEnabled = false;

    // How many contiguous solid terrain blocks must sit straight below a block
    // before it counts as resting on solid ground.
    private int minDepth = 4;

    // The anchor material. null = no foundation block configured.
    private Material foundationBlock = null;

    public boolean isDepthGroundingEnabled() {
        return depthGroundingEnabled;
    }

    public void setDepthGroundingEnabled(boolean depthGroundingEnabled) {
        this.depthGroundingEnabled = depthGroundingEnabled;
    }

    public int getMinDepth() {
        return minDepth;
    }

    /**
     * Set how many terrain blocks must be below a block to ground it. Clamped to
     * at least 1 — a depth of 0 would ground every block with anything beneath it,
     * which is never the intent.
     */
    public void setMinDepth(int minDepth) {
        this.minDepth = Math.max(1, minDepth);
    }

    public Material getFoundationBlock() {
        return foundationBlock;
    }

    public void setFoundationBlock(Material foundationBlock) {
        this.foundationBlock = foundationBlock;
    }

    /** Whether a foundation/anchor material has been configured. */
    public boolean hasFoundationBlock() {
        return foundationBlock != null;
    }

    /** Whether the given material is the configured foundation/anchor block. */
    public boolean isFoundationBlock(Material material) {
        return foundationBlock != null && foundationBlock == material;
    }

    @Override
    public String toString() {
        return "FoundationConfig{" + "depthGroundingEnabled="
                + depthGroundingEnabled + ", minDepth="
                + minDepth + ", foundationBlock="
                + foundationBlock + '}';
    }
}
