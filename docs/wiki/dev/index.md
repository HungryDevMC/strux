# Developer Guide

<div class="page-aside"><img class="page-mascot" src="../assets/img/mascot/reading-blueprint.png" alt="The Strux Mason"></div>












Strux is built to be built on. This page is the **contract**: what's public, what
to call, and how to integrate cleanly so your code keeps working as Strux evolves.

If you just want to start playing with it, jump to your path below — each one has
a runnable example in a few lines.

## Pick your integration path

| You want to… | Use | Start here |
|---|---|---|
| React to / drive physics on a **Paper server** | The plugin API | [API Reference](api.md) |
| Embed the physics in **your own project** (a plugin, another game, a tool) | The engine SDK | [Engine SDK](engine-sdk.md) |

Want to understand what the engine is actually doing first? Read
[How the Physics Works](physics-model.md).

## 60-second start (embedding the engine)

```java
StruxEngine engine = new StruxEngine();

engine.addGround(0, 0, 0);                                  // an anchor
for (int y = 1; y <= 5; y++) {
    engine.addBlock(0, y, 0, new MaterialSpec(3.0, 100.0)); // weight, strength
}
engine.solve();

StructureReport report = engine.assess();    // grade + stats (S/A/B/C/F)
CascadeResult fell = engine.breakBlock(0, 1, 0);   // knock out the base → watch it cascade
```

That's the whole loop: describe the structure, ask the engine what holds, knock
something out, render what falls. Everything else is detail on those four moves.

## The public contract

**Public, stable — build against these:**

- **Plugin API** — the accessors on `StructuralIntegrityPlugin` (e.g.
  `getStructureManager()`), and the `StructureManager`, `MaterialRegistry`,
  `CollapseGuard`, and `RecordingService` surfaces. See [API Reference](api.md).
- **Engine SDK** — `StruxEngine` and the value types you pass it: `NodePos`,
  `MaterialSpec`, `BlastContext`, `ImpactContext`, `PhysicsConfig`, and the
  results it hands back (`StructureReport`, `CascadeResult`, `BlastResult`).
- **Config keys** in `config.yml` (Minecraft) and the `PhysicsConfig` knobs.

**Internal — do not depend on these:**

Anything not listed above is an implementation detail and may change between
versions without notice — the internal solver/cascade staging, persistence and
caching machinery, the recording file format internals, and everything under a
`*.internal`-style package or marked package-private. If you find yourself
reaching for one of these, [tell us](#getting-help) — that's a gap in the public
API we'd rather fill.

### Versioning promise

Strux follows semantic versioning on the public contract above:

- **Patch** (`1.0.x`) — fixes, no API change.
- **Minor** (`1.x.0`) — additions only; your code keeps compiling.
- **Major** (`x.0.0`) — the only releases that may change or remove public API,
  always called out in the changelog.

Tune behaviour through **config**, not by reaching into internals — config is
part of the contract; internals are not.

## Best practices

1. **Go through the facade.** `StruxEngine` (embedding) or `StructureManager`
   (plugin) is the intended entry point. Reach past it only when the facade
   genuinely doesn't expose what you need.
2. **One structure, one thread.** The engine is single-threaded by contract.
   Drive each structure from one thread; only read it concurrently when nothing
   is modifying it.
3. **Let the engine scope the work.** Don't re-solve the whole world — a local
   change already re-checks only what it can affect. Feed it the change, not the
   universe.
4. **Map materials honestly.** An adapter's quality is mostly its
   weight/strength mapping. Real-feeling collapses come from real-feeling
   numbers, not safety fudge factors.
5. **Use callbacks for effects.** Drive sound/particles/animation off the
   per-step cascade callbacks rather than diffing the world afterward.
6. **Watch the cost.** `/strux perf` (plugin) shows the real solve time on your
   hardware — measure before you optimise.

## License

Strux is **free** to use, run, and modify on a **single server** (including a
commercial one) under the **Business Source License 1.1**. Running it across a
network / multiple servers / a horizontally-scaled deployment needs a paid
**Enterprise** license (coming later), which adds horizontal scaling and support.
The source is public to audit, and converts to the Apache License 2.0 on the
Change Date.

## Getting help

- 🐛 Found a bug or a missing API? [Open a GitHub issue](https://github.com/HungryDevMC/strux/issues).
- 📧 Commercial / embedding questions: info@gesp.tech.
