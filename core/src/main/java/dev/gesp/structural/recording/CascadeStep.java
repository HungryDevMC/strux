package dev.gesp.structural.recording;

import dev.gesp.structural.model.NodePos;

/**
 * A single step in a cascade collapse sequence.
 *
 * @param pos        position of the collapsed block
 * @param materialId material identifier of the collapsed block
 * @param stepNumber which step in the cascade (1-indexed)
 * @param reason     why this block collapsed: "FLOATING" or "OVERLOADED"
 */
public record CascadeStep(NodePos pos, String materialId, int stepNumber, String reason) {}
