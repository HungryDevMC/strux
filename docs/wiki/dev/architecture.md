# Architecture

<div class="page-aside"><img class="page-mascot" src="../assets/img/mascot/reading-blueprint.png" alt="The Strux Mason"></div>












A bird's-eye view of how Strux is put together. You don't need this to *use*
Strux — for that, see the [Developer Guide](index.md). This page is for the
curious: how the pieces fit, and why.

## One engine, many games

Strux is split into a **pure physics engine** and thin **adapters** that connect
it to a specific game.

```
strux/
├── core/                # The physics engine. Pure Java, no game code.
└── adapter-minecraft/   # Paper/Spigot plugin
```

The golden rule: **`core` knows nothing about any game.** It reasons about
*nodes* (things with weight) and *edges* (things touching), never "blocks" or
"Minecraft". A node can be a block, a prefab, a truss joint — anything with mass
that rests on something else.

Adapters depend on the engine; the engine never depends on an adapter. This is
what lets the same physics run a Minecraft plugin or a headless simulation
without change.

## How an adapter connects

An adapter's whole job is translation — turn the game's blocks into the engine's
nodes, hand them to the engine, and render whatever the engine says fell.

```
[Game block]  →  [Adapter]  →  [Node + material (weight, strength)]  →  [Engine]
                     ↑                                                      │
                     └──────────  "these blocks fell / cracked"  ←─────────┘
```

So the adapter answers two questions for the engine — *"what blocks are here, and
how heavy/strong is each one?"* — and the engine answers one back: *"given
that, what can no longer hold?"*

## What happens when you break a block

```
1. You break a block.
2. The adapter notices and tells the engine.
3. The engine re-checks just the part of the structure that could be affected.
4. Anything that can no longer hold its load collapses — and that can cascade.
5. The adapter plays the collapse: blocks fall, dust, sound, cracks.
```

Explosions and projectile impacts follow the same shape — the adapter builds a
description of the hit, the engine works out the damage and collapse, the adapter
renders it.

For the actual physics in those steps — stress, the lever effect, how failure
spreads — see [How the Physics Works](physics-model.md).

## A note on threading

The engine is **single-threaded by contract**: one structure is driven from one
thread (in Minecraft, the main server thread). It holds no long-lived background
state of its own, which is also what makes the "Enterprise" scaling direction
(moving collapse work off the game server) possible later — see the roadmap.

## Why it stays fast on big worlds

Two ideas keep Strux cheap no matter how large the world is:

- **Scoped work.** A change only re-checks the part of the structure it can
  actually affect, and stops at solid terrain. Breaking a fence on one side of a
  huge world never re-checks the other side.
- **Capped per tick.** A giant collapse is spread across several ticks instead of
  doing it all in one — so the server stays responsive. You can watch the real
  cost live with `/strux perf`.

That's the whole story at a high level. To build against Strux, head to the
[Developer Guide](index.md); to understand the physics itself, read
[How the Physics Works](physics-model.md).
