# Blast Design (the correct + fun direction)

This is the implementation design for explosions in strux. It supersedes the
flow proposed in [`blast-context-api-analysis.md`](./blast-context-api-analysis.md),
which had two problems:

1. **Category error** — it added blast energy into the same `stress` field that
   holds *gravity load*, and compared it to `maxLoad` (a gravity-load capacity).
   Blast overpressure and structural load are different quantities; the
   epicenter should shatter regardless of how loaded it is.
2. **Solver conflict** — `StressSolver.solve()` begins with `resetStress()`, so
   any externally-added stress is wiped on the next solve. The proposed cascade
   loop calls `solveAll()`, which would erase the blast damage it just applied.

## Core idea: two separate quantities

| quantity | meaning | lifetime |
|---|---|---|
| **load-stress** (`verticalStress + momentStress`) | gravity load a node carries | transient — recomputed from topology every solve (**unchanged**) |
| **damage** | persistent structural weakening | survives re-solves and accumulates across hits (**new**) |

`damage ∈ [0,1]` lowers a node's *effective* capacity:
`effectiveMaxLoad = maxLoad × (1 − damage)` (a later reinforcement feature added
a `× reinforcement` factor, so the full formula is
`maxLoad × reinforcement × (1 − damage)`). A weakened block then fails under its
**own existing gravity load** through the normal solver/cascade — no special
casing. Repeated explosions stack damage (multi-hit raiding).

## Two-phase explosion

```
Explosion → StruxExplosionEngine.process(graph, ctx)
   Phase 1  direct blast:  for each node in the radius cube
              intensity = power · falloff(dist) · occlusion / blastResistance
              intensity ≥ destructionThreshold → DESTROY (crater)
              else                             → addDamage (crack, persistent)
   Phase 2  gravity: scoped collapse of the blast-affected region —
              drop floaters, then fully-damaged blocks, then overloaded blocks,
              re-checking floaters after each (all in canonical order)
   → BlastResult { destroyed, collapsed, damaged, finalStress }
```

- **Phase 1** is the satisfying, learnable crater, and it correctly destroys the
  epicenter regardless of load. It iterates only the radius *sphere* — the scan
  walks just the cells inside the blast ball, not the whole bounding cube — and
  `occlusion` means cover shields the blast; `blastResistance` (per material) lets
  players build bunkers.
- **Occlusion is exact line-of-sight.** When `occlusion = RAYCAST`, the engine
  walks the straight line from the blast centre to each target voxel-by-voxel
  (an integer 3D DDA / Amanatides–Woo traversal) and counts the solid blocks the
  line *actually crosses*. Each crossed block shields the target by
  `occlusionAttenuation`. This is true cover: a wall directly between the blast and
  a block protects it; a block off to the side does not. (Earlier versions sampled
  a few points along the line instead, which could miss or double-count a block —
  the DDA visits each crossed voxel exactly once, so cover is now physically
  honest. Switching to it shifted some borderline blocks between "cratered",
  "cracked" and "untouched".) On an exact 45° diagonal the ray passes through a
  voxel *corner*; the traversal steps every tied axis at once (a diagonal move
  through the corner) rather than grazing the corner-touching voxels to one side.
  Without that, a diagonal line of sight counted ~2–3× too much cover and was
  left/right asymmetric — two mirror-image structures took different damage from
  the same blast. Diagonals now attenuate at the same rate as axis directions
  through the same material thickness, and cover is mirror-symmetric.
- **Phase 2** is the emergent progressive collapse from lost supports. The blast
  engine runs its own scoped collapse loops (floating → fully-damaged →
  overloaded) bounded to the blast-affected region, sharing the `StressSolver`
  with the rest of the core. Unlike a break/impact cascade it is **uncapped** —
  it does not consult `maxCascadeSteps` — because the affected region is already
  bounded by the blast radius and its dependents.
  - **Boundary guard (overload phase).** The overloaded-block loop shares the
    cascade's defense against reading stale stress across the scope edge. The blast
    scope is dependent-subgraph + support columns, not a full closure, so an edge
    block still has neighbours outside it — and `StressSolver` reads an out-of-scope
    neighbour's *persistent* `verticalStress` field, whatever an earlier solve left
    there. So the loop never drops an overloaded block that leans on a block outside
    the scope: it widens the scope around that block and re-solves first (ring width
    doubling per consecutive round), so the deciding stress is always recomputed from
    the real post-blast graph. A blast carves the same crater whether or not the
    structure was stress-solved a tick earlier. This mirrors `CascadeEngine`'s
    per-batch boundary guard.

The `destroyed` / `collapsed` / `damaged` split lets the adapter render each
differently (instant break vs falling block vs crack particles). The three sets
are **pairwise disjoint**: a block is shattered, OR it fell, OR it survived
weakened — never two at once. A block the shockwave cracks and the cascade then
drops is reported only in `collapsed` (it is gone, not a weakened survivor); the
engine prunes it from `damaged` before returning. The crack damage was still
applied to the node, so a block that cracks and *stays standing* keeps that
damage for the next blast.

## Shape in code

```java
// core/model/Node.java        — damage is NOT touched by resetStress()
double damage;                  // 0..1
void   addDamage(double d);
double effectiveMaxLoad();      // maxLoad × reinforcement × (1 − damage); ground unaffected
boolean isDestroyed();          // damage >= 1
// stressPercent() now divides by effectiveMaxLoad() (identical when damage==0)

// core/model/MaterialSpec.java — blastResistance is the third field; a later
// pass added fireResistance as a fourth. Both shorter ctors default to 1.0.
record MaterialSpec(double mass, double maxLoad, double blastResistance, double fireResistance) {
    MaterialSpec(double mass, double maxLoad) { this(mass, maxLoad, 1.0, 1.0); }
    MaterialSpec(double mass, double maxLoad, double blastResistance) { this(mass, maxLoad, blastResistance, 1.0); }
}

// core/solver/CascadeEngine.java — settle() was extracted so break and impact
// share one collapse loop. (The blast engine does NOT call it; it inlines its
// own scoped, uncapped collapse — see Phase 2 above.)
List<CollapsedNode> settle(StructureGraph g, Set<NodePos> scope, SolverCallback cb);
CascadeResult        cascade(StructureGraph g, NodePos trigger, SolverCallback cb); // removeBlock + settle

// core/blast/
record BlastContext(NodePos center, double power, BlastShape shape,
                    BlastFalloff falloff, BlastOcclusion occlusion);  // + builder
enum  BlastShape    { SPHERE }
enum  BlastFalloff  { LINEAR, QUADRATIC, FLAT }
enum  BlastOcclusion{ NONE, RAYCAST }
record BlastResult(List<NodePos> destroyed, List<NodePos> collapsed,
                   Map<NodePos,Double> damaged, Map<NodePos,Double> finalStress);
interface BlastCallback {            // optional hooks; NONE no-op
    default boolean onBlast(BlastContext c){ return true; }   // pre: cancel/modify
    default void onDirectDestroy(NodePos p){}
    default void onDamaged(NodePos p, double dmg){}
    default void onCollapse(NodePos p){}
    default void onComplete(BlastResult r){}
}
class StruxExplosionEngine {          // pure physics, lives in core
    BlastResult process(StructureGraph g, BlastContext ctx, BlastCallback cb);
}
```

## Tuning knobs (PhysicsConfig)

| knob | default | effect |
|---|---|---|
| `blastRadiusPerPower` | 1.5 | crater size per unit power |
| `destructionThreshold` | 2.0 | intensity that shatters vs cracks |
| `damageScale` | 0.5 | how fast sub-lethal hits accumulate |
| `occlusionAttenuation` | 0.25 | how much each intervening block shields |
| `MaterialSpec.blastResistance` | 1.0 | bunkers (high) vs glass cannons (low) |

## Adapter glue (the only game-specific part)

`adapter-minecraft` `ExplosionListener`: `EntityExplodeEvent → BlastContext →
engine.process → apply BlastResult` (clear vanilla block list, air out
`destroyed`, spawn falling blocks for `collapsed`, crack particles for
`damaged`). `MaterialRegistry` assigns `blastResistance` alongside mass/maxLoad.

## Build order (each step independently green)

1. `Node.damage` + `effectiveMaxLoad` + `MaterialSpec.blastResistance` + tests.
2. `CascadeEngine.settle` extraction (pure refactor).
3. `core/blast/` package + `StruxExplosionEngine` + tests (Bukkit-free).
4. `adapter-minecraft` `ExplosionListener` + wiring + material resistances.
