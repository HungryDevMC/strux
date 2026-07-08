window.BENCHMARK_DATA = {
  "lastUpdate": 1783521151350,
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
          "id": "0bbf676a60f1e9e8b83d06b3c56f2ba78067d40c",
          "message": "release: StructuralIntegrity 1.0.0\n\nDeterministic structural-physics engine for Minecraft (Paper): scope-bounded\nstress solving and cascades, async off-thread settling, budget-bound collapse\npacing, component-granularity memory eviction (opt-in), CoreProtect-aware\nrestore detection, and a physics-service seam for alternative runtimes.\nShips with JMH benchmarks and a plain-English perf dashboard.",
          "timestamp": "2026-07-08T16:30:29+02:00",
          "tree_id": "87670f18ccfb95e2ea810eab72f62fff09ff1c14",
          "url": "https://github.com/HungryDevMC/strux/commit/0bbf676a60f1e9e8b83d06b3c56f2ba78067d40c"
        },
        "date": 1783521150819,
        "tool": "jmh",
        "benches": [
          {
            "name": "Cascade: knock out one pillar of a 21-wide bridge so the deck cantilevers and trims back",
            "value": 104.71621872817205,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "Cascade: a 40-block-tall column loses its base and drops as floating debris",
            "value": 54.569328664051376,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "Scoped cascade: break a block mid-tower on 200x200 siege terrain (same tower, large connected world - terrain size should not matter)",
            "value": 444543.212,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "Scoped cascade: break a block mid-tower on 50x50 siege terrain (~375-block tower on a small connected world)",
            "value": 32147.523387563135,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "Scoped stress-solve of one structure in a shared world (should stay flat as the world grows) (structures = 1) ( {\"structures\":\"1\"} )",
            "value": 4.603765863544669,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "Scoped stress-solve of one structure in a shared world (should stay flat as the world grows) (structures = 50) ( {\"structures\":\"50\"} )",
            "value": 4.071451172903284,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "Scoped stress-solve of one structure in a shared world (should stay flat as the world grows) (structures = 200) ( {\"structures\":\"200\"} )",
            "value": 5.629701875707876,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "Whole-world stress-solve: every structure in the world (grows with structure count) (structures = 1) ( {\"structures\":\"1\"} )",
            "value": 4.502361721049759,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "Whole-world stress-solve: every structure in the world (grows with structure count) (structures = 50) ( {\"structures\":\"50\"} )",
            "value": 583.0368530283021,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "Whole-world stress-solve: every structure in the world (grows with structure count) (structures = 200) ( {\"structures\":\"200\"} )",
            "value": 3286.5864348422547,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "One full stress solve over a 5x5xheight stone tower (height = 4) ( {\"height\":\"4\"} )",
            "value": 39.367749520162455,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "One full stress solve over a 5x5xheight stone tower (height = 10) ( {\"height\":\"10\"} )",
            "value": 200.65926466739003,
            "unit": "us/op",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      }
    ]
  }
}