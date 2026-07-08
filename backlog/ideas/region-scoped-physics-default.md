---
name: region-scoped-physics-default
title: Region-only physics as an opt-in mode
scope: post-mvp
size: standard
created: 2026-06-18
wip-owner:
links:
  - "[[bound-big-collapse-cost]]"
  - "[[tracked-block-eviction]]"
---

# Region-only physics as an opt-in mode

## What & why

Decided: the free plugin ships **physics-on-everywhere** by default (works on install).
This brief adds **region-only as an opt-in admin mode** — physics applies only inside
designated regions — for production servers that want bounded scope, and as a mitigation
for long-run accumulation ([[tracked-block-eviction]]). **Not the default.**

**Why it's cheap + clean:** the region gate runs *before* a block is registered
(`BlockPlaceListener:126` / `BlockBreakListener:100` → `guard.physicsAllowed` → bail if
denied), so in region-only mode blocks outside regions are **never tracked** — it kills
tracking, accumulation, and solve cost outside regions, not just collapse behavior.

Today the gate is allow-by-default (WorldGuard `strux-physics` flag defaults to ALLOW,
`StruxFlags:52`; gate `return state != DENY`, `StruxFlags:78`). Add `regions.physics-mode:
allow-by-default (default) | deny-by-default`; when `deny-by-default`, invert to
`return state == ALLOW` so physics only runs inside an explicitly-allowed region.

## Analysis

- **Dependencies:** none; complements the always-on freeze guard
  [[bound-big-collapse-cost]].
- **Sizing:** **standard** — config key + gate inversion (~1–2h). Requires WorldGuard
  for the region grant (acceptable for an advanced opt-in; a strux-native region is a
  later option).
- **Risk:** an admin enabling deny-by-default without an allow-region gets no physics
  (intended for this mode, but document it clearly so it's not mistaken for a bug).
- **Scope decision:** `post-mvp` — opt-in admin feature, not a launch blocker.

### Open questions

_None._  (Decided: opt-in, default stays allow-by-default. WorldGuard required for the
mode; a strux-native region is a separate future option.)

## Spec

Add `regions.physics-mode: allow-by-default | deny-by-default` (default
`allow-by-default`). When `deny-by-default`, invert the WorldGuard gate so physics
applies only inside a region with the `strux-physics` flag set to ALLOW. `disabled-worlds`
keeps working. Document the mode + the "nothing happens until you flag a region" caveat.
Touches `RegionConfig`, `PluginConfigLoader`, `StruxFlags`/`AbstractProtectionService`,
`config.yml`.

## Acceptance criteria

1. **Given** `physics-mode: deny-by-default` and a `strux-physics`-ALLOW region, **when**
   a structure inside it loses support, **then** it collapses; outside the region nothing
   is tracked or collapses.
2. **Given** `physics-mode: allow-by-default` (default), **then** behavior is exactly as
   today (whole-world, backwards compatible).

## Examples

- `physics-mode: deny-by-default` + a WorldGuard region `arena` with
  `/rg flag arena strux-physics allow` → physics only in `arena`.

## Scope boundaries

- ✅ **In scope:** the `physics-mode` config + gate inversion + docs.
- 🚫 **Out of scope:** a strux-native region system; making it the default.
- ⛔ **Never touch:** physics, core snapshots, `*Metrics`.

## Risk note

Keep `allow-by-default` the default (backwards compatible). The gate must stay
pre-registration so opt-in region-only also removes perf cost outside regions. Document
the inert-without-a-region behavior so it isn't reported as a bug.
