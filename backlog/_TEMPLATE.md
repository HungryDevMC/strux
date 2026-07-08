---
name: my-slug
title: Human-readable title
scope: post-mvp        # mvp | post-mvp | out
size: standard         # trivial | small | standard | large  (trivial → lightweight refinement)
created: 2026-06-13
wip-owner:             # set automatically when moved to in-progress/
links:                 # optional list of [[other-slug]] dependencies
---

# Human-readable title

> Fill every section up to **Risk note** before this brief can move to `ready/`. Delete this
> quote block and every `_(fill me in)_` placeholder as you go — a brief is not ready while a
> placeholder remains, and vague words should be quantified. Fill it in with Example Mapping
> (rules / examples / open questions).
>
> **`size: trivial`** (a few lines, no new interfaces, obviously correct) takes the lightweight
> path: only *What & why · Spec · Acceptance criteria · one Example* are required. Everything
> else here is required for `small`/`standard`/`large` and for any `scope: mvp` brief.

## What & why

_(fill me in)_ — one short paragraph: the problem or need, and the outcome we want. ELI5.
This is the **vertical slice** of value — one observable outcome, not "the database part".

## Analysis

Required before leaving `analyzed/`.

- **Dependencies:** _(fill me in)_ — what must exist first. Link briefs as `[[slug]]`.
- **Sizing (INVEST):** _(fill me in)_ — set `size:` above. Is it **S**mall (fits one sitting)
  and **I**ndependent? If not, split it (SPIDR — Spike/Path/Interface/Data/Rules) into separate
  briefs and link them; don't refine a brief that's secretly five.
- **Risk:** _(fill me in)_ — what could go wrong; especially silent-failure modes.
- **Alternatives considered:** _(fill me in)_ — and why this approach wins.
- **Scope decision:** _(fill me in)_ — `mvp` / `post-mvp` / `out`, and the reason.

### Open questions

_None._  ← must read "_None._" (or be empty) before this brief can become ready.

## Spec

_(fill me in)_ — what gets built, precisely, in terms of **observable behaviour** (not how to
code it). Detailed enough that two people would build the same thing. Use real names, values,
versions, and config keys — specificity is what stops an implementer (human or agent) guessing.

## Acceptance criteria

Numbered, each independently checkable, written **Given / When / Then** so each maps to a test.

1. **Given** _(some state)_ **when** _(an action)_ **then** _(an observable, checkable result)_.

## Examples

Concrete worked cases with **real values** that make the criteria unambiguous (Example Mapping
green cards). At least one. These double as the seed for tests.

- _(input / starting state)_ → _(exact expected result)_

## Scope boundaries

Say what's in, out, and untouchable — or the implementer will decide for you.

- ✅ **In scope:** _(fill me in)_
- 🚫 **Out of scope (this brief):** _(fill me in)_ — defer to a separate brief if it matters.
- ⛔ **Never touch:** _(fill me in)_ — files/areas/invariants this work must not change
  (e.g. core snapshots, `StruxMetrics` budgets, the host-agnostic core rule).

## Implementation surface

Where it lives and what to reuse — **not** a line-by-line how-to (over-specifying handcuffs the
implementation).

- **Files/modules to touch:** _(fill me in)_ — real paths.
- **Key interfaces / signatures:** _(fill me in)_ — the method(s)/types to add or extend.
- **Reuse:** _(fill me in)_ — existing functions/classes to build on (with paths).

## Verification

How "done" is proven. Each line ties a command to the acceptance criterion it checks.

- `./gradlew :module:test --tests ...` → proves AC #_(n)_
- _(manual/E2E step if needed)_ → proves AC #_(n)_

## E2E scenario

_(fill me in)_ — the player / admin / developer story, start to finish, in plain language.

## Affected audiences & doc pages

- **Players** — _(fill me in)_ (which `docs/wiki/player-guide/...` page + changelog line)
- **Admins** — _(fill me in)_ (config page + changelog line)
- **Developers** — _(fill me in)_ (dev page + changelog line)

(Delete the audiences that don't apply; keep at least one.)

## Risk note

_(fill me in)_ — silent-failure guards, perf/`*Metrics` impact, snapshot impact, MockBukkit
teardown gotchas, anything a reviewer should watch.

## Red evidence

Filled when the brief enters `in-progress/`: the failing tests captured before implementation,
so the green at the end means something.

## Outcome

Filled when the brief moves to `done/`: the commit(s), what actually shipped, and any deviation
from the spec above.
