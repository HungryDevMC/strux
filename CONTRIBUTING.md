# Contributing to strux

Thanks for your interest! This guide covers the basics for working on the
**Structural Integrity Engine** locally and submitting changes.

## Prerequisites

- **JDK 21** (Gradle auto-downloads the toolchain on first use).
- **Git**
- *(Optional)* Docker + Docker Compose for the bundled Paper/Spigot test server
  under `docker/`.

## Build and test

The Gradle wrapper handles everything:

```bash
./gradlew build                          # full build: spotlessCheck + all tests + perf gate
./gradlew :core:test                     # core physics tests + deterministic perf gate
./gradlew :adapter-minecraft:shadowJar   # build the Minecraft plugin JAR
./gradlew spotlessApply                  # auto-fix formatting before committing
./gradlew :benchmarks:jmh                # real ns/op benchmarks (slow)
```

If `JAVA_HOME` isn't set in your shell, prefix any command:
`JAVA_HOME=/path/to/jdk21 ./gradlew ...`

## Project layout

- `core/` — pure physics engine, **no game code**.
- `adapter-minecraft/` — Paper/Spigot plugin (Java 21).
- `benchmarks/` — JMH perf benchmarks.

The hard rule: **never leak game-specific types into `core/`**. Adapters depend
on core; core never depends on an adapter. See [`DESIGN.md`](DESIGN.md) for the
reasoning and the constraints it imposes.

## Code conventions

- **Format:** palantir-java-format via Spotless (4-space). Run
  `./gradlew spotlessApply` before committing; `spotlessCheck` runs as part of
  `build`.
- **Imports:** no non-static wildcard imports (`import foo.*;`), and no inline
  fully-qualified class references (`java.util.Collections.emptyList()`) — always
  add an `import` and use the short name. Static wildcards for test assertions
  (`import static org.junit.jupiter.api.Assertions.*;`) are the one allowed
  exception. The `checkNoWildcardImports` Gradle task enforces the first rule.
- **Commits:** [Conventional Commits](https://www.conventionalcommits.org/) with
  module scope, e.g. `feat(adapter-minecraft): ...`, `refactor(core)!: ...`,
  `chore(tooling): ...`.

## Tests and perf

The core ships a **behavioural-snapshot regression suite** (golden files under
`core/src/test/resources/snapshots/`) plus a **deterministic perf gate**
(`StruxMetrics` work-counts, machine-independent). Both run on every
`./gradlew :core:test`.

- After an *intentional* physics change, regenerate snapshots:
  `./gradlew test -Pupdate-snapshots`, then review the snapshot git diff before
  committing.
- For hot-path changes (solver / cascade / blast), also run JMH for real ns/op
  and compare to `benchmarks/baseline.json`: `./gradlew :benchmarks:jmh`. The
  perf gate catches algorithmic regressions cheaply; JMH catches per-pass cost.

## Backlog & WIP workflow

Work is intake-gated. Every actionable item is a Markdown file under
[`backlog/`](backlog/README.md); the folder it lives in is its status
(`ideas → analyzed → ready → in-progress → done`). Move a file between folders with
`git mv` to change its status.

The workflow, in short:

1. Capture an idea in `backlog/ideas/` — a title and a *What & why*.
2. Fill in the **Analysis** section, then move it to `backlog/analyzed/`.
3. Complete the Definition of Ready (Spec / Acceptance criteria / E2E / docs / risk),
   then move it to `backlog/ready/`.
4. Pull it into `backlog/in-progress/` to build it, and to `backlog/done/` when it ships.

Keep the **WIP limit at 2**: at most two files in `in-progress/` at a time — finish or
park one before starting a third. See [`backlog/README.md`](backlog/README.md) for the
full Definition of Ready.

For build/test conventions and the module layout, see [`DEVELOPING.md`](DEVELOPING.md).

## Submitting changes

1. Fork the repo, branch off `main`.
2. Make your change. Run `./gradlew build` locally — it must pass.
3. Push, then open a pull request against `main`.
4. CI re-runs the build. Reviewers look at correctness, conventions, and
   that any physics change comes with snapshot/benchmark evidence.

## Reporting bugs and security issues

- **Bugs and feature requests:** use [GitHub issues](https://github.com/HungryDevMC/strux/issues).
- **Security vulnerabilities:** see [`SECURITY.md`](SECURITY.md) — please report
  privately, not in a public issue.
