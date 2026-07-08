package dev.gesp.structural.solver;

/**
 * Information about a horizontal arm.
 */
class ArmInfo {
    final double totalMass; // Total mass of all blocks in the arm
    final int reach; // Number of blocks in the arm

    ArmInfo(double totalMass, int reach) {
        this.totalMass = totalMass;
        this.reach = reach;
    }
}
