package dev.gesp.structural.solver;

import dev.gesp.structural.model.NodePos;
import java.util.List;

/** Debug-only geometry for an answered arm query: its members (canonical order) and beam flag. */
record ArmDetail(List<NodePos> members, boolean isBeam) {}
