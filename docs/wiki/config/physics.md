# Physics Settings <img class="title-icon" src="../assets/img/system/physics-stress.png" alt="">

<div class="page-aside left"><img class="page-mascot" src="../assets/img/mascot/tinkering.png" alt="The Strux Mason"></div>












Control how buildings collapse. These keys live at the **top level** of
`config.yml` (no `physics:` wrapper) plus the nested `cascade`, `impact`,
`fire`, `entity-weight`, and `materials` sections. See the
[full key reference](index.md) for the at-a-glance table.

---

## Moment and beams

Stress has two parts: vertical (weight straight down) and **moment** (sideways
torque, like holding a bucket at arm's length). A cantilever sticking out
sideways loads its anchor with moment.

```yaml
# Scales how much moment stress matters. 1.0 = realistic.
moment-multiplier: 1.0

# How much moment stress is removed for BEAMS supported on both ends.
# A beam shares its load across two supports, so it earns a discount.
# 1.0 = beams feel no moment at all (default); 0.0 = treated like a cantilever.
beam-moment-reduction: 1.0
```

Raise `moment-multiplier` to make long overhangs break sooner; lower it for more
forgiving cantilevers. If towers on beams feel "too easy," drop
`beam-moment-reduction` toward `0.8` so beams carry some moment again.

---

## Big collapses and the cap

`max-cascade-steps` is a **per-tick** budget, not a hard limit on the whole
collapse. It stops one block break from freezing the server.

```yaml
# Max blocks collapsed PER TICK in one chain reaction.
max-cascade-steps: 50
```

If a collapse is bigger than the cap, the extra blocks do not just hang in the
air. The rest finishes over the next ticks, doing at most `max-cascade-steps`
blocks each tick, until the structure is fully settled. So a giant tower still
comes all the way down — it just takes a moment.

A safety bound stops a pathological build from collapsing forever:

```yaml
cascade:
  # How many extra ticks one huge collapse may keep finishing for.
  max-resume-ticks: 200
```

If that bound is ever hit you get a WARNING in the log. The default covers very
large structures comfortably. Raise `max-cascade-steps` for snappier, all-at-once
collapses; lower it if a big collapse stutters a slow server.

There is also a wall-clock seatbelt. The step cap counts falling blocks, but on a
very large structure the *math between* the falls (working out what is floating
and what is overloaded) can cost more than a tick by itself. The time budget
bounds that too:

```yaml
cascade:
  # Max milliseconds ONE settle pass may spend on the main thread.
  settle-budget-ms: 30
```

When a settle pass runs out of time it pauses and finishes over the next ticks —
exactly like the step cap, just measured in milliseconds instead of blocks. The
server never freezes; a monster collapse simply lands over a few more ticks.
Set `0` to turn the budget off (not recommended on big maps).

!!! note "Why big terrain stays cheap"
    Before solving, the engine works out the region a disturbance can actually
    affect, and buried terrain acts as a wall for that search: an undamaged,
    buried terrain column that is capped by quiet surface and leaned on by
    nothing carries only its own weight straight down, so the region keeps it
    as a support but never spreads *through* it. A tower falling on a big field
    re-solves the tower and its footprint — not the field. Damaged or
    heat-softened columns (craters, fires) drop that shortcut and re-solve in
    full, so destruction always stays exact.

!!! note "Explosions ignore the cap"
    The blast engine does **not** use `max-cascade-steps` — it is a separate
    collapse path. An explosion settles the
    whole affected structure to completion (it is never left half-collapsed). A
    huge blast is instead paced by `blast.max-scan-per-tick`, which spreads both its
    radius check and its collapse across ticks (see [Explosions](#explosions)).

---

## Materials

Override the built-in physical properties of any block. Only the axes you set
change; the rest keep their defaults. Use the Minecraft material name. The
special key `default` tunes any block with no explicit entry.

```yaml
materials:
  STONE:
    mass: 3.0              # how heavy (pushes down on what's below)
    max-load: 120.0        # how much load it carries before failing
    blast-resistance: 1.0  # >1 shrugs off explosions, <1 is fragile
    fire-resistance: 1.0   # higher = slower to weaken in fire
  OAK_PLANKS:
    max-load: 25.0         # only override max-load; mass etc. stay default
  default:
    mass: 2.0
    max-load: 30.0
```

The bundled `config.yml` ships this section **commented out**, so the built-in
material table applies until you uncomment it. Unknown block names are skipped
with a console warning, so a typo never crashes load.

| Axis | Meaning |
|------|---------|
| `mass` | How heavy the block is (load on whatever is beneath). |
| `max-load` | How much load it carries before it fails. |
| `blast-resistance` | `>1` shrugs off explosions; `<1` is fragile. |
| `fire-resistance` | Higher = slower to weaken under fire. |

---

## Entity weight

Players and mobs add load to **already-weak** blocks — standing on them is a
continuous load, landing on them from height is a kinetic spike. Healthy blocks
are never checked, so normal movement is safe.

```yaml
entity-weight:
  enabled: true

  # How often to scan for entities on stressed blocks (ticks).
  scan-interval-ticks: 10

  # Only blocks at/above EITHER threshold are checked.
  stress-threshold: 0.7   # 70% of capacity in use
  damage-threshold: 0.5   # 50% damaged

  standing:
    enabled: true
    # Mass per entity type. `default` covers anything unlisted.
    mass:
      player: 2.0
      zombie: 2.0
      skeleton: 1.5
      creeper: 1.8
      spider: 1.2
      horse: 4.0
      iron_golem: 8.0
      warden: 10.0
      default: 2.0

  fall-impact:
    enabled: true
    energy-scale: 1.0       # multiplier on landing energy
    min-fall-distance: 2.0  # falls shorter than this (blocks) are ignored
```

Entity type names follow Bukkit's `EntityType` enum. Unknown names under
`standing.mass` are skipped with a console warning. Raising a mass makes that
creature more dangerous to weak floors; raising `fall-impact.energy-scale` makes
drops onto cracked floors more lethal.

---

## Projectile impacts

Arrows, tridents, catapult stones, and ram strikes damage structures by their
**kinetic energy** (E = ½·mass·speed²), not a fixed per-hit number. A light, slow
hit cracks the surface; ten accumulate to break it. A heavy, fast hit punches
through and undermines what's behind.

```yaml
impact:
  enabled: true

  # Multiplies every hit's energy. Raise for quicker sieges, lower for grindier.
  energy-scale: 1.0

  # Energy a blast-resistance-1 block absorbs before it's bored through.
  # Higher = tougher walls, shallower penetration.
  penetration-cost: 4.0

  # Fraction of absorbed-energy turned into persistent crack damage.
  # 1.0 = a block that absorbs its full toughness is destroyed.
  # Lower = more forgiving (more hits to break a wall).
  damage-scale: 1.0

  # Hard cap on blocks one impact can bore through, even with huge energy.
  max-penetration: 6

  # Safety seatbelt: most ms per tick spent settling queued hits.
  tick-budget-ms: 10.0
```

A hit does not resolve the moment it lands. Strux queues hits and drains a few
each tick, never spending more than `tick-budget-ms` per tick on them. So a whole
volley against a giant castle can never freeze the server — the hits resolve over
the next tick or two, in the order they landed. If a hit's target block is
already gone when its turn comes (a teammate mined it, or an earlier collapse
took it), strux quietly drops that hit.

Raise `tick-budget-ms` for snappier walls; lower it if a busy siege stutters.

---

## Explosions

TNT, creepers, and fireballs crater tracked structures and undermine what they
hold up. Like projectile hits, an explosion does **not** resolve the moment it
goes off.

```yaml
blast:
  # Safety seatbelt: most ms per tick spent settling queued explosions.
  tick-budget-ms: 10.0
  # How much work one big explosion does per tick: blast-radius positions it
  # checks AND blocks its collapse drops, sharing the same per-tick budget.
  max-scan-per-tick: 4096
  # How many crater blocks turn to air per tick (the crater forms gradually).
  max-crater-removals-per-tick: 64
  # Play the break sound/particle for 1 in N removed crater blocks.
  crater-effect-sample-rate: 8
  # Use FastAsyncWorldEdit to bulk-write the crater's air when present.
  fawe-acceleration: true
```

When an explosion fires, strux only does the cheap part on the spot (claims its
tracked blocks so vanilla won't crater them) and queues the blast. A processor
then settles queued blasts a few each tick, never spending more than
`tick-budget-ms` per tick on them. So several explosions in one tick — a TNT
chain, a cannon volley — can never freeze the server: they settle over the next
tick or two, in the order they fired. If a structure is already gone by the time
a queued blast's turn comes, strux quietly drops it.

One **very large single explosion** used to be the exception. It has two heavy
parts: first it checks every block inside the blast radius (a cost that grows with
the cube of the radius), then it drops everything the blast left hanging (a cost
that grows with how much collapses). Either part alone could cost more than the
whole budget in one tick. Now strux spreads **both** across ticks. `max-scan-per-tick`
is a shared per-tick budget of *work steps* — one step is one position checked OR one
block dropped — so a giant blast checks a chunk of its radius, then drops a chunk of
its collapse, a bit each tick (still under `tick-budget-ms`), until it is done. Only
one blast is in flight at a time; the next waits its turn (still strict firing order).

The crater and the collapse are exactly the same whether the blast finished in one
tick or twenty — slicing it only moves *when* the blocks change, never *which* ones.

**Carving the crater is streamed too.** Once a blast's scan is done, strux still has
to turn every destroyed block to air — and doing thousands in one tick (protection
check, block-log, sound, set-to-air, each ×2000) was the last freeze. So the crater
now forms a few blocks per tick: at most `max-crater-removals-per-tick` (default 64)
blocks per tick, still under `tick-budget-ms`. A 2000-block crater spreads over ~30
ticks instead of hitching once. To keep that gradual carve cheap, the per-block break
sound and particle are *sampled* — `crater-effect-sample-rate` plays them for 1 in N
removed blocks (default 8), and the single aggregate "cascade complete" boom still
fires at the end. When a whole chunk is unprotected strux checks that once for the
chunk rather than once per block. A `fawe-acceleration` flag (default on) reserves the
hook for handing those air-block writes to **FastAsyncWorldEdit** in one bulk edit when
it is installed — strux would still do the protection, logging, effects and debris
itself; FAWE would just do the writes. That writer ships in a later update (it needs a
live FAWE server to test), so today strux always uses its own streamed per-tick writes
and only notes in the log when FAWE is detected.

Raise `tick-budget-ms` for snappier explosions, or `max-scan-per-tick` so big
blasts resolve in fewer ticks; lower either if a busy siege stutters. Raise
`max-crater-removals-per-tick` for faster-forming craters, lower it if a giant blast
still stutters.

---

## Fire

Sustained fire weakens a structure over time — the slow-burn counterpart to
explosions and projectiles. A flammable block on fire chars fast; any block next
to fire/lava heats up slowly (radiant), so a long enough fire can even cook a
metal frame. Each block's rate divides by its fire resistance, so material choice
matters. Only fires near war-zone structures are tracked, and water/rain puts
them out, so defenders have counterplay.

```yaml
fire:
  enabled: true

  # Capacity a fire-resistance-1 block ON FIRE loses per tick.
  # A material's real rate is this ÷ its fire resistance.
  damage-per-tick: 0.0006

  # Radiant heat as a fraction of the direct-burn rate: how much a block merely
  # NEXT TO fire/lava weakens. This is what lets fire affect stone and metal.
  radiant-factor: 0.25

  # How often the scorch scan runs (20 = once a second).
  scan-interval-ticks: 20

  # A flame with nothing flammable beside it gutters out after this many ticks.
  # Touching a flammable block resets the clock; lava is exempt. 0 = never.
  barren-burnout-ticks: 600

  # Safety seatbelt: most ms per tick one scorch pass may spend.
  tick-budget-ms: 10.0
```

If a burning pass runs long (lots of fires, a big settle), it stops where it is
and the fires it didn't reach wait for the next pass — a slightly slower burn,
never a frozen tick. Raise `damage-per-tick` for faster burn-downs; raise
`radiant-factor` to make non-flammable walls more vulnerable to a sustained fire.
`barren-burnout-ticks` is what stops an eternal flame on bare stone from burning
forever while still letting a fuelled siege fire cook a wall down.

---

## Learn more

- [Full key reference](index.md)
- [Effects Settings](effects.md) — particles, sounds, cracks, screen shake
- [Weather Settings](weather.md) — rain, thunder, snow
- [Admin Guide](../admin/index.md) — protecting areas, war zones
</content>
