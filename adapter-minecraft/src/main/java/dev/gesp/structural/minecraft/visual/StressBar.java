package dev.gesp.structural.minecraft.visual;

/**
 * Pure helpers for the live stress readout.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                          STRESS BAR                                │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Turns a stress percentage into a little filled bar:               │
 *   │                                                                     │
 *   │     0%   →  ░░░░░░                                                  │
 *   │    34%   →  ██░░░░                                                  │
 *   │   100%   →  ██████                                                  │
 *   │                                                                     │
 *   │  Each cell is one slice of the bar. We fill a cell once stress      │
 *   │  reaches that slice, rounding to the nearest cell so a near-full    │
 *   │  bar reads as full and a near-empty one reads as empty.            │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>No game types here on purpose: this is a string function, unit-tested
 * directly, so the task that renders the action bar stays a thin wiring layer.
 */
public final class StressBar {

    /** The filled-cell glyph (a full block). */
    public static final char FILLED = '█';

    /** The empty-cell glyph (a light shade). */
    public static final char EMPTY = '░';

    private StressBar() {}

    /**
     * Render {@code fraction} (0.0 = empty, 1.0 = full) as a {@code cells}-wide
     * bar of {@link #FILLED}/{@link #EMPTY} glyphs.
     *
     * <p>The fraction is clamped to {@code [0, 1]} first (an over-100% stress
     * still reads as a full bar, never more), then scaled to the cell count and
     * rounded to the nearest whole cell.
     *
     * @param fraction the stress fraction (typically {@code percent / 100.0})
     * @param cells    how many cells wide the bar is (must be at least 1)
     * @return a string of exactly {@code cells} glyphs
     * @throws IllegalArgumentException if {@code cells < 1}
     */
    public static String bar(double fraction, int cells) {
        if (cells < 1) {
            throw new IllegalArgumentException("cells must be >= 1, was " + cells);
        }
        double clamped = Math.max(0.0, Math.min(1.0, fraction));
        int filled = (int) Math.round(clamped * cells);
        // Round can hand back the full count near the top; never overflow.
        if (filled > cells) {
            filled = cells;
        }
        StringBuilder sb = new StringBuilder(cells);
        for (int i = 0; i < cells; i++) {
            sb.append(i < filled ? FILLED : EMPTY);
        }
        return sb.toString();
    }
}
