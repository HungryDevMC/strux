# Engine SDK

<div class="page-aside"><img class="page-mascot" src="../assets/img/mascot/tinkering.png" alt="The Strux Mason"></div>












Embed the Strux physics engine in your own projects. The `core/` module is a
standalone physics library — package `dev.gesp.structural.*`, pure Java, zero
runtime dependencies, no game types. You can embed it in custom Minecraft
plugins, other voxel games, simulation tools, or level editors.

## The facade: `StruxEngine`

`dev.gesp.structural.engine.StruxEngine` bundles the graph, solver, cascade
engine, blast engine and grader behind one fluent object. Most hosts only ever
touch this class.

```java
StruxEngine engine = new StruxEngine();            // or new StruxEngine(physicsConfig)

// 1. Describe the structure (host maps its own blocks → NodePos + MaterialSpec)
engine.addGround(0, 0, 0);                          // foundation/anchor
for (int y = 1; y <= 5; y++) {
    engine.addBlock(0, y, 0, new MaterialSpec(3.0, 100.0)); // mass, maxLoad
}
engine.solve();                                     // compute stress

// 2. Query / grade
StructureReport report = engine.assess();           // solves, then S/A/B/C/F + stats
double stress = engine.stressAt(new NodePos(0, 1, 0)); // fraction, 1.0 = at capacity

// 3. Simulate
CascadeResult fell = engine.breakBlock(0, 1, 0);    // knock out the base → cascade
engine.detonate(BlastContext.builder()
    .center(new NodePos(0, 3, 0)).power(6.0).build());

// 4. Counterplay
engine.reinforce(new NodePos(0, 1, 0), 3.0);        // raise capacity 3×
```

`breakBlock` and `detonate` each have an overload taking a callback
(`SolverCallback` / `BlastCallback`) so a host can drive animation, sound and
particles off the per-step events.

The engine is **single-threaded by contract**: drive one engine from one thread
(typically the host's tick thread).

### See it run headless

```bash
./gradlew :prototype:run
```

`:prototype` is a small headless siege simulation on the pure core — a fortress
felled by a strike, then an overloaded tower saved by reinforcement, rendered in
the terminal. It is the reference embedding: no game attached.

## Maven / Gradle dependency

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.gesp:strux-core:1.0.0")
}
```

## Core types

### NodePos

Immutable lattice coordinate. Only `y` is physical ("up"); `x`/`z` are identity.

```java
NodePos pos = new NodePos(x, y, z);
int x = pos.x(); int y = pos.y(); int z = pos.z();
```

### MaterialSpec

A record of physical properties. Two convenience constructors default the
trailing resistances to `1.0`.

```java
record MaterialSpec(double mass, double maxLoad,
                    double blastResistance, double fireResistance) {}

MaterialSpec stone  = new MaterialSpec(3.0, 100.0);        // resistances = 1.0
MaterialSpec bunker = new MaterialSpec(3.0, 100.0, 4.0);   // tougher under blast
MaterialSpec ground = MaterialSpec.GROUND;                 // mass 0, infinite maxLoad

double mass = stone.mass();
double maxLoad = stone.maxLoad();
boolean isGround = stone.isGround();
```

### Node

A `final class` (not an interface) with mutable physics state.

```java
Node node = engine.graph().getNode(pos);

double stress  = node.stressValue();      // verticalStress + momentStress (load units)
double percent = node.stressPercent();    // / effectiveMaxLoad; 1.0 = at capacity
double damage  = node.damage();           // 0..1, persistent across solves
double cap     = node.effectiveMaxLoad(); // maxLoad × reinforcement × (1 − damage)
```

### StructureReport

What `assess()` returns: a letter `StructureGrade` (S/A/B/C/F) plus the stats it
was derived from.

```java
StructureReport r = engine.assess();
StructureGrade grade = r.grade();
int peak = r.peakPercent();        // highest stress % (whole number)
int avg  = r.avgPercent();
int overloaded = r.overloadedCount();
```

## Tipping prediction: will the whole thing topple?

Stress and cascades answer "does a block lose its support?". Tipping answers a
different question: "does the whole structure fall over as one rigid body?" —
like a tall, narrow tower whose blocks are all fine but whose weight has leaned
out past its base.

This is real statics, the same rule a crane operator uses. The engine finds the
structure's **center of mass** (the average point all the weight pulls on) and
its **support polygon** (the footprint of where it touches the ground). If the
center of mass sits over that footprint, it stands. If it leans out past an
edge, gravity rotates the body about that edge — it tips.

The footprint is the real area each ground block bears on — its full unit cell,
not just its centre point. So a single 1×1 column is stable for a centre of mass
anywhere within that block (up to a half-block overhang), exactly like a real
brick balanced on a post; it only tips once the weight leans out past the block's
edge.

```java
TipResult tip = engine.wouldTip(0, 1, 0);   // any block in the structure
if (tip.tips()) {
    NodePos pivot = tip.pivotEdgeMidpoint(); // the edge it rotates about (y = 0)
    double dx = tip.dirX(), dz = tip.dirZ(); // unit direction it topples toward
}
```

`wouldTip` is a **pure read-only query**: it never solves, moves a block, or runs
a cascade — call it as often as you like. It computes the queried block's
connected component, then checks that component's center of mass against its
support polygon. An absent block, or a component with no above-ground mass, reads
as stable. For direct access (e.g. to inspect the hull yourself) use
`dev.gesp.structural.assess.TippingAnalyzer`:
`centerOfMass`, `supportPolygon`, and `tips`.

> The cascade/settle loop does **not** use tipping yet — this slice ships the
> prediction only. Integrating a topple into the collapse is a later slice.

## Reacting to a cascade

`breakBlock(pos, callback)` takes a `SolverCallback`:

```java
engine.breakBlock(0, 1, 0, new SolverCallback() {
    @Override public void onStressUpdated(Map<NodePos, Double> stressMap) { /* ... */ }

    @Override public void onCascadeStep(CollapsedNode node, int step, CollapseReason reason) {
        System.out.println("Collapsed " + node.pos() + " (" + reason + ")");
    }

    @Override public void onCascadeComplete(List<CollapsedNode> all) {
        System.out.println("Cascade complete: " + all.size() + " nodes");
    }

    // onStressUpdated is only built when this returns true — default false.
    @Override public boolean wantsStressUpdates() { return false; }
});
```

`SolverCallback.NONE` is a no-op for when you only want the returned
`CascadeResult`.

### Capped, resumable cascades

A break/impact cascade collapses at most `PhysicsConfig.getMaxCascadeSteps()`
(default **50**) blocks per call, then stops. If work remained,
`CascadeResult.truncated()` is `true` and the graph is left mid-collapse — the
host should call `CascadeEngine.settleResult(...)` again on a later tick until a
pass collapses nothing and reports `!truncated()`. Pure floating collapse is
exempt from the cap. (The blast engine runs its own uncapped scoped path; see
below.)

## Explosions

```java
StruxExplosionEngine blast = new StruxExplosionEngine(config); // or engine.detonate(...)

BlastContext ctx = BlastContext.builder()
    .center(new NodePos(0, 3, 0))
    .power(6.0)
    .falloff(BlastFalloff.QUADRATIC)   // default
    .occlusion(BlastOcclusion.RAYCAST) // default
    .build();

BlastResult result = blast.process(graph, ctx);
result.destroyed();  // shattered directly (the crater)
result.collapsed();  // fell afterwards in the gravity cascade
result.damaged();    // survived but took persistent damage (pos → 0..1)
```

The three result sets are **pairwise disjoint**. Phase 1 craters and cracks
(persistent damage); phases 2–4 collapse floating, fully-damaged, then
overloaded blocks — all scoped to the blast-affected region and **uncapped**
(separate from `maxCascadeSteps`). A block that cracks but stays standing keeps
its damage for the next blast.

## Kinetic impact

For projectiles and rams, `ImpactEngine` spends a kinetic-energy budget along
the projectile's path. The caller computes the energy (`½·m·v²`) where real
velocity exists; the engine is unit-agnostic and only spends it.

```java
ImpactContext ctx = ImpactContext.builder()
    .origin(hitPos)
    .direction(vx, vy, vz)        // need not be normalised; omit for a point impact
    .energy(0.5 * mass * speed * speed)
    .build();

ImpactResult result = new ImpactEngine(config).process(graph, ctx);
result.penetrated(); // punched clean through (path order)
result.collapsed();  // fell in the secondary cascade
result.damaged();    // cracked but survived (pos → 0..1)
```

Impact's secondary settle goes through `CascadeEngine.settle`, so it **is**
capped/resumable like a break.

## PhysicsConfig

Plain getters/setters — **there is no builder**. Construct defaults and set what
you need.

```java
PhysicsConfig config = new PhysicsConfig();
config.setMaxCascadeSteps(100);     // default 50
config.setMomentMultiplier(1.0);
config.setDebrisImpactScale(0.5);   // default 0.0 (off); games opt in

StruxEngine engine = new StruxEngine(config);
```

Knobs cover moment (cantilever) strength, the cascade cap, blast falloff/
threshold/damage, kinetic-impact cost/penetration, fire degradation, debris
loading, crack-visual thresholds and rubble.

## Building non-grid structures

`addBlock` is the grid shortcut (auto-connects face-adjacent neighbours). For a
prefab, truss, or any custom topology, wire edges yourself:

```java
StructureGraph graph = engine.graph();
graph.addGroundNode(a);
graph.addNode(b, spec, false);
graph.connect(a, b);          // load travels only across edges you create
```

## Thread safety

- `StructureGraph` / `StruxEngine` are **not** thread-safe.
- All modifications must happen on one thread.
- Read-only queries can run concurrently only if the graph isn't being modified.

## Performance tips

1. **Batch building**: add nodes before solving.
2. **Scoped solves**: a local change only re-solves its structural closure;
   solve cost is bounded by structure size, not world size.
3. **Reuse callbacks**: avoid allocating a callback per event on hot paths.
4. **`wantsStressUpdates()`**: leave it `false` unless you read the stress map —
   it skips building that map.
