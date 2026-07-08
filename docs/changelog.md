<!--
  MAINTAINERS: every behavioral change adds an entry under [Unreleased], tagged with
  its audience: (admins) for server operators, or (developers) for people embedding the
  engine / writing adapters. At release, move the [Unreleased] entries under a new
  version heading. Keep entries ELI5: short sentences, simple words, one idea per line.
-->

# What changed?

Every time strux changes, we write it down here.

Strux is a structural-integrity engine for voxel games. It watches how blocks hold each
other up, and it makes buildings sag, crack, and fall down when they lose support — like
a real building would. Game adapters (starting with Minecraft) plug into it.
## [1.0.0] — 2026-07-08

- (developers) Whole-graph stress solves got their speed back. A recent fix taught the solver
  to ignore stress from blocks outside the part it was asked to solve — needed when you solve a
  small window (like one placed block's support chain), but pure overhead when you solve the
  whole structure, because then nothing is outside. The solver now checks once, up front, whether
  the scope covers the whole graph (`subgraph.size() < graph.size()`) and skips the per-block
  membership test entirely when it does. This is the hot per-tick whole-world path, so it matters:
  about −6% on the whole-world solve and −15% on a dense small tower. Physics is byte-for-byte
  identical — a new equivalence test proves a whole-graph solve matches a guard-engaged solve
  node for node, and the partial-scope guard still fires (the phantom-load regression test stays
  green).
- (developers) The published JMH benchmark chart now reads in plain English. Before, each
  line on the chart was labelled with the raw Java method name (like
  `dev.gesp.structural.bench.SolverBenchmark.solveAll`), which told you nothing about what
  was being measured. Now a small map, `benchmarks/display-names.json`, gives every
  benchmark a sentence that says what it does and how big the scenario is (like "Whole-world
  stress-solve: every structure in the world"). A stdlib-only script,
  `benchmarks/scripts/humanize-results.py`, rewrites the JMH results file with these labels
  right after the benchmarks run (and keeps each `@Param` variant distinct by adding the
  value in parentheses). The CI benchmark job runs it automatically, and `./gradlew
  check` self-tests the script so it cannot silently rot. Unknown benchmark names pass
  through unchanged with a warning, so a new benchmark never breaks the chart — it just
  shows its raw name until you add a label.
- (developers) Tidied the collapse code without changing what it does. The moment-arm math
  that lived in one huge nested block inside `StressSolver` is now its own file,
  `MomentArmIndex` (with small helper types `ArmInfo`, `ArmQuery`, `ArmDetail`), so the solver
  file reads as "work out the stress" and hands the arm geometry to a well-named neighbour. The
  three block listeners (break, change, piston) used to each copy the same steps — sort collapsed
  blocks into floaters and overloaded, remove the floaters, schedule the overloaded, resume a
  cut-short cascade — so those steps now live once in a shared `CascadeResultHandler`. The physics
  is byte-for-byte the same: the arm-equivalence test still pins the new index to the old walk,
  the golden snapshots are untouched, and the perf budgets did not move. Purely how the code is
  arranged, not how buildings fall.
- (developers) Fire no longer freezes the game while it burns. A fire near a big keep
  chars blocks a little each second. The off-thread "settle" that works out what falls
  used to treat every char as a reason to throw its answer away and start over; after
  three tries in a row it gave up and did the whole keep-sized settle on the main thread —
  a visible stutter, again and again for as long as the fire burned. Now a char that only
  makes an already-covered block _weaker_ is not a conflict. The engine keeps its answer
  (which can only be too cautious, never wrong — a weaker block never stops a fall that was
  already going to happen) and any extra collapse is caught by the next settle. A new
  `asyncSettleDamageSkips` counter tracks these keeps-its-answer cases so the conflict
  counter stays honest. Real edits — placing, breaking, or repairing a block mid-settle —
  still start over as before.
- (admins) Fixed `container-weight.enabled` turning **on** by mistake when the key was
  missing from your config. The code fallback said "on" while the shipped config and the
  docs said "off" — so anyone who deleted or never had that line got heavy-container weight
  silently enabled. It now falls back to **off**, matching the shipped config and the docs.
- (admins) Config docs now match the real defaults. `cascade.settle-budget-ms` is `8.0`
  (the docs said `30.0`), and `effects.min-visible-damage` is `0.05` (the docs said
  `0.15`). Also documented three settings that the plugin already reads but the reference
  page never listed: `cascade.async-settle`, the whole `logging.restore-scan.*` group, and
  `entity-weight.fall-impact.force-impact-distance`.

- (developers) Placing a block against a big connected building no longer collapses parts
  of it with a fake "overload". When you add a block, the engine only re-checks the
  building right under it (the support chain), which is fast. But that small check used to
  peek at leftover stress numbers on the walls just outside it — and on a closed loop like
  a castle's curtain wall, those stale numbers piled up into a pretend overload that
  crushed wall the whole-building check (and the green stress particles) said was fine. The
  scoped stress solve is now scope-honest: it only counts weight from blocks inside the
  part it is solving, so a single placement carves the same result whether or not something
  solved the wall a moment earlier. A block that genuinely overloads what it rests on still
  falls.
- (developers) Explosions no longer over-collapse when a blast lands next to a structure
  that was already stress-solved. The blast's overload settle used to peek at leftover
  stress numbers on blocks just outside the area it was solving, and those stale numbers
  could tip an edge block into a fake collapse. Now the blast widens its solve to include
  any block a candidate leans on before it drops anything — the same guard the cascade
  already uses — so a blast carves the same crater whether or not something solved the
  wall a moment earlier.
- (developers) A structure a plugin registers at startup (in `onEnable`, via
  `addBlockDirect`) is no longer wiped when the saved graph loads from disk a tick later.
  The boot load now *merges* into the live graph — the blocks you just registered win on
  any shared spot, and any extra saved blocks are added beside them — instead of replacing
  the whole world graph. Registering structures at enable is now safe with file
  persistence on.
- (admins) A plugin's own startup-built structures (a demo castle, a minigame arena) keep
  their physics across a restart instead of occasionally losing it to the previous
  session's save.
- (developers) The Minecraft adapter now drives collapse through a `PhysicsService` seam
  (see SCALING.md §2) instead of calling the core engines inline. By default it binds the
  in-process `LocalPhysicsService` — nothing changes for embedded ("Pro") use. Later, an
  Enterprise build can bind a remote physics tier by injecting a different `PhysicsService`
  into `StructureManager`, with no change to the manager or the core. The interface
  documents the single-owner-thread, in-place-mutation ownership contract a remote
  implementation must honour. Pure refactor: behaviour and metrics are unchanged.
- (admins) The per-tick collapse budget (`cascade.settle-budget-ms`) now really works,
  and its default dropped from **30 to 8**. A big keep blowing up used to be able to
  freeze the server for a moment, because the budget only got checked *between* falling
  blocks — but one step of the maths could take much longer than the budget on its own.
  Now the maths itself stops and picks up next tick, so a huge collapse spreads out over
  many ticks instead of one long stall. The same blocks still fall — just a little later.
  One pass may run up to about **twice** the budget (so ~16ms at the default), because it
  always finishes the small piece it is in the middle of. Set it to `0` to turn the budget
  off (the old "run until done" behaviour, handy for offline benchmarks).
- (developers) The two heavy steps of a settle are now interruptible and resumable:
  `StructureGraph.affectedRegion` (the closure BFS) and `StressSolver.findOverloadedBatch`
  (the stress level-scan). Each takes a `BooleanSupplier pause` and a cursor
  (`ScopeClosureCursor` / `BatchScanCursor`) so a budget can stop it mid-step and continue
  next call via the existing `SettleOutcome.truncated()` / `remainingScope()` resume
  contract. A resumed-to-fixpoint settle is byte-identical (same blocks, same order) to an
  unbudgeted one.
- (developers) A budget pause that lands *mid-collapse* (partway through an overloaded
  batch, or a floating drain) now **parks the un-collapsed remainder** and replays that
  exact list on resume, before re-querying the solver. Before, the resume re-ran
  `findOverloadedBatch` on the already-mutated graph, which can return a different set —
  so, since the pause is a wall-clock deadline, server load could change *which* blocks
  collapse. Now paused and unpaused runs are byte-identical no matter where the pause
  falls (the pacing budget changes only *when* blocks fall, never *which*).
- (developers) Pistons now really cause collapse. Before, a piston pulling a block out
  from under a tower left the tower floating in the air forever — the code deleted the
  moved block one step too early, so the collapse check found nothing to drop. Now the
  moved block leaves through the same break-and-fall path a normal broken block uses, so
  whatever it was holding up comes down. It also means piston collapses respect the
  anti-freeze budget and show up in the perf counters, like every other collapse.
- (developers) A falling sand or gravel block now takes its riders with it. Before, when
  sand started to fall the block resting on it just hung in the air. Now the fall runs the
  same collapse check as fire, melting, and enderman pickups, so the thing on top loses its
  support and falls too.
- (developers) Fixed the parallel cascade driver so surviving blocks keep the same
  solved stress as the serial engine. Before, settling independent structures in
  parallel dropped the freshly-computed stress off survivors. Topology matched, but
  anything reading stress afterwards — the entity-weight collapse check, the stress
  overlay, structural grades — could see different numbers, and even make different
  follow-up collapse decisions, depending on whether the parallel path ran. Now the
  merge copies each survivor's vertical and moment stress back, so both paths are
  truly identical.
- (admins) The CoreProtect "clear cracks after a rollback" scan no longer hammers the
  server after a big blast. It now asks the database about a limited number of blocks
  per run (`logging.restore-scan.max-lookups-per-run`, default 50), takes turns so no
  block is skipped, runs on a configurable schedule (`interval-ticks`, default 100 =
  every 5 seconds), and stays quiet while a blast or collapse is still happening
  (`defer-during-cascade`, default true). See
  [Integrations → CoreProtect](wiki/admin/integrations.md#clearing-cracks-after-a-rollback).
- (developers) The restore detector reads a shared busy signal built from
  `BlastProcessor.hasActiveBlast()`/`queueSize()` and `CascadeResumeManager.pendingWorlds()`,
  and defers its scan while either is active — bounded so it still scans after 10
  consecutive skips.
- **(admins)** New `memory.eviction` setting (off by default). Turn it on and strux stops
  holding far-away structures in memory: once every chunk a structure sits in is unloaded,
  it is parked to a small side store and dropped from the live graph, then brought back —
  exactly as you left it, cracks and all — the moment any of its chunks loads again. This
  keeps memory flat on servers that run for weeks instead of climbing with every build. A
  `grace-ticks` delay avoids churn when a player walks back and forth.
- **(developers)** Component memory eviction, adapter-side (SCALING.md §5–§6). The eviction
  unit is a whole connected component — never a chunk slice — evicted only when all its
  chunk columns are unloaded, so support can never be evicted out from under a live block.
  Evict → restore is bit-identical (StructureData round-trips full per-node state; stress is
  re-solved). Thermally-softened components are never evicted (that transient state is not
  persisted). New additive core method `StructureConverter.mergeInto` merges a snapshot into
  an existing graph.
- **Big collapses now compute off the main thread.** (admins) A new
  `cascade.async-settle` setting (default on) works out a keep-sized collapse on a
  helper thread and applies it a few blocks per tick, so a big blast no longer
  briefly hitches the server. The blocks that fall — and the order they fall in —
  are exactly the same as before. If someone edits a block mid-collapse the answer
  is recomputed so their edit wins; a run of conflicts logs a `WARNING` and falls
  back to the old on-the-main-thread solve. While an explosion is still carving
  its crater the helper waits and solves once the blast finishes, so TNT chains
  never trigger that fallback. Set it to `false` for the old always-synchronous
  timing.
- **New graph modification stamp + solvable snapshot.** (developers)
  `StructureGraph.modCount()` moves on any outcome-changing edit (topology *and*
  damage/repair/reinforcement), and `copySolvableSubgraph(scope)` takes a scoped
  copy that keeps the connectivity index so a full `settleResult` can run on it
  off-thread. These back the adapter's new `AsyncSettleCoordinator` (snapshot →
  off-thread solve → conflict-checked main-thread apply). See
  [architecture](wiki/dev/architecture.md#a-note-on-threading).

## [0.9.0] — 2026-06-18 (pre-release baseline)

The first public release — the full engine, free on a single server: realistic collapse,
the stress overlay, explosions, fire, weather, temperature, reinforcement, structural
grades, and the developer API + SDK.
