window.BENCHMARK_DATA = {
  "lastUpdate": 1783504102547,
  "repoUrl": "https://github.com/HungryDevMC/strux",
  "entries": {
    "strux JMH benchmarks": [
      {
        "commit": {
          "author": {
            "email": "algorithm1401@gmail.com",
            "name": "HungryDevMC",
            "username": "HungryDevMC"
          },
          "committer": {
            "email": "algorithm1401@gmail.com",
            "name": "HungryDevMC",
            "username": "HungryDevMC"
          },
          "distinct": true,
          "id": "ab3c3444896b34265e4706da18215c2d88b0ed49",
          "message": "docs: add release, platform, and benchmark badges",
          "timestamp": "2026-07-03T15:57:44+02:00",
          "tree_id": "7bb1aa40c6518c5840d1a4e5050d2f2b4a273f36",
          "url": "https://github.com/HungryDevMC/strux/commit/ab3c3444896b34265e4706da18215c2d88b0ed49"
        },
        "date": 1783087140204,
        "tool": "jmh",
        "benches": [
          {
            "name": "dev.gesp.structural.bench.CascadeBenchmark.bridgeTrimBack",
            "value": 170.87219426739458,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "dev.gesp.structural.bench.CascadeBenchmark.columnFullCollapse",
            "value": 70.65577709978231,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "dev.gesp.structural.bench.CascadeBenchmark.siegeTowerLarge",
            "value": 557685.9508333333,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "dev.gesp.structural.bench.CascadeBenchmark.siegeTowerSmall",
            "value": 45375.49038210678,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "dev.gesp.structural.bench.ScopedSolveBenchmark.solveOneComponent ( {\"structures\":\"1\"} )",
            "value": 5.221697136627257,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "dev.gesp.structural.bench.ScopedSolveBenchmark.solveOneComponent ( {\"structures\":\"50\"} )",
            "value": 5.016381433575481,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "dev.gesp.structural.bench.ScopedSolveBenchmark.solveOneComponent ( {\"structures\":\"200\"} )",
            "value": 5.21643780323837,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "dev.gesp.structural.bench.ScopedSolveBenchmark.solveWholeWorld ( {\"structures\":\"1\"} )",
            "value": 5.22546313769928,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "dev.gesp.structural.bench.ScopedSolveBenchmark.solveWholeWorld ( {\"structures\":\"50\"} )",
            "value": 542.8995048516408,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "dev.gesp.structural.bench.ScopedSolveBenchmark.solveWholeWorld ( {\"structures\":\"200\"} )",
            "value": 3411.8216639691145,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "dev.gesp.structural.bench.SolverBenchmark.solveAll ( {\"height\":\"4\"} )",
            "value": 47.846233832860094,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "dev.gesp.structural.bench.SolverBenchmark.solveAll ( {\"height\":\"10\"} )",
            "value": 126.81732110497954,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "hungrydev@Robins-MacBook-Pro.local",
            "name": "Robin Peeters"
          },
          "committer": {
            "email": "hungrydev@Robins-MacBook-Pro.local",
            "name": "Robin Peeters"
          },
          "distinct": true,
          "id": "0815bae5fb30b81d09c224e11867b08802e416f3",
          "message": "sync: engine update — async off-thread settle, budget-bound cascades, memory eviction, physics fixes, config hardening\n\nBrings the public engine up to the current internal release since v1.0.0:\n\n- Big collapses now settle off the main thread (cascade.async-settle, default on),\n  applied a few blocks per tick; fall set and order are byte-identical to synchronous.\n- Per-tick collapse budget (cascade.settle-budget-ms) is now genuinely enforced mid-step\n  and resumable; default lowered 30 -> 8. Paused and unpaused runs are byte-identical.\n- Component memory eviction (memory.eviction, default off): idle structures park to a\n  side store once all their chunks unload and restore bit-identically on chunk load.\n- Minecraft adapter now drives collapse through a PhysicsService seam (LocalPhysicsService\n  by default); pure refactor, behaviour and metrics unchanged.\n- Physics correctness: scope-honest scoped stress solve, blast-overload boundary fix,\n  piston and falling-block collapses, parallel-cascade survivor-stress fix, startup graph\n  merge no longer clobbers plugin-registered structures.\n- Async settle no longer thrashes under fire damage (asyncSettleDamageSkips counter).\n- CoreProtect restore-scan throttling (bounded lookups, scheduled, defer-during-cascade).\n- Config hardening: container-weight.enabled falls back to off; reference docs match\n  real defaults; newly documented keys.\n\nSolver internals simplified (moment-arm indexing folded into the stress solver).",
          "timestamp": "2026-07-08T11:44:52+02:00",
          "tree_id": "07ddb8cafa07e7cb6af2031fa8b60c4731229a91",
          "url": "https://github.com/HungryDevMC/strux/commit/0815bae5fb30b81d09c224e11867b08802e416f3"
        },
        "date": 1783504102232,
        "tool": "jmh",
        "benches": [
          {
            "name": "dev.gesp.structural.bench.CascadeBenchmark.bridgeTrimBack",
            "value": 158.03355410049969,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "dev.gesp.structural.bench.CascadeBenchmark.columnFullCollapse",
            "value": 67.54075450415853,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "dev.gesp.structural.bench.CascadeBenchmark.siegeTowerLarge",
            "value": 476422.23983333335,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "dev.gesp.structural.bench.CascadeBenchmark.siegeTowerSmall",
            "value": 40225.78262137384,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "dev.gesp.structural.bench.ScopedSolveBenchmark.solveOneComponent ( {\"structures\":\"1\"} )",
            "value": 4.899909911340537,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "dev.gesp.structural.bench.ScopedSolveBenchmark.solveOneComponent ( {\"structures\":\"50\"} )",
            "value": 4.952026648602865,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "dev.gesp.structural.bench.ScopedSolveBenchmark.solveOneComponent ( {\"structures\":\"200\"} )",
            "value": 4.865963251567997,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "dev.gesp.structural.bench.ScopedSolveBenchmark.solveWholeWorld ( {\"structures\":\"1\"} )",
            "value": 4.9836054202517355,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "dev.gesp.structural.bench.ScopedSolveBenchmark.solveWholeWorld ( {\"structures\":\"50\"} )",
            "value": 562.2399747884407,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "dev.gesp.structural.bench.ScopedSolveBenchmark.solveWholeWorld ( {\"structures\":\"200\"} )",
            "value": 3902.5245987547537,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "dev.gesp.structural.bench.SolverBenchmark.solveAll ( {\"height\":\"4\"} )",
            "value": 50.64838815652268,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "dev.gesp.structural.bench.SolverBenchmark.solveAll ( {\"height\":\"10\"} )",
            "value": 129.95969420438556,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      }
    ]
  }
}