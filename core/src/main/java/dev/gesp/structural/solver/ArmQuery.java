package dev.gesp.structural.solver;

import dev.gesp.structural.model.NodePos;

/** A single arm question: the arm root plus the anchor's distance-from-ground. */
record ArmQuery(NodePos startPos, int anchorDistance) {}
