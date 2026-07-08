# Effects Settings <img class="title-icon" src="../assets/img/system/effects.png" alt="">

<div class="page-aside"><img class="page-mascot" src="../assets/img/mascot/celebrating.png" alt="The Strux Mason"></div>












Control what collapses look and sound like. All of these keys live under the
`effects:` section of `config.yml` (flat keys, not sub-sections), except the two
top-level telegraph switches noted below. See the [full key reference](index.md)
for the at-a-glance table.

---

## Wobble vs. cracks

Strux has two independent collapse telegraphs. They are separate switches — run
either, both, or neither.

```yaml
# WOBBLE telegraph (top-level key, NOT under effects):
# critical blocks visibly rock before they fail. Purely visual, no physics.
pre-collapse-shake: false

effects:
  # CRACK telegraph: blocks show crack textures under load/damage.
  cracks-enabled: true
```

Cracks also heal on screen the moment the danger is gone: rescue a sagging
block (for example, place a support pillar under it) and its crack texture is
wiped right away, instead of lingering for a couple of seconds.

The bundled config ships `pre-collapse-shake: false` (the crack telegraph carries
the warning, and the wobble overlay was distracting during raids). Note the code
fallback for a missing key is `true`, but the shipped config sets it `false`.

---

## Collapse timing

```yaml
effects:
  # Delay before blocks fall after a PLAYER break (ticks; 20 = 1s).
  collapse-delay-ticks: 25

  # Delay before blocks fall after an EXPLOSION (punchier when shorter).
  explosion-collapse-delay-ticks: 4

  # Blocks animated falling per tick. Higher = smoother but more CPU.
  max-collapses-per-tick: 25
```

`collapse-delay-ticks` is your escape window — break a support and the roof falls
this many ticks later. `max-collapses-per-tick` smooths the visual fall; it is
separate from the physics cap (`max-cascade-steps` on the
[Physics page](physics.md#big-collapses-and-the-cap)).

---

## Screen shake

```yaml
effects:
  screen-shake-enabled: true

  # Min collapsed blocks before the ground shakes (small collapses stay quiet).
  screen-shake-threshold: 8

  # How far the shake reaches (blocks).
  screen-shake-radius: 32.0
```

Raise the threshold so only huge collapses shake; lower it so even small ones do.
(The code fallback for the threshold is `15`; the bundled config ships `8`.)

---

## Dust

```yaml
effects:
  dust-clouds-enabled: true

  # Dust amount. 0.5 = half, 2.0 = double.
  dust-multiplier: 1.5

  # Big spreading wave after a building finishes falling (demolition-style).
  dust-wave-enabled: true
  dust-wave-max-radius: 20.0
```

Turn the wave off or shrink `dust-wave-max-radius` if a slow client lags during
big collapses. (Code fallbacks: `dust-multiplier` 1.0, `dust-wave-max-radius`
15.0 — the bundled config ships the louder 1.5 / 20.0.)

---

## Stress particles

Colored particles warn about loaded blocks (visible in `/engineer` mode and on
critical blocks). The overlay refreshes every `visual-update-ticks` ticks (a
top-level key — see the [Physics page](physics.md)).

```yaml
effects:
  stress-caution-threshold: 0.50   # yellow at 50% stress
  stress-danger-threshold: 0.80    # orange at 80%
  stress-critical-threshold: 0.95  # red + flames at 95%
  stress-particle-size: 0.7

  # Ramp the ambient stress sound the closer a block is to failing
  # (soft creak → groan → sharp crack).
  escalating-stress-audio: true
```

Lower the thresholds to get warnings earlier; raise them for fewer, later
warnings.

---

## Cracking warnings

These are the cracking **sounds** before a collapse (distinct from the crack
textures below).

```yaml
effects:
  cracking-warnings-enabled: true

  # Ticks between cracking sounds (lower = more frequent). Minimum 1 — a
  # configured 0 is treated as 1 (it used to crash the collapse system).
  cracking-warning-interval: 3

  # Cap on simultaneous cracking sounds (stops spam in mass collapses).
  max-cracking-warnings-per-tick: 6
```

(Code fallback for the interval is `5`; the bundled config ships the more
frequent `3`.)

---

## Impact feedback

The little "bite" each arrow leaves. When a projectile hits a wall it adds a
chip of damage. This shows that chip: a tiny puff of the block's particles and a
soft hit tick, right where the arrow landed.

So ten arrows look like ten little bites — not one wall quietly soaking them all
up. It is **only** a visual; the damage is exactly the same whether this is on or
off.

```yaml
effects:
  impact-feedback: true
```

Set it to `false` for no per-hit puff or tick. The wall still takes the same
damage and still collapses the same way.

---

## Cracks

The visual crack-texture overlay on stressed/damaged blocks.

```yaml
effects:
  # Master switch for ALL crack textures (from damage AND stress).
  cracks-enabled: true

  # Min damage fraction before damage cracks show.
  min-visible-damage: 0.15

  # How far crack textures render (blocks).
  damage-view-distance: 32.0

  # Also crack blocks from structural STRESS, not just blast/impact damage.
  # A heavily-loaded wall cracks under its own load, and as it starts to fail
  # the cracks spread toward the break on their own.
  stress-cracks-enabled: true

  # Distress (worse of stress-fraction and damage) at which each stage appears.
  crack-hairline-threshold: 0.60   # faint cracks
  crack-cracked-threshold: 0.78    # clear cracks
  crack-crumbling-threshold: 0.90  # heavy cracks — last warning before collapse
```

Lower the thresholds to crack blocks sooner (more dramatic, but noisier on normal
builds whose bases are always somewhat loaded).

Crack visuals are near-free on a static world. The server remembers which blocks
are cracked and only re-checks them while nothing in that world changes — it does
not re-scan every tracked block each second. The full re-check happens only when
the world actually changes (a block is placed, broken, blasted, or collapses).
There is nothing to configure for this; it is automatic.

---

## Explosions

```yaml
effects:
  # How far explosion damage messages reach (blocks).
  explosion-notify-radius: 64.0

  # Max falling debris pieces per explosion. Lower for slow clients.
  max-debris-per-explosion: 200
```

---

## Actionbar warning

```yaml
effects:
  # Stress at which placing a block flashes a CRITICAL STRESS warning
  # above the hotbar.
  critical-stress-warning-threshold: 0.90
```

---

## Collapse notifications (chat)

Two friendly chat touches when buildings come down.

```yaml
effects:
  # Tell the whole server when a big collapse happens.
  big-collapse-broadcast-enabled: true

  # Only collapses BIGGER than this many blocks are announced (small ones
  # stay quiet so chat isn't spammed).
  big-collapse-broadcast-threshold: 15

  # The first time a player collapses their own build, send a one-time tip
  # suggesting /engineer. Fires once per player, ever.
  first-collapse-hint-enabled: true
```

When a collapse is over the threshold, everyone sees a line like:

```
💥 Steve's structure collapsed! (23 blocks)
```

The name is the player whose break or placement triggered the collapse, and the
number is how many blocks fell.

For a giant collapse that finishes over several ticks, the announced count is the
**first-tick** size — the blocks that came down right away in that one action —
not the eventual total. (The rest settle quietly over the next ticks.)

The first-collapse tip is private (only that player sees it) and the "already
seen it" mark is saved on the player, so it survives restarts and never repeats.

## Live stress summary

An always-on readout above the hotbar while a player looks at (or stands on) a
tracked structure. It looks like:

```
World: ██░░░░ 34% avg | Peak: 78%
```

The bar and the `avg` number are the average stress across the world's tracked
blocks; `Peak` is the single most-stressed block.

> **Note:** the numbers are **world-wide**, not for the one structure you are
> aiming at — strux grades a whole world at once. The readout is labelled
> `World:` so it never lies about what it measures.

It is **off by default** so existing servers are not surprised by a new HUD
element. If a `⚠ CRITICAL STRESS` place-warning fires the same tick, the warning
wins and the summary steps aside (both share the action bar through one arbiter,
so they never flicker against each other).

```yaml
effects:
  # Show the live stress summary in the action bar? Off by default.
  stress-summary-enabled: false
  # How often it refreshes, in ticks (20 = 1 second; 10 = twice a second).
  stress-summary-interval-ticks: 10
## Near-miss notification

Tells you "Close call — that block was barely holding." when a block you knocked
loose collapses while it was *just* over its limit. It only fires for an overloaded
collapse that was barely holding — not for a block that was hopelessly overloaded, and
not for a block that simply had nothing under it (a floating collapse). You get at most
one message per break or place, however many blocks fall.

```yaml
effects:
  near-miss-notification-enabled: true  # show the "Close call" message
  # How close to failing a block must have been to count, as a fraction of its
  # limit. 1.0 = exactly at the limit. A collapse just above this counts; one far
  # above does not (that block was never really holding).
  near-miss-threshold: 0.98
```

---

## Rubble

Optional physical debris left behind by collapses. Off by default.

```yaml
effects:
  rubble-enabled: false           # collapsed blocks leave physical rubble
  return-collapsed-blocks: false  # drop collapsed blocks as recoverable items
  rubble-ground-offset: 0         # vertical offset for where rubble settles
```

---

## Undermining

Makes a dig-under-a-wall collapse *look* like undermining. Pure presentation — the
same blocks fall either way; this only changes where the rubble lands. Only has any
effect when `rubble-enabled` is on (rubble has to be spawning to be biased).

When a break-triggered collapse spawns rubble, the falling blocks drift sideways toward
the hole you dug, so the tunnel partly fills back in and the wall reads as slumping into
the gap instead of dropping straight down.

```yaml
effects:
  undermine:
    backfill-rubble: true          # drift rubble toward the dug-out tunnel
    max-rubble-per-collapse: 200   # cap rubble entities per collapse (shares
                                   # the explosion debris budget by default)
```

- `backfill-rubble: false` keeps the collapse but lets rubble fall straight down.
- Lower `max-rubble-per-collapse` if a big undermine spawns too many falling blocks
  for slow clients.

---

## Learn more

- [Full key reference](index.md)
- [Physics Settings](physics.md)
- [Weather Settings](weather.md)
</content>
