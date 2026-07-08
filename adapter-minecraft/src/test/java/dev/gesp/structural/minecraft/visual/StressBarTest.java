package dev.gesp.structural.minecraft.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure {@link StressBar#bar} glyph helper — percent → bar.
 * No game types, so this is a plain JUnit test of a string function.
 */
@DisplayName("StressBar: percent → ██░░░░ glyphs")
class StressBarTest {

    @Test
    @DisplayName("0% is all empty cells")
    void emptyBar() {
        assertEquals("░░░░░░", StressBar.bar(0.0, 6));
    }

    @Test
    @DisplayName("100% is all filled cells")
    void fullBar() {
        assertEquals("██████", StressBar.bar(1.0, 6));
    }

    @Test
    @DisplayName("the example from the spec: 34% over 6 cells fills 2")
    void specExample() {
        // 0.34 * 6 = 2.04 → rounds to 2 filled.
        assertEquals("██░░░░", StressBar.bar(0.34, 6));
    }

    @Test
    @DisplayName("rounds to the nearest cell, not floor")
    void roundsToNearest() {
        // 0.5 * 6 = 3.0 → 3 filled.
        assertEquals("███░░░", StressBar.bar(0.50, 6));
        // 0.58 * 6 = 3.48 → 3 filled.
        assertEquals("███░░░", StressBar.bar(0.58, 6));
        // 0.59 * 6 = 3.54 → 4 filled.
        assertEquals("████░░", StressBar.bar(0.59, 6));
    }

    @Test
    @DisplayName("a fraction above 1.0 clamps to a full bar, never overflows")
    void clampsAboveFull() {
        String bar = StressBar.bar(1.8, 6);
        assertEquals("██████", bar);
        assertEquals(6, bar.length(), "never longer than the cell count");
    }

    @Test
    @DisplayName("a negative fraction clamps to an empty bar")
    void clampsBelowZero() {
        assertEquals("░░░░░░", StressBar.bar(-0.5, 6));
    }

    @Test
    @DisplayName("the output is always exactly `cells` glyphs wide")
    void widthIsExact() {
        for (int cells = 1; cells <= 12; cells++) {
            for (double f = 0.0; f <= 1.0; f += 0.07) {
                assertEquals(cells, StressBar.bar(f, cells).length(), "width for cells=" + cells + " f=" + f);
            }
        }
    }

    @Test
    @DisplayName("every glyph is either the filled or the empty marker")
    void onlyKnownGlyphs() {
        String bar = StressBar.bar(0.42, 8);
        for (int i = 0; i < bar.length(); i++) {
            char c = bar.charAt(i);
            assertTrue(c == StressBar.FILLED || c == StressBar.EMPTY, "unexpected glyph: " + c);
        }
    }

    @Test
    @DisplayName("a non-positive cell count is rejected")
    void rejectsNonPositiveCells() {
        assertThrows(IllegalArgumentException.class, () -> StressBar.bar(0.5, 0));
        assertThrows(IllegalArgumentException.class, () -> StressBar.bar(0.5, -3));
    }

    @Test
    @DisplayName("a single-cell bar flips at the halfway mark")
    void singleCell() {
        assertEquals("░", StressBar.bar(0.49, 1));
        assertEquals("█", StressBar.bar(0.50, 1));
    }
}
