package dev.gesp.structural.assess;

import dev.gesp.structural.model.NodePos;

/**
 * The verdict of a global tipping check: does a structure's center of mass sit
 * over its base, or has it walked out past the edge so the whole thing topples?
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                        WILL IT TIP OVER?                           │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Stack of blocks, seen from the side:                              │
 *   │                                                                     │
 *   │        ▢▢▢▢                ▢▢▢▢                                    │
 *   │        ▢▢▢▢                    ▢▢▢▢                                │
 *   │        ▢▢▢▢                        ▢▢▢▢                            │
 *   │       ──┬──── ground          ──┬──── ground                       │
 *   │         •  CoM over base         •  CoM past the edge → TIPS       │
 *   │       STABLE                   pivot here ┘                         │
 *   │                                                                     │
 *   │  This is the same rule a crane operator uses: keep the center of   │
 *   │  mass inside the footprint of the legs (the "support polygon").    │
 *   │  Step the CoM outside that footprint and gravity rotates the body  │
 *   │  about the nearest edge.                                           │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * @param tips              {@code true} if the center of mass projects outside the
 *                          support polygon (the structure topples).
 * @param pivotEdgeMidpoint the lattice point nearest the hull edge the body would
 *                          rotate about, rounded to integer coordinates at {@code y=0};
 *                          {@code null} when {@link #tips} is {@code false}.
 * @param dirX              x-component of the (unit) topple direction — the outward
 *                          normal of the pivot edge; {@code 0} when stable.
 * @param dirZ              z-component of the (unit) topple direction; {@code 0} when stable.
 */
public record TipResult(boolean tips, NodePos pivotEdgeMidpoint, double dirX, double dirZ) {

    /** The verdict for a structure that is stable — CoM safely over its base. */
    public static TipResult stable() {
        return new TipResult(false, null, 0.0, 0.0);
    }
}
