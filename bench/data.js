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
          "id": "68d9555072c932c83216d8de47ccca3015df35aa",
          "message": "docs: changelog for the 1.x series\n\nBring the public changelog up to date with everything shipped since\n1.0.0 \u2014 the async settle, budget-bound cascades, memory eviction, the\nphysics correctness fixes, config hardening, the benchmark dashboard\nnames, and the solver refactors.",
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
          "message": "fix(build): benchmark-name self-test skips when python3 is absent\n\nA bare 'python3' Exec wired into check dies with ProcessExecutionException\non JDK-only environments and takes the whole build down. Resolve the\ninterpreter from fixed locations at configuration time and skip with a\nwarning when absent \u2014 the benchmark pipeline that runs the humanizer\nprovides its own python.",
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
};
