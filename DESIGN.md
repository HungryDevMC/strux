# Design Decisions & Future Considerations

This document tracks design decisions and ideas for future implementation.

---

## Core Principles

1. **The core is pure physics** - No game-specific code. Adapters define materials.
2. **Simple over clever** - A 12-year-old should understand the code.
3. **Tuning over hacks** - Adjust material values, don't add artificial safety factors.
4. **Unit-agnostic** - The core reasons about *nodes* and *edges*, never "blocks". A node can be a 1×1×1 block, a prefab, a truss joint, a vertex — anything.

---

## Generic Structures (Any Unit)

The solver and cascade engine only ever look at three things about a node:
its `mass`, its `maxLoad`/`grounded` status, and *which nodes it connects to*
(`StructureGraph.getNeighbors`). The only positional assumption is that `y` is
"up" (used to tell horizontal neighbors from vertical ones in the moment pass).
Nothing in the physics knows what a node represents.

That means a consumer can model whatever unit they like by choosing how to
build the graph:

```java
StructureGraph g = new StructureGraph();

// Grid case (1x1x1 blocks): let the engine derive 6-connectivity from coords.
g.addBlock(pos, spec, grounded);          // auto-connects to face neighbors

// Anything else (prefabs, vertices, trusses): declare topology yourself.
g.addNode(prefabCenter, spec, grounded);  // no auto-connect
g.addGroundNode(footing);
g.connect(prefabCenter, neighborCenter);  // YOU decide what supports what
```

- `addNode` / `addGroundNode` add a node with **no** edges.
- `connect` / `disconnect` define the edges (load paths) explicitly.
- `addBlock` / `addGroundBlock` are sugar for the grid case — `addNode` + an
  auto-`connect` to existing face-adjacent neighbors.

**Modeling a prefab** (like a multi-block building system): represent each prefab
as a single node — `mass` = sum of its block masses, `maxLoad` = the prefab's
capacity, position = the prefab's center cell, edges = the prefabs it rests on
or touches. When that node collapses, the adapter removes all of the prefab's
underlying world blocks. The placement preview check uses `graph.copy()` +
solve on the copy; `copy()` preserves explicit edges, so non-grid topologies
simulate correctly.

**Known limitation:** the built-in `FilePersistenceAdapter` /
`ApiPersistenceAdapter` currently serialize nodes only and rebuild adjacency
from grid positions on load. Non-grid topologies should persist via the
consumer's own store (e.g. the prefab adapter's backend), or the line format
should be extended with `edge:` records (follow-up).

---

## Fun vs Realism

**Decision:** Keep the physics model honest. Make it fun by tuning material values, not by adding fake multipliers.

```
    ❌ BAD:  safetyFactor: 2.0  (hides the real physics)
    ✅ GOOD: stone.maxLoad: 120  (just make stone stronger)
```

The model should be "functionally correct" - what you see is what you get.

---

## Placement Safety Check (TODO for Adapters)

Adapters should implement a **pre-placement check** to prevent frustrating collapses:

```
    Player tries to place block:

    1. Adapter creates a COPY of the structure
    2. Simulates adding the new block
    3. Runs the solver on the copy
    4. If any block would collapse:
       → Show warning: "This placement would collapse X blocks!"
       → Let player confirm or cancel
    5. If safe:
       → Place the block normally
```

This keeps the physics honest but makes building dummy-proof.

**Where to implement:** `adapter-minecraft/` (not in core)

---

## Known Constraints (original MVP scope — since exceeded)

This was the original MVP scope. Some rows have since been exceeded in the 1.0.0
release (notably the kinetic-impact and crack/partial-damage models below).

| Constraint | Status | Notes |
|------------|--------|-------|
| 6-connectivity only | Accepted | Matches Minecraft model |
| No tension forces | Accepted | Most block games don't have this |
| No torque/moment | Accepted | Would complicate the model significantly |
| 1x1x1 blocks only | Accepted | Adapter maps shapes to cubes |
| No partial damage | Shipped in 1.0.0 | Crack model now tracks progressive damage |
| No impact damage | Shipped in 1.0.0 | Kinetic-impact energy model now lands hits |

---

## Threading & Graph Ownership

A `StructureGraph` and the engines that mutate it (`StressSolver`, `CascadeEngine`,
`StruxExplosionEngine`) are **not thread-safe**. `StressSolver` in particular keeps
per-pass scratch state (its reusable `denomCache`), so a single instance must never be
shared across threads. The ownership rule every adapter must follow:

1. **One owner thread.** A graph is created, read, and mutated only on its owning thread
   — the host's main/world thread. All physics (place/break/blast/cascade/solve) runs
   there.
2. **Background threads see only snapshots.** Persistence and any other background work
   never touch a live graph. The owner thread takes an immutable snapshot
   (`StructureConverter.toData`), hands that to the background thread for file I/O, and a
   loaded graph is **published back only on the owner thread** (`world.execute` /
   `runTask`), never installed into the live store from the I/O thread.
3. **Guard the not-yet-loaded window.** A world whose initial load is still in flight is
   marked load-pending; a background autosave/shutdown-save skips it, so it can never
   overwrite good disk data with an empty or partial graph.
4. **Off-thread readers read a cached report, never the graph.** Read-only consumers that
   may run on another thread (PlaceholderAPI expansions, dashboards) never solve or walk
   the live graph; they return the last cached report, refreshed on the owner thread.

The Minecraft adapter is the reference: `StructurePersistenceCoordinator` deserializes
off-thread but publishes on the main thread and guards saves with `initialLoadPending`;
`StruxPlaceholders` refuses to solve when `!Bukkit.isPrimaryThread()`. Other adapters
must conform rather than re-deriving a different rule.

---

## Tuning Guidelines (For Adapters)

When defining material values, consider:

```
    Player expectation: "I should be able to build a 10-block tall dirt hut"

    Math: height = maxLoad / mass

    For 10-block dirt tower: maxLoad = 10 × mass
    If dirt.mass = 1.0, then dirt.maxLoad should be ~10-15 (with margin)
```

**Suggested tuning process:**
1. Define what players expect to build (tower heights, cantilever lengths)
2. Calculate required maxLoad values
3. Playtest and adjust

---

## Performance Considerations

**Current approach:** HashMap-based adjacency list (simple, zero dependencies)

**Why not a graph database (Neo4j, etc.)?**
- Overkill for 50-500 blocks
- Network/embedded overhead
- Can't easily embed in game plugins

**Why not a graph library (JGraphT, Guava)?**
- Extra dependency
- We'd wrap it anyway
- Current approach is simple enough

**The bottleneck is NOT the data structure** - it's the solver algorithm. HashMap lookups are ~50ns, BFS is O(V+E) regardless of storage.

**If we need to optimize later:**
```java
// 1. Pack coordinates into long (no object allocation)
long packPos(int x, int y, int z) {
    return ((long)x << 40) | ((long)(y & 0xFFFFF) << 20) | (z & 0xFFFFF);
}

// 2. Use fastutil primitive maps (less boxing)
Long2ObjectOpenHashMap<BlockNode> nodes;

// 3. Chunk-based partitioning for very large structures
Map<ChunkPos, Chunk> chunks;  // each chunk = 16x16x16
```

**Decision:** Keep HashMap for MVP. Profile before optimizing.

---

## Future Ideas (Not MVP)

- [ ] Placement preview check in adapters
- [ ] Visual stress indicators (particles, block tints)
- [ ] Cascade delay for dramatic effect
- [ ] Configurable cascade limits per server
- [ ] "Repair" mechanic for high-stress blocks
- [ ] Distance-from-support multiplier for better cantilever physics
- [ ] Primitive-packed positions for less GC pressure
- [ ] Chunk-based partitioning for 10,000+ block structures (partition by connected
      component, not by chunk)

---

## Scaling beyond one server

Horizontal scaling (embedded + durable, or a sharded service), running on a default
survival world without changing the world, and the component-granularity
eviction/sharding rule are all *additive* — a new adapter against an unchanged core,
because the core already
owns no state, is deterministic, and proves split-solve-merge via `ParallelCascadeDriver`.
