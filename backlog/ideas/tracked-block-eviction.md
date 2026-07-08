---
name: tracked-block-eviction
title: Evict stale tracked blocks to bound long-run memory
scope: post-mvp
size: standard
created: 2026-06-18
wip-owner:
links:
  - "[[bound-big-collapse-cost]]"
  - "[[region-scoped-physics-default]]"
---

# Evict stale tracked blocks to bound long-run memory

## What & why

Known limitation of shipping **physics-on-everywhere** (the chosen free-release
default): every player-placed block is tracked into the structure graph and never
released, so on a big, long-running server the tracked-block set grows without bound —
memory + solve-scope creep over weeks. [[bound-big-collapse-cost]] stops it from
*freezing*, but doesn't stop it from *accumulating*. This brief bounds the accumulation.

Not a launch blocker (a fresh/normal server is fine for a long time; the freeze guard
ships first). Captured so the slow-burn isn't forgotten.

## Analysis

- **Dependencies:** orthogonal to the freeze guard; the opt-in
  [[region-scoped-physics-default]] is the other mitigation (region-only never tracks
  outside regions).
- **Sizing:** **standard** — needs care (eviction must not change collapse behavior for
  active structures).
- **Risk:** evicting a block that's still load-bearing for a standing structure would
  corrupt physics. Eviction must only drop blocks that are settled + untouched + safely
  re-derivable on next interaction.
- **Alternatives:** LRU/age-based eviction of settled untouched blocks · cap tracked
  blocks per world with oldest-out · rely on region-only opt-in instead · persistence
  pruning.
- **Scope decision:** `post-mvp` — long-run robustness, after the launch hardening.

### Open questions

1. Eviction policy — age/LRU vs hard per-world cap vs persistence pruning?
2. How to evict safely without changing the collapse of a structure that's later
   disturbed (re-scan on interaction)?

## Spec

_(to refine)_ — bound the tracked-block set per world (evict settled, untouched blocks
on an age/size policy; re-register lazily when a region is next disturbed), without
changing collapse outcomes for active structures.

## Acceptance criteria

1. **Given** a server running for a long time with many builds, **then** the tracked-
   block memory stays under a configured bound.
2. **Given** a structure whose blocks were evicted, **when** a support is later broken,
   **then** it still collapses correctly (eviction is transparent to behavior).
