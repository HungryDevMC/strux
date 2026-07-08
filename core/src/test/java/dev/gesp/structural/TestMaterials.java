package dev.gesp.structural;

import dev.gesp.structural.model.MaterialSpec;

/**
 * Common material specs for testing.
 *
 * <pre>
 *   These are TEST values - adapters will define their own.
 *
 *   ┌──────────┬──────┬─────────┬─────────────────────────┐
 *   │ Material │ Mass │ MaxLoad │ Can hold (blocks tall)  │
 *   ├──────────┼──────┼─────────┼─────────────────────────┤
 *   │ LIGHT    │ 1.0  │ 20.0    │ 20 blocks               │
 *   │ MEDIUM   │ 2.0  │ 50.0    │ 25 blocks               │
 *   │ HEAVY    │ 3.0  │ 100.0   │ 33 blocks               │
 *   └──────────┴──────┴─────────┴─────────────────────────┘
 * </pre>
 */
public final class TestMaterials {

    private TestMaterials() {} // No instances

    /** Light material: mass=1, maxLoad=20 */
    public static final MaterialSpec LIGHT = new MaterialSpec(1.0, 20.0);

    /** Medium material: mass=2, maxLoad=50 */
    public static final MaterialSpec MEDIUM = new MaterialSpec(2.0, 50.0);

    /** Heavy material: mass=3, maxLoad=100 */
    public static final MaterialSpec HEAVY = new MaterialSpec(3.0, 100.0);
}
