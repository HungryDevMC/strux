# Developing strux

Build and test instructions for working on the **Structural Integrity Engine** (*strux*)
— a voxel block-physics engine that makes structures collapse realistically (stress →
overload → cascade), plus game-specific adapters.

## Prerequisites

- **JDK 21** is required. Gradle's toolchain support auto-downloads it on first use.
- If `java` isn't on your `PATH` or `JAVA_HOME` isn't set, prefix Gradle commands, e.g.
  `JAVA_HOME=/path/to/jdk21 ./gradlew <task>`.

## Build & test

The Gradle wrapper handles everything:

```bash
./gradlew build                          # full build: spotlessCheck + all module tests + perf gate
./gradlew :core:test                     # core physics tests + deterministic perf gate (fast, 1–2s)
./gradlew :adapter-minecraft:shadowJar   # build the Minecraft plugin JAR -> adapter-minecraft/build/libs/
./gradlew spotlessApply                  # auto-fix formatting before committing
./gradlew :benchmarks:jmh                # real ns/op benchmarks (slow)
```

**Fast feedback loop.** While iterating, run `./gradlew :core:test` — it runs the
behavioural-snapshot regression suite plus the deterministic perf gate and is the real
signal for physics changes.

**Snapshot regression.** The core ships a behavioural-snapshot regression suite (scenario
DSL under `core/src/test/java/.../scenario/`, golden files under
`core/src/test/resources/snapshots/`). After an *intentional* physics change, regenerate
the snapshots and review the diff:

```bash
./gradlew test -Pupdate-snapshots
```

**Perf gate.** `PerformanceGateTest` asserts `StruxMetrics` work-counts, so performance is
checked deterministically and machine-independently on every run. When a change
intentionally alters cost, update the budgets in the same commit and say so in the commit
body. For hot-path changes (solver / cascade / blast) also run JMH and compare to
`benchmarks/baseline.json`.

## Module layout

- `core/` — **pure physics, zero game code.** Package `dev.gesp.structural.*`. Reasons
  about *nodes* and *edges*, never "blocks" or game types. Key pieces:
  `graph/StructureGraph`, `solver/{StressSolver,CascadeEngine}`,
  `blast/StruxExplosionEngine`, `model/{Node,NodePos,MaterialSpec}`.
- `adapter-minecraft/` — Paper/Spigot plugin. Maps Materials → mass/maxLoad, routes
  block-break/place and explosion events through the core.
- `benchmarks/` — JMH perf benchmarks.

**Hard rule:** never leak game-specific types into `core/`. Adapters depend on core, never
the reverse. Rationale and accepted constraints are in [`DESIGN.md`](DESIGN.md).

## Recordings

The core can record a physics session to a `.strx` file and re-simulate it deterministically
(`dev.gesp.structural.recording`); the Minecraft adapter exposes this via `/strux record`.
Recorded `.strx` fixtures under `core/src/test/resources/recordings/` double as regression
goldens (see `RecordedSessionRegressionTest`).

## Conventions

- **Commits:** [Conventional Commits](https://www.conventionalcommits.org/) with a module
  scope — `feat(adapter-minecraft): ...`, `refactor(core)!: ...`, `chore(tooling): ...`.
- **Formatting:** palantir-java-format via Spotless (4-space indent). Run
  `./gradlew spotlessApply` before committing; `spotlessCheck` runs as part of `build`.
- **Imports:** never use non-static wildcard imports (`import foo.bar.*;`) — always import
  specific classes. Static wildcards for test assertions
  (`import static org.junit.jupiter.api.Assertions.*;`) are the one allowed exception,
  enforced by the `checkNoWildcardImports` Gradle task. Also avoid inline fully-qualified
  references in the code body — add an `import` and use the short name.
- **Philosophy** (see [`DESIGN.md`](DESIGN.md)): keep the physics honest — tune material
  values, don't add fake safety multipliers. Keep the code simple enough that a beginner
  can follow it.

## CI / releases

GitHub Actions runs the whole pipeline — no configuration or secrets beyond the
default `GITHUB_TOKEN`.

- **Every push to `main` and every pull request** runs `./gradlew build` (all module
  tests + `spotlessCheck` + `checkNoWildcardImports` + the deterministic
  `PerformanceGateTest`), assembles the plugin jar, and uploads it (plus test reports on
  failure) as workflow artifacts. See [`.github/workflows/ci.yml`](.github/workflows/ci.yml).
- **Pushes to `main`** also run a short JMH profile and publish per-commit performance
  history to the [benchmarks chart](https://hungrydevmc.github.io/strux/bench/). This job
  is `continue-on-error`, so noisy shared runners never block CI.
- **Cutting a release** is just pushing a `v*` tag — CI builds, tests, and attaches the
  shadow jar to an auto-generated GitHub Release
  ([`.github/workflows/release.yml`](.github/workflows/release.yml)):

  ```bash
  git tag v1.0.1 && git push --tags
  ```

## Documentation

Docs are the source of truth for players, admins, and developers. Every behavioural change
updates the relevant page(s) under `docs/wiki/` and adds a `docs/changelog.md` entry under
`[Unreleased]` with its audience tag.
