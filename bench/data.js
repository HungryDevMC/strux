window.BENCHMARK_DATA = {
  "lastUpdate": 1783515185034,
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
      },
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
          "id": "68d9555072c932c83216d8de47ccca3015df35aa",
          "message": "docs: changelog for the 1.x series\n\nBring the public changelog up to date with everything shipped since\n1.0.0 — the async settle, budget-bound cascades, memory eviction, the\nphysics correctness fixes, config hardening, the benchmark dashboard\nnames, and the solver refactors.",
          "timestamp": "2026-07-08T13:58:38+02:00",
          "tree_id": "70877d3c42f51c824b6a8da8d01b0291768b13df",
          "url": "https://github.com/HungryDevMC/strux/commit/68d9555072c932c83216d8de47ccca3015df35aa"
        },
        "date": 1783512235308,
        "tool": "jmh",
        "benches": [
          {
            "name": "Cascade: knock out one pillar of a 21-wide bridge so the deck cantilevers and trims back",
            "value": 178.45181269170135,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "Cascade: a 40-block-tall column loses its base and drops as floating debris",
            "value": 67.77679542402156,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "Scoped cascade: break a block mid-tower on 200x200 siege terrain (same tower, large connected world - terrain size should not matter)",
            "value": 509027.3943888889,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "Scoped cascade: break a block mid-tower on 50x50 siege terrain (~375-block tower on a small connected world)",
            "value": 36381.32478773451,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "Scoped stress-solve of one structure in a shared world (should stay flat as the world grows) (structures = 1) ( {\"structures\":\"1\"} )",
            "value": 5.445348673459285,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "Scoped stress-solve of one structure in a shared world (should stay flat as the world grows) (structures = 50) ( {\"structures\":\"50\"} )",
            "value": 5.979869114271646,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "Scoped stress-solve of one structure in a shared world (should stay flat as the world grows) (structures = 200) ( {\"structures\":\"200\"} )",
            "value": 4.986038235826171,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "Whole-world stress-solve: every structure in the world (grows with structure count) (structures = 1) ( {\"structures\":\"1\"} )",
            "value": 4.7477152308438875,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "Whole-world stress-solve: every structure in the world (grows with structure count) (structures = 50) ( {\"structures\":\"50\"} )",
            "value": 547.9386399048743,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "Whole-world stress-solve: every structure in the world (grows with structure count) (structures = 200) ( {\"structures\":\"200\"} )",
            "value": 3342.0534132802622,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "One full stress solve over a 5x5xheight stone tower (height = 4) ( {\"height\":\"4\"} )",
            "value": 47.2127385124381,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "One full stress solve over a 5x5xheight stone tower (height = 10) ( {\"height\":\"10\"} )",
            "value": 130.17463244739818,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
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
          "id": "68a236547b25d5b3b9bbed321c055bda51804910",
          "message": "fix(build): benchmark-name self-test skips when python3 is absent\n\nA bare 'python3' Exec wired into check dies with ProcessExecutionException\non JDK-only environments and takes the whole build down. Resolve the\ninterpreter from fixed locations at configuration time and skip with a\nwarning when absent — the benchmark pipeline that runs the humanizer\nprovides its own python.",
          "timestamp": "2026-07-08T14:51:35+02:00",
          "tree_id": "28757dd9e273aba04149134ddf09a89bf5fa1bdb",
          "url": "https://github.com/HungryDevMC/strux/commit/68a236547b25d5b3b9bbed321c055bda51804910"
        },
        "date": 1783515184233,
        "tool": "jmh",
        "benches": [
          {
            "name": "Cascade: knock out one pillar of a 21-wide bridge so the deck cantilevers and trims back",
            "value": 153.35553121169235,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "Cascade: a 40-block-tall column loses its base and drops as floating debris",
            "value": 64.66631042486172,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "Scoped cascade: break a block mid-tower on 200x200 siege terrain (same tower, large connected world - terrain size should not matter)",
            "value": 439866.09300000005,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "Scoped cascade: break a block mid-tower on 50x50 siege terrain (~375-block tower on a small connected world)",
            "value": 45423.67881446341,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "Scoped stress-solve of one structure in a shared world (should stay flat as the world grows) (structures = 1) ( {\"structures\":\"1\"} )",
            "value": 5.586619779128345,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "Scoped stress-solve of one structure in a shared world (should stay flat as the world grows) (structures = 50) ( {\"structures\":\"50\"} )",
            "value": 4.861886623378539,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "Scoped stress-solve of one structure in a shared world (should stay flat as the world grows) (structures = 200) ( {\"structures\":\"200\"} )",
            "value": 7.418009001744122,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "Whole-world stress-solve: every structure in the world (grows with structure count) (structures = 1) ( {\"structures\":\"1\"} )",
            "value": 4.6769029389506995,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "Whole-world stress-solve: every structure in the world (grows with structure count) (structures = 50) ( {\"structures\":\"50\"} )",
            "value": 620.2592545250791,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "Whole-world stress-solve: every structure in the world (grows with structure count) (structures = 200) ( {\"structures\":\"200\"} )",
            "value": 3959.9258568928885,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "One full stress solve over a 5x5xheight stone tower (height = 4) ( {\"height\":\"4\"} )",
            "value": 45.819048517511995,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "One full stress solve over a 5x5xheight stone tower (height = 10) ( {\"height\":\"10\"} )",
            "value": 194.26844654747825,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      }
    ]
  }
}