# Benchmarks (JMH)

Wall-clock microbenchmarks for the `:core` physics engine. These give real
ns/op numbers for **tuning** — the complement to the deterministic
`PerformanceGateTest` in `:core`, which catches *algorithmic* regressions on any
machine but does not measure time.

| Layer | What it answers | When it runs |
|-------|-----------------|--------------|
| `core` `PerformanceGateTest` | "Did the algorithm start doing more work?" (solver passes, node visits) | every `./gradlew test` |
| `benchmarks` (this module) | "How many microseconds does a pass cost?" | on demand |

## Run

```bash
# All benchmarks → build/results/jmh/results.json
./gradlew :benchmarks:jmh

# Only matching benchmarks (regex passed straight to JMH)
./gradlew :benchmarks:jmh -PjmhArgs="Cascade"

# Faster, rougher run while iterating (fewer iterations / one fork)
./gradlew :benchmarks:jmh -PjmhArgs="-wi 1 -i 3 -f 1 -w 1 -r 1"
```

## Red-green-refactor for performance

1. `./gradlew :benchmarks:jmh` and commit the result as `baseline.json` (done once;
   re-baseline deliberately when hardware or the intended cost changes).
2. Make your change.
3. Re-run and compare the fresh `build/results/jmh/results.json` against
   `baseline.json`:

   ```bash
   # score (us/op) per benchmark, baseline vs current
   paste \
     <(jq -r '.[] | "\(.benchmark) \(.params//{}|tostring)\t\(.primaryMetric.score)"' benchmarks/baseline.json) \
     <(jq -r '.[] | "\(.primaryMetric.score)"'                                   benchmarks/build/results/jmh/results.json)
   ```

A regression shows up as a higher score; a win shows up as a lower one. The error
bars (`primaryMetric.scoreError`) tell you whether a difference is real or noise.

## What's measured

- `SolverBenchmark` — one full stress solve over a 5×5 tower (non-mutating;
  isolates per-pass cost, which cascade/blast call repeatedly).
- `CascadeBenchmark` — full settle after a break: a tall column (bulk floating
  collapse) and a bridge pillar knock-out (progressive trim-back).
- `BlastBenchmark` — full explosion on a solid tower, with occlusion `NONE` vs
  `RAYCAST` (raycast cover-sampling is the costlier path).

Mutating benchmarks run on a fresh `StructureGraph.copy()` each invocation.
