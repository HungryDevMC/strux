# core

The pure-physics heart of strux. **Zero runtime dependencies** — just the JDK.

## What lives here

| Package | Purpose |
| --- | --- |
| `model/` | Domain types: `NodePos`, `Node`, `MaterialSpec` |
| `graph/` | `StructureGraph` — connected-nodes data structure + adjacency |
| `solver/` | `StressSolver` (per-node vertical + moment), `CascadeEngine` (progressive collapse) |
| `blast/` | `StruxExplosionEngine` — direct destruction + persistent damage + secondary cascade |
| `persistence/` | DTOs (`StructureData`, `BlockData`), the `PersistenceAdapter` interface, and a file-based implementation |
| `metrics/` | `StruxMetrics` — deterministic work-counter used by the perf gate |
| `api/` | `SolverCallback`, `CollapseReason` — observation hooks for adapters |
| `config/` | `PhysicsConfig` — solver knobs (debris impact, moment multiplier, cascade caps) |

## The hard rule

**No game types in core.** No `org.bukkit.*`, no `com.hypixel.hytale.*`, no anything that
ties core to a specific game. The `CoreHasNoGameTypesTest` (under `src/test/java/.../arch/`)
fails the build if anyone ever breaks this.

Why: keeps the engine reusable across games (Minecraft, Hytale, untold, anything voxel-shaped)
and keeps the physics testable in isolation. See [`DESIGN.md`](../DESIGN.md).

## Test harness

`./gradlew :core:test` runs:

1. **Behavioural snapshot regression** (DSL scenarios under `src/test/java/.../scenario/`,
   golden files under `src/test/resources/snapshots/`). Catches *meaning* changes.
2. **Deterministic perf gate** (`PerformanceGateTest` — asserts `StruxMetrics` work-counts).
   Catches *algorithmic regressions* in a machine-independent way.
3. **ArchUnit invariants** (`CoreHasNoGameTypesTest`). Catches accidental coupling.

Intentional physics changes regenerate snapshots: `./gradlew test -Pupdate-snapshots`.

For real ns/op numbers, run JMH instead: `./gradlew :benchmarks:jmh`.
