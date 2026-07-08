---
name: release-smoke-test
title: Smoke-test the free plugin on a clean Paper 1.21 server
scope: post-mvp
size: small
created: 2026-06-16
wip-owner:
links:
  - "[[release-build-1-0]]"
---

# Smoke-test the free plugin on a clean Paper 1.21 server

## What & why

The plugin was developed alongside other projects; before we hand it to strangers we
must confirm it stands on its own — installs clean on a vanilla Paper 1.21
server with **no other plugins**, enables/disables without errors, and the
headline features actually work for a first-time admin. This is the "stress test a
little more" gate for the public release. Outcome: a documented clean-server run-through
that passes, so the Modrinth release isn't someone's broken first impression.

## Analysis

- **Dependencies:** [[release-build-1-0]] (tests that exact 1.0.0 artifact).
- **Sizing (INVEST):** **small** — a scripted/manual run-through on a throwaway server,
  not new code (any bug it finds becomes its own brief).
- **Risk:** silent failure — a feature that errors only on a fresh world / without soft-deps;
  default config not sane for a public server. The whole point is to surface these.
- **Alternatives considered:** rely on the existing unit/e2e suite — insufficient: those
  run under MockBukkit/with the dev setup, not a clean real Paper box with default config.
- **Scope decision:** `post-mvp` — release QA for the free plugin.

### Open questions

_None._

## Spec

On a fresh Paper **1.21.x** server with **only** `StructuralIntegrity-1.0.0.jar`
installed (no soft-deps), verify:
- Server starts; plugin **enables** with no errors/warnings in console; `/plugins`
  shows it green.
- Default `config.yml` is generated and is sane for a public survival server (no debug
  spam, no perf-killer defaults).
- **Collapse** works: build a small overhang/tower, knock out a support, the unsupported
  part falls (cascade) without lag spikes or exceptions.
- **Explosion** works: a TNT/creeper blast carves a crater and the surrounding structure
  reacts.
- `/engineer` toggles the load-path visualization; `/strux scan|grade|reinforce|repair`
  run without error on a scanned region.
- Server **stops** / plugin **disables** cleanly (no lingering tasks/errors).
- Re-run with WorldEdit + CoreProtect present → soft-dep hooks load, no conflicts.

Any failure becomes a separate bug brief; this brief passes only when the run is clean.

## Acceptance criteria

1. **Given** a clean Paper 1.21 server with only the 1.0.0 jar, **when** it starts,
   **then** the plugin enables with zero errors/warnings and generates a default config.
2. **Given** that server, **when** a supported structure loses its support, **then** the
   unsupported blocks collapse with no exceptions in console.
3. **Given** that server, **when** `/engineer` and each `/strux` subcommand are run,
   **then** each responds without throwing.
4. **Given** the server is stopped, **when** the plugin disables, **then** there are no
   errors and no orphaned tasks.

## Examples

- Pillar of 6 blocks on the ground, break the bottom 2 → top 4 fall (FLOATING) within a
  tick or two, console clean.
- `/strux scan` over a 10×10×10 region → reports node count, no stack trace.

## Scope boundaries

- ✅ **In scope:** a documented clean-server run-through + sane-default check; filing any
  bug it finds.
- 🚫 **Out of scope:** fixing bugs it finds (separate briefs), performance benchmarking,
  Bedrock/Geyser.
- ⛔ **Never touch:** core snapshots, `StruxMetrics` budgets, physics tuning.

## Implementation surface

- **Where:** a throwaway Paper 1.21 server (reuse the `docker/` Paper setup or a local
  one); the [[strux-live-e2e-verification]] recipe is the model.
- **Reuse:** existing docker-compose Paper config; the jar from [[release-build-1-0]].

## Verification

- Boot the clean server, run the checklist above, capture the console log → proves AC
  #1–#4. Attach the log to the brief on completion.

## E2E scenario

A first-time admin downloads the jar, drops it on their vanilla Paper box, starts up,
builds a tower, blows out the base, and watches it fall — no errors, no config fiddling.
That first-session experience is exactly what this brief protects.

## Affected audiences & doc pages

- **Admins** — confirm the `docs/wiki/getting-started/installation.md` + quickstart
  match the real clean-install experience; changelog admin line if anything changes.

## Risk note

This is a guard, not a feature — its value is catching a broken first impression. If it
finds bugs, file them rather than hot-fixing under this brief. Use a throwaway world;
never point it at a real server. Do not run two gradle/Paper builds against the same
checkout (see [[strux-test-hang-gotchas]]).

## Red evidence

Filled when work starts.

## Outcome

Filled at done/.
