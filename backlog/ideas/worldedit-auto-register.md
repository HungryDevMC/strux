---
name: worldedit-auto-register
title: Opt-in auto-register WorldEdit/FAWE edits as strux nodes
scope: post-mvp
size: standard         # trivial | small | standard | large  (trivial → lightweight refinement)
created: 2026-06-13
wip-owner:
links:
---

# Opt-in auto-register WorldEdit/FAWE edits as strux nodes

> Captured from a Q&A session. Refine (Example Mapping) before moving to `ready/`.

## What & why

WorldEdit and FastAsyncWorldEdit write blocks straight into the world and do **not**
fire `BlockPlaceEvent`. Strux only learns about a block via `BlockPlaceListener`
(the event) or an explicit `/strux scan` of a region. So blocks placed with
WorldEdit get **no structural integrity and consume no settle budget** — they're
invisible to the physics until someone scans them. This bit a gamemode when
its *build mode* switched to WorldEdit for fast placement: structures it built
stopped collapsing.

Strux deliberately does **not** auto-register every WE edit (a `//set` of a million
blocks would register and solve all of them and freeze the server). But we want an
**opt-in** path: when an admin turns it on, WorldEdit/FAWE edits up to a size cap are
automatically registered as strux nodes — so a gamemode (or a builder) gets
structural integrity transparently, without having to call `/strux scan` by hand.

## Analysis

- **Dependencies:** WorldEdit/FAWE soft-dep is already wired in the repo — commit
  `0129795` added the EngineHub maven repo and the FAWE-detection pattern
  (`StageWriters.detect`, "FAWE registers as WorldEdit"). Reuse the existing
  region-registration path: `RegionScanner` + `StructureManager` (the same code
  `/strux scan` runs — see `scan/RegionScanner.java`, `scan/WorldEditHook.java`,
  `scan/StruxCommand.java`).
- **Sizing (INVEST):** **standard (M).** One new listener + a config flag + a size
  cap + wiring; the registration logic itself already exists. Independent of the
  explain/replay brief.
- **Risk:**
  - *Perf footgun (the whole reason this is opt-in + capped).* A large WE edit could
    register millions of nodes and solve them, freezing the server. A **size cap** is
    mandatory: above N changed blocks, skip registration and log a warning. Default
    OFF.
  - *FAWE async / threading.* FAWE applies edits off the main thread. Strux graph
    mutation + cascade must run on the main thread (or strux's own executor) — hook
    the right `EditSessionEvent` stage and marshal registration back safely. Graph is
    not thread-safe.
  - *Double-registration.* If both the WE hook and `BlockPlaceListener` ever see the
    same block, guard against registering twice / re-solving redundantly.
  - *Not unit-testable glue.* FAWE is absent from the MockBukkit classpath (noted in
    `0129795`: "WorldEditStageWriter glue is not unit-testable … verified live"). Keep
    the WE glue thin and dumb; put the testable logic in the region-scan/register seam
    (`RegionScanner`) which already has tests, and verify the glue live.
- **Alternatives considered:**
  - *Caller scans the region after its WE placement* (status quo design; the caller's
    build-mode fix on its side). Still valid and complementary, but the user wants a
    transparent strux-side option so gamemodes don't each re-implement it.
  - *Always auto-register all WE edits* — rejected: the million-block-`//set` freeze.
    Hence opt-in + capped.
- **Scope decision:** `post-mvp` — adapter convenience/integration, not an MVP
  deliverable. Does not expand MVP scope. A gamemode's build-mode regression is the
  motivating case but its own fix lives in that gamemode's own code.

### Open questions

1. Which WorldEdit `EditSessionEvent` stage to hook, and how to enumerate the changed
   blocks reliably under both plain WE and async FAWE?
2. Default size cap value (changed-block count) — and is it per-edit or cumulative?
3. Should auto-registered regions be persisted/marked so a server restart re-scans
   them, or is registration in-memory only like a manual `/strux scan`?
4. Per-world / per-region scoping — auto-register everywhere it's enabled, or only
   inside designated build areas?

## Spec

_(to finalize after Open questions / `/refine`)_ — intended shape:

- New config (default OFF): `worldedit.auto-register: false` and
  `worldedit.auto-register-max-blocks: <N>` (the size cap).
- A WorldEdit `EditSessionEvent` listener, active only when a WorldEdit/FAWE plugin
  is detected (reuse the `0129795` detection). On a completed edit, collect the
  changed region/blocks; if the count ≤ cap, register them through the existing
  `RegionScanner`/`StructureManager` path (identical to `/strux scan`); if over cap,
  skip and log a warning.
- Registration runs on the main thread; safe under async FAWE.
- Default behavior unchanged: with the flag off, WE edits are not registered and
  `/strux scan` remains the way to register them.

## Acceptance criteria

1. **Given** `worldedit.auto-register: true`, WorldEdit installed, and an edit under
   the cap, **when** a player runs `//set`/`//paste`, **then** the placed blocks
   become strux nodes that collapse and consume settle budget exactly like
   hand-placed blocks.
2. **Given** an edit whose changed-block count exceeds the cap, **when** it completes,
   **then** strux skips auto-registration and logs a single warning (server not
   frozen).
3. **Given** the flag is off (default), **when** a WE edit happens, **then** strux
   does not register the blocks — behavior is identical to today.
4. **Given** both the WE hook and `BlockPlaceListener` could see a block, **then** it
   is registered at most once (no double-solve).

## Examples

- `worldedit.auto-register: true`, cap `20000`; player `//set stone` on a 10×10×10
  region (1000 blocks) → all 1000 become tracked nodes; knocking out the base
  cascades the rest.
- Same config; `//set` on a 100×100×100 region (1,000,000 blocks > cap) → no
  registration, log: "WE edit of 1000000 blocks exceeds auto-register cap 20000;
  skipped — run /strux scan to register a sub-region."

## Scope boundaries

- ✅ **In scope:** opt-in config, the WE EditSession listener, size cap, wiring to the
  existing region-register path, docs.
- 🚫 **Out of scope:** the gamemode's own build-mode fix itself (lives in that gamemode's code);
  changing the default `/strux scan` behavior; any core/physics change.
- ⛔ **Never touch:** core snapshots, `StruxMetrics` budgets, the host-agnostic core
  rule (this is adapter-only).

## Implementation surface

- **Files/modules to touch:** `adapter-minecraft/.../scan/` (new WE EditSession
  listener alongside `WorldEditHook`), `StructuralIntegrityPlugin` (register listener
  + config), config.yml + `docs/wiki/config/...`.
- **Reuse:** `RegionScanner`, `StructureManager` (the `/strux scan` path),
  `StruxCommand` scan logic, and the FAWE detection from `0129795`
  (`StageWriters.detect`).

## Verification

- `./gradlew :adapter-minecraft:test --tests "*RegionScanner*"` → proves the
  register-a-region seam (AC #1/#2 logic).
- Manual on a live FAWE server (glue not unit-testable): `//set` under and over the
  cap, confirm collapse + the cap warning → proves AC #1, #2.

## E2E scenario

A server admin enables `worldedit.auto-register`. A gamemode's build mode
pastes a keep via FAWE. Because the paste is under the cap, every block registers as
a strux node — so when an attacker saps the base, the keep collapses, exactly as it
did before build mode moved to WorldEdit. A builder doing a giant terrain `//set`
sees a "skipped — over cap" log instead of a frozen server.

## Affected audiences & doc pages

- **Admins** — new `docs/wiki/config/...` keys (`worldedit.auto-register`,
  `…-max-blocks`) + `[Unreleased]` admin changelog line.
- **Developers** — note in the scan/WorldEdit integration docs; dev changelog line.

## Risk note

Default OFF; the size cap is the load-bearing guard against the million-block freeze
— do not ship without it. FAWE runs async: marshal graph registration to the main
thread; the graph is not thread-safe. Guard against double-registration with
`BlockPlaceListener`. The WE glue is not unit-testable (FAWE off the MockBukkit
classpath) — keep it thin and verify live; assert the logic via `RegionScanner`
tests. Adapter-only: must not touch core snapshots or `StruxMetrics` budgets.

## Red evidence

Filled when the brief enters `in-progress/`.

## Outcome

Filled when the brief moves to `done/`.
