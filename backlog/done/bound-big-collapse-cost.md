---
name: bound-big-collapse-cost
title: Freeze-proof big collapses (hard caps so TPS never tanks)
scope: post-mvp
size: standard
created: 2026-06-18
wip-owner: hungrydev
links:
  - "[[region-scoped-physics-default]]"
  - "[[tracked-block-eviction]]"
---

# Freeze-proof big collapses (hard caps so TPS never tanks)

## What & why

The free plugin ships **physics-on-everywhere** (works on install — good first
impression). The one thing that must hold for that to be safe: **a big collapse can
never freeze the server, regardless of structure or world size.** Incremental building
is already cheap; big collapses are the spike.

Investigation pinned the bottleneck: it's **not** the math (the solve is ~600µs; a tall
column collapses as "floating" with 0 re-solve). It's the **main-thread world-apply** —
per collapsed block a `setType(AIR)` + ~12 particles + a sound
(`DelayedCollapseManager.collapseBlock`), capped at 25/tick. So it chunks rather than
hard-freezes, but the resume path spends up to the 30ms settle budget **every tick**
while it falls, so **TPS sags for the collapse's whole duration**. We need a hard
guarantee, not just chunking.

## Analysis

- **Dependencies:** makes physics-on-everywhere safe; pairs with the opt-in
  [[region-scoped-physics-default]] and the long-run [[tracked-block-eviction]].
- **Sizing:** **standard** — a world-apply path change + adaptive throttle, no solver
  change.
- **Risk:** lowering caps makes collapses look draggy; the FAWE bulk path must keep a
  vanilla main-thread fallback (Bukkit world mutation is main-thread-only). Don't
  regress the collapse *drama*.
- **Levers (chosen):**
  1. **TPS-adaptive throttle** — when recent tick time is high, automatically shrink the
     per-tick collapse budget so the server never drops below a TPS floor; the collapse
     just takes more ticks under load. This is the "never freeze regardless of size"
     guarantee.
  2. **Bulk world-writes via FAWE** — route collapse `setType(AIR)` through the
     bulk-edit path the crater applier already uses (`blast.fawe-acceleration` /
     `CraterApplier`), removing the per-block main-thread cost; vanilla per-tick loop
     stays as fallback.
  3. **Batch-sampled effects** — emit collapse particles/sounds per *batch/region*, not
     12 particles per block (big packet-load cut).
- **Scope decision:** `post-mvp` — release perf hardening; the gate for shipping
  physics-on-everywhere.

### Open questions

_None._  (Decided: ship physics-on-everywhere; guarantee a TPS floor via adaptive
throttle + FAWE bulk-writes + batched effects. Default TPS floor 18.0, tunable.)

## Spec

- Add a **TPS-adaptive throttle** to the collapse apply path
  (`DelayedCollapseManager` / `CascadeResumeManager`): track recent mean tick time;
  when TPS dips below `cascade.tps-floor` (default **18.0**), reduce that tick's
  collapse work (fewer blocks applied, smaller settle pass) so TPS recovers. A collapse
  always completes — it just slows under load. Never exceeds a per-tick wall-clock
  ceiling.
- **Bulk world-writes:** when FAWE is present, apply each tick's batch of AIR-sets via
  the existing bulk-edit writer (as the crater applier does); fall back to the per-tick
  `setType` loop otherwise.
- **Batched effects:** sample collapse particles/sounds per batch (config
  `effects.collapse-particle-sampling`, default ~1 puff per N blocks) instead of per
  block.
- Keep the solver and its `StruxMetrics` budgets untouched; add a world-apply cost
  signal (blocks-applied/tick, throttle engagements) for observability.

## Acceptance criteria

1. **Given** a 1,000-block structure collapsing on a populated server, **when** it
   falls, **then** server TPS stays at/above `cascade.tps-floor` for the entire
   collapse (no freeze, no sub-floor sag).
2. **Given** the same collapse, **then** every block still comes down (drama
   preserved) — the result is identical, just spread across more ticks / via bulk
   writes.
3. **Given** FAWE is installed, **when** a big collapse runs, **then** AIR-sets go
   through the bulk writer (verified via the world-apply signal / logs), with a working
   vanilla fallback when FAWE is absent.
4. **Given** the change, **then** the deterministic `StruxMetrics` solve counts and
   their perf-gate budgets are unchanged (this targets world-apply, not the solver).

## Examples

- `tps-floor: 18.0`; 1,000-block keep collapses while 10 players online → TPS dips to
  ~18 and holds, collapse finishes over ~2–3s instead of freezing.
- FAWE present → one bulk AIR edit per tick-batch instead of 25 individual `setType`
  calls.

## Scope boundaries

- ✅ **In scope:** the collapse world-apply path (throttle, bulk-writes, effect
  sampling), the `cascade.tps-floor` + effect-sampling config.
- 🚫 **Out of scope:** the solver/cascade algorithm; region-only mode
  ([[region-scoped-physics-default]]); long-run memory growth
  ([[tracked-block-eviction]]).
- ⛔ **Never touch:** core snapshots, `StruxMetrics` solve budgets, the physics result
  (same blocks down).

## Implementation surface

- **Files:** `adapter-minecraft/.../listener/DelayedCollapseManager.java`,
  `.../listener/CascadeResumeManager.java`, the effects config + `config.yml`, and the
  FAWE bulk writer reused from `CraterApplier` / the `blast.fawe-acceleration` path.
- **Reuse:** the existing per-tick chunking, the FAWE crater bulk-edit writer, server
  TPS via Paper's `Bukkit.getServer().getTPS()` (or a tick-time sampler).

## Verification

- A timed soak test: collapse a large structure on the docker server with bots/load,
  sample TPS via RCON/`/tps`, assert it stays ≥ floor → proves AC #1/#2.
- Unit-test the throttle's budget-shrink logic in isolation (pure, MockBukkit-safe).
- `./gradlew :core:test` unchanged (solver untouched) → proves AC #4.

## E2E scenario

An admin installs the free plugin, a player blows up a big tower while others are on —
the tower comes crashing down dramatically and the server holds its TPS the whole time,
instead of hitching. Nobody touches a config.

## Affected audiences & doc pages

- **Admins** — new `cascade.tps-floor` + effect-sampling config; `docs/changelog.md`
  admin line; a "performance / it can't freeze your server" note in the admin docs.
- **Developers** — the world-apply throttle + bulk-write path in the dev docs.

## Risk note

Bukkit world mutation is main-thread-only — only FAWE safely bulks it; keep the vanilla
fallback. The adaptive throttle must never *stall* a collapse permanently (it slows,
always completes). Don't regress collapse drama. Solver snapshots / `*Metrics` budgets
must not move — this is a world-apply change only. Hot path: run JMH + the perf gate.

## Red evidence

Filled when work starts.

## Outcome

Filled at done/.
