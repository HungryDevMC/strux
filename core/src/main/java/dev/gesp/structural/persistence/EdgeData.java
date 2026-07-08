package dev.gesp.structural.persistence;

/**
 * Serializable representation of one undirected edge for persistence.
 *
 * <p>Only written for worlds whose topology is NOT derivable from the grid
 * (explicit {@code connect()} graphs, or grids with {@code disconnect()}ed
 * joints) — plain block worlds re-derive their adjacency from positions on
 * load and never pay for edge storage. Stored once per edge, endpoints in
 * lexicographic order.
 */
public record EdgeData(int x1, int y1, int z1, int x2, int y2, int z2) {}
