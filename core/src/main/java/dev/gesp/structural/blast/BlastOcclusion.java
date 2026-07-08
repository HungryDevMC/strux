package dev.gesp.structural.blast;

/**
 * Whether blocks between the blast center and a target shield it.
 */
public enum BlastOcclusion {

    /** Ignore cover — the blast passes through everything. */
    NONE,

    /** Solid blocks along the line of sight attenuate the blast (bunkers work). */
    RAYCAST
}
