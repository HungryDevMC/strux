# Admin Guide

<div class="page-aside left"><img class="page-mascot" src="../assets/img/mascot/pointing.png" alt="The Strux Mason"></div>












Setting up and running Strux on your server: commands, permissions, protection,
and the optional plugin integrations.

---

## Commands

All player-facing subcommands need a player in the world. The exception is
`/strux record`, which runs from the **console or RCON** too (it's operator
tooling for CI and replay).

| Command | What it does | Permission |
|---------|--------------|------------|
| `/strux` (no args) or `/strux help` | List the subcommands you can use | — |
| `/strux wand` | Get the Strux Scanner (click two corners) | — |
| `/strux pos1` / `/strux pos2` | Set selection corners to the block you look at | — |
| `/strux scan` | Give everything in the selection structural integrity (re-set the corners if their world was unloaded) | — |
| `/strux grade` | Structural grade of this world's builds | — |
| `/strux predict` | How much would collapse if you broke the block you look at | — |
| `/strux reinforce` | Reinforce the block you're looking at | `structuralintegrity.reinforce` |
| `/strux repair` | Clear damage on the block you're looking at | `structuralintegrity.repair` |
| `/strux perf` | Performance: solve time, per-task timings, tracked blocks, safety caps | — |
| `/strux metrics` | Toggle the real-time metrics boss bar | — |
| `/strux beam [n]` | Get Support Beam items | `structuralintegrity.admin` |
| `/strux demo` | Build a demo fort (testing/recording) | `structuralintegrity.admin` |
| `/strux record <…>` | Event recording (console/RCON capable) | see below |
| `/engineer` | Toggle the stress overlay | `structuralintegrity.engineer` |

`/engineer` also has the aliases `/stress` and `/loadpath`.

!!! warning "There is no `/strux reload`"
    Strux does **not** have a config-reload command. Edit `config.yml`, then
    restart the server to apply changes.

### Recording subcommands

`/strux record <sub>` — works from console and RCON. `start`, `stop`, `list`,
`replay`, `verify`, `export`, `clear`, and `status`. Every subcommand except the
read-only `list` and `status` requires `structuralintegrity.admin` — so a normal
player can't stop a recording in progress, hog the recorder, or trigger heavy
`verify` replays. Recording names are
confined to the recordings folder — a name that tries to climb out of it (`../…` or
an absolute path) is rejected, so the command can't be used to read other files.

Set `recording.auto-record: true` to start a recording automatically when the
server boots. (This used to crash the plugin on startup; it now starts cleanly,
beginning the session from the first world's current structure.)

When the recording count passes `max-sessions`, the oldest recordings are deleted
first — measured by when each file was last written, so a recording you captured
moments ago is never removed ahead of an older one.

---

## Permissions

The plugin registers these (note the `structuralintegrity.` prefix — not
`strux.`):

| Permission | Grants | Default |
|------------|--------|---------|
| `structuralintegrity.engineer` | `/engineer` overlay (and its `/stress`, `/loadpath` aliases) | everyone |
| `structuralintegrity.reinforce` | `/strux reinforce` + Support Beams | everyone |
| `structuralintegrity.repair` | `/strux repair` | everyone |
| `structuralintegrity.admin` | `/strux beam`, `/strux demo`, recording writes | ops |

There are no `strux.use`, `strux.notify`, or `strux.bypass` permissions — those
do not exist in the plugin.

---

## Protecting areas

You probably don't want spawn or arenas to collapse. There are two layers of
control, both under the `regions:` section.

```yaml
regions:
  # Master switch. false = physics runs everywhere, no protection at all.
  enabled: true

  # Worlds where physics NEVER runs (case-insensitive names).
  disabled-worlds:
    - creative_plots
    - lobby

  # Honor the WorldGuard "strux-physics" region flag (only if WorldGuard is on).
  respect-worldguard: true
```

Even a collapse that *starts* in an allowed area will not destroy blocks that
fall inside a protected one — protected regions stay safe no matter where the
collapse began. Protection is also resolved per world, so two explosions in
different worlds at the same coordinates never share each other's verdict.

### With WorldGuard

Strux registers a `strux-physics` region flag (defaults to **allow**, so physics
works everywhere until you deny it). Requires WorldGuard installed; the flag is
registered during plugin load.

```
//wand
//region create spawn
/rg flag spawn strux-physics deny   # physics OFF in this region
/rg flag spawn strux-physics allow  # physics ON (the default)
```

Strux processes placements, explosions, fire, and projectile hits **last** among
plugins (HIGHEST event priority), so if another plugin cancels the event — a
protection plugin, a mini-game, an anti-grief tool — Strux honors it and does nothing.

### War zones

War-zone scoping turns Strux into a siege mechanic: destruction only happens
inside an **active war zone**, and structures are collapse-proof everywhere else.
WorldGuard denies still apply on top.

```yaml
regions:
  war-zone:
    enabled: false              # true = destruction only in active war zones
    providers: [towny, factions]  # which plugin(s) define a war zone
    allow-wilderness: false     # count unclaimed wilderness as a war zone?
```

Providers are hooked **reflectively** — Towny and Factions are optional, and a
provider that isn't installed is simply skipped. If both are listed, a location
counts as a war zone if **either** says so.

---

## Performance

```
/strux perf
```

Reports tracked block counts (total + this world), average and worst solve time,
cumulative engine work (solves, node visits, blocks collapsed), and the active
safety caps. Use it to spot a build that's straining the solver.

### Per-task table

Below the solver line, `/strux perf` also prints one line for each repeating task
that has run at least once. These are the small jobs strux runs every few ticks:
the crack and stress visuals, entity weight, fire, weather, and the explosion /
arrow / collapse queues. They are where steady-state lag hides.

Each line shows four numbers:

- **avg ms** — how long one pass takes on average.
- **worst ms** — the slowest single pass seen.
- **passes** — how many passes are in the sample window.
- **work/pass** — how much that task did per pass (nodes scanned, entities
  checked, items settled, blocks dropped). This is the key one.

The work number tells you *why* a task is slow. A high avg ms with a high
work/pass means "slow because it has a lot to do" — a big build. A high avg ms
with a *low* work/pass means "slow per unit" — something pricey per item. Two
very different fixes.

Idle tasks are skipped: a task that had nothing to do (no explosions queued, no
collapses falling) does not print, so the table only ever shows real work.

Example: if `weather-load` shows a big avg ms and a big work/pass, the weather
sweep is touching a lot of blocks — lower `weather.tick-budget-ms` to spread it
over more ticks. If `damage-visualizer` dominates, it is re-scanning every
tracked block for cracks every pass; raise `visual-update-ticks` so it runs less
often.

The main lag levers are `max-cascade-steps` (physics cap) and
`effects.max-collapses-per-tick` (visual cap) — see the
[Physics](../config/physics.md#big-collapses-and-the-cap) and
[Effects](../config/effects.md#collapse-timing) pages. The impact, fire, and
weather scans each have a `tick-budget-ms` seatbelt so a busy siege can never
freeze a tick. Lower those budgets if the server stutters, raise them for
snappier walls.

A big explosion's crater is also carved a few blocks per tick rather than all at
once (the `blast-queue` task), so a huge blast can never freeze the tick it lands
in. `blast.max-crater-removals-per-tick` sets how fast the crater forms;
`blast.crater-effect-sample-rate` thins the break sounds/particles on a giant
crater; and `blast.fawe-acceleration` reserves the hook for handing the air-block
writes to FastAsyncWorldEdit in one bulk edit (that writer ships in a later update).
See the [Explosions config](../config/index.md#explosions-blast).

There is no `skip-unloaded-chunks` setting — Strux only ever works on tracked
blocks, so empty/unloaded areas cost nothing.

---

## Logging & rollback (CoreProtect)

Strux can log every collapse removal to CoreProtect, attributed to the user
`#strux`, so you can answer "what flattened my base?" and even undo a bad
collapse.

```yaml
logging:
  # Log collapses to CoreProtect (only if CoreProtect is installed).
  coreprotect: true
```

```
/co inspect              # click a block to see if strux removed it
/co rollback u:#strux t:5m   # restore everything strux collapsed in 5 min
```

If CoreProtect isn't installed, this key is harmlessly ignored.

---

## Optional integrations

Every external hook is optional and guarded — the plugin loads fine without any
of them. `softdepend`: WorldEdit, WorldGuard, CoreProtect, Vault, PlaceholderAPI,
Towny, Factions.

| Plugin | What it adds | If absent |
|--------|--------------|-----------|
| WorldGuard | `strux-physics` region flag | per-world toggles still work |
| CoreProtect | collapse logging + rollback | logging skipped |
| Vault | charge currency for strux actions (`economy:` costs) | all actions free |
| PlaceholderAPI | `%strux_grade%` etc. | placeholders disabled |
| Towny / Factions | war-zone providers | that provider skipped |
| WorldEdit | `//wand` selection for `/strux scan` | use `/strux wand` instead |

With no Vault/economy plugin, every `economy:` charge defaults to free anyway, so
you can leave those costs set without Vault installed.

**Placeholders and async plugins.** Some plugins (chat formatters, scoreboards, TAB)
resolve placeholders on a background thread. Strux never grades a world off the main
thread — doing so could corrupt the live structure. When a `%strux_*%` placeholder is
asked for off-thread, strux returns the **last value it had** (or an empty `S` / `0`
if it has not graded that world yet); the number refreshes on the next on-thread
request. So an async scoreboard may lag the live grade by a moment — it never blocks
or destabilises the server.

---

## Troubleshooting

**`persistence.type: api` is being ignored (writes to files instead).** Older builds
read config and command text using the server's system locale, which mangled values like
`api` on a Turkish-locale JVM. Strux now parses these with a fixed locale, so a non-English
server locale no longer affects persistence type, entity names, `/strux record` subcommands,
or biome checks.

**Nothing is collapsing.** Check `regions.enabled` and whether the world is in
`disabled-worlds`, or a WorldGuard region denies `strux-physics`. If war-zone
scoping is on, destruction only happens inside an active war zone. Use
`/strux predict` on a support block to see what *should* fall.

**A world loaded with Multiverse has no physics on its old builds.** Strux now loads a
world's saved structures the moment the world comes up, not just the worlds present at
startup — so `/mv load`, dynamic worlds, and lazy-loaded worlds all keep their physics.
(If you are on an older build, restart with the world already loaded as a workaround.)

**Errors or buildup when a world unloads mid-collapse.** Strux now drops any collapses
still queued in a world the moment that world unloads, so they cannot error against a
gone world or pile up — they simply stop.

**A few blocks float and never fall after a restart.** If the server was restarted
*mid-collapse* (a big cascade was still falling block-by-block when it went down),
strux now finishes those pending removals before saving, so the world and its saved
structure stay in step. Older builds dropped the in-flight removals, leaving a handful
of floating, untracked blocks; breaking and replacing such a block re-registers it.

**Too much lag.** Lower `max-cascade-steps` and `effects.max-collapses-per-tick`,
and lower the `tick-budget-ms` seatbelts (impact/fire/weather). Use `/strux perf`
to find the strained world. Strux also no longer loads far-away chunks to check
weather or container weight on builds nobody is near — those checks now skip
unloaded chunks and run when the chunk is next loaded. Stress particles and
creaking sounds are likewise only drawn for stressed blocks in loaded chunks with
a player within 48 blocks. And a collapse too big to finish in one batch
(weather/weight/heat) now continues over the next ticks instead of stranding
floating blocks.

**Players griefing with collapses.** Protect key areas with WorldGuard
(`strux-physics deny`) or add them to `disabled-worlds`. Consider war-zone
scoping so structures are only destructible during a declared war.

**WorldEdit edits don't update strux structure.** If you use WorldEdit (`//set`,
`//replace`, `//fill`, etc.) on a scanned structure, strux does not automatically
detect the change — blocks could appear or vanish without the physics graph
updating. This is safe (no crashes, no corruption), but the structure may behave
unexpectedly until you re-scan it with `/strux scan`. Use WorldEdit's `//wand` to
select the edited region, then run `/strux scan` to re-register the structure.

**Strux crater writes don't appear in WorldEdit undo.** When strux removes blocks
(from an explosion crater or a collapse), those writes bypass WorldEdit's undo
history — you cannot `//undo` a collapse. This is intentional: strux's destructive
writes are engine operations, not user edits. Use CoreProtect for rollback instead
(`/co rollback u:#strux t:5m`).

---

## Next steps

- [Full config reference](../config/index.md) — every key, default, and one-liner
- [Physics Settings](../config/physics.md)
- [Effects Settings](../config/effects.md)
- [Weather Settings](../config/weather.md)
</content>
