# Backlog

Every piece of work lives here as one Markdown file. The **folder a file is in is
its status.** Moving the file between folders is the only way to change status — there
is no `status:` field to keep in sync.

```
capture        analyze            meet DoR         pull (WIP ≤ 2)     ship
  │               │                  │                 │                 │
ideas/    →    analyzed/   →      ready/      →   in-progress/   →     done/
what & why    + Analysis        + Spec/AC/E2E/    + wip-owner          + Outcome
                                  Audiences/Risk    + Red evidence
```

- **`ideas/`** — raw capture. A title and a *What & why*. No commitment.
- **`analyzed/`** — the **Analysis** section is filled (deps, sizing, risk,
  alternatives, scope decision). Open questions still allowed here.
- **`ready/`** — passed the **Definition of Ready** (below). Only pull work from here.
- **`in-progress/`** — actively being built. **Hard cap: 2 files.** Each has a
  `wip-owner` and (once work starts) *Red evidence*.
- **`done/`** — shipped. Kept **forever** as history; the *Outcome* section records
  what happened. Done items never count against WIP.

`scope:` (`mvp` / `post-mvp` / `out`) lives in the frontmatter and is independent of
status. It records whether an item is part of the frozen release scope; changing scope
is a deliberate edit, not a side-effect of doing the work.

## Definition of Ready (mirrors the Definition of Done)

A brief may enter `ready/` only when **all** of these hold. The easy way to get there
is to fill it in together using Example Mapping (rules / examples / open questions).

- [ ] **Analysis** complete, and **Open questions** reads `_None._`.
- [ ] `scope:` set (`mvp`/`post-mvp`/`out`) and `size:` set.
- [ ] **Spec** filled — *observable behaviour*, no `_(…)_` placeholders left anywhere.
- [ ] **Acceptance criteria** — ≥1 numbered, independently-checkable item (Given/When/Then).
- [ ] **Examples** — ≥1 concrete case with real values (input → expected output).
- [ ] **Scope boundaries** — ✅ in / 🚫 out / ⛔ never-touch.
- [ ] **Implementation surface** — real files, interfaces, and existing code to reuse.
- [ ] **Verification** — exact commands, each tied to an acceptance criterion.
- [ ] **E2E scenario**, **Affected audiences & doc pages** (≥1 audience), **Risk note**.

**Scale by size:** a `size: trivial`, non-MVP brief takes a light path — only *What & why ·
Spec · Acceptance criteria · one Example* are required (delete the unused sections). Everything
else, and every `mvp` brief, needs the full set. Quantify or enumerate vague words
(fast/robust/simple/handle/etc.) rather than leaving them.

## Working the board

To change an item's status, move its file to the next folder:

```bash
git mv backlog/ideas/<slug>.md backlog/analyzed/<slug>.md    # after Analysis is filled
git mv backlog/analyzed/<slug>.md backlog/ready/<slug>.md     # after the DoR checklist passes
git mv backlog/ready/<slug>.md backlog/in-progress/<slug>.md  # keep in-progress/ to ≤ 2 files
git mv backlog/in-progress/<slug>.md backlog/done/<slug>.md   # when shipped
```

## Conventions the board relies on

- **WIP limit is 2.** Keep at most two files in `in-progress/` — finish or park one
  before starting a third. The point: stop starting, start finishing.
- **In-progress briefs are complete.** Don't move a brief to `in-progress/` until it
  meets the Definition of Ready; set its `wip-owner` when you do.
- **Scope is deliberate.** New ideas default to `scope: post-mvp`; promoting something
  into the release scope is an explicit decision, not a by-product of building it.
