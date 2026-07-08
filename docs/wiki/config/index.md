# Configuration <img class="title-icon" src="../assets/img/system/settings.png" alt="">

<div class="page-aside"><img class="page-mascot" src="../assets/img/mascot/tinkering.png" alt="The Strux Mason"></div>












This is the complete reference for `config.yml`. Every key the plugin actually
reads is listed here with its real default and a one-line summary. The
per-topic pages ([Physics](physics.md), [Effects](effects.md),
[Weather](weather.md)) carry the longer explanations and tuning advice.

---

## Where's the config?

After the plugin runs once:

```
plugins/StructuralIntegrity/config.yml
```

Edit it, then **restart the server** to apply changes. There is no in-game
config-reload command — `/strux reload` does not exist. (The plugin can reload
its physics section internally, but nothing is wired to a command, so a restart
is the supported path.)

---

## How keys are grouped

`config.yml` is organised top to bottom roughly as: top-level physics knobs,
then nested sections (`cascade`, `impact`, `blast`, `fire`, `entity-weight`,
`container-weight`, `temperature-strength`, `weather`, `effects`, `regions`,
`reinforcement`, `economy`, `materials`, `logging`, `persistence`, `recording`,
`metrics-overlay`). The tables below follow that
order. Defaults shown are the values the **code** falls back to when a key is
missing — these are also what the bundled `config.yml` ships, except where noted.

---

## Top-level physics

| Key | Default | What it does | More |
|-----|---------|--------------|------|
| `moment-multiplier` | `1.0` | Scales sideways (cantilever) stress. Higher = cantilevers break sooner. | [Physics](physics.md#moment-and-beams) |
| `beam-moment-reduction` | `1.0` | How much moment stress is removed for beams supported on both ends. `1.0` = beams feel none. | [Physics](physics.md#moment-and-beams) |
| `max-cascade-steps` | `50` | Max blocks collapsed **per tick** in one chain reaction (not a whole-collapse cap). Bundled config ships `20`. | [Physics](physics.md#big-collapses-and-the-cap) |
| `bending-depth-enabled` | `true` | Thicker beams are realistically stronger (section-modulus scaling). Off = thickness doesn't matter. | [Physics](physics.md#moment-and-beams) |
| `visual-update-ticks` | `10` | How often the stress/crack overlays refresh, in ticks. | [Effects](effects.md#stress-particles) |
| `pre-collapse-shake` | `false` (shipped) | Critical blocks visibly wobble before failing. Purely visual. Code fallback is `true`, but the bundled config ships `false`. | [Effects](effects.md#wobble-vs-cracks) |
| `debug-logging` | `false` | Print stress calculations to console (noisy; debugging only). | — |

---

## Cascade resume

| Key | Default | What it does | More |
|-----|---------|--------------|------|
| `cascade.max-resume-ticks` | `200` | Extra ticks a single over-cap collapse may keep finishing for, before a safety warning. | [Physics](physics.md#big-collapses-and-the-cap) |
| `cascade.settle-budget-ms` | `30.0` | Wall-clock budget (ms) one settle pass may spend per tick before pausing and resuming next tick — big structures can never freeze the server. `0` = no budget. | [Physics](physics.md#big-collapses-and-the-cap) |

---

## Kinetic impact (projectiles / rams)

| Key | Default | What it does | More |
|-----|---------|--------------|------|
| `impact.enabled` | `true` | Master switch for projectile/ram structural damage. | [Physics](physics.md#projectile-impacts) |
| `impact.energy-scale` | `1.0` | Multiplier on every impact's computed energy. Higher = quicker sieges. | [Physics](physics.md#projectile-impacts) |
| `impact.penetration-cost` | `4.0` | Energy a blast-resistance-1 block absorbs before it's bored through. Higher = tougher walls. | [Physics](physics.md#projectile-impacts) |
| `impact.damage-scale` | `1.0` | Fraction of absorbed-energy turned into persistent crack damage. Lower = more hits to break a wall. | [Physics](physics.md#projectile-impacts) |
| `impact.max-penetration` | `6` | Hard cap on blocks one impact can bore through, however energetic. | [Physics](physics.md#projectile-impacts) |
| `impact.tick-budget-ms` | `10.0` | Max ms per tick the impact processor spends draining queued hits. | [Physics](physics.md#projectile-impacts) |

---

## Explosions (blast)

| Key | Default | What it does | More |
|-----|---------|--------------|------|
| `blast.tick-budget-ms` | `10.0` | Max ms per tick the blast processor spends settling queued explosions. Bounds how many blasts settle per tick. | [Physics](physics.md#explosions) |
| `blast.async-overload-queries` | `true` | Solve a blast's "which blocks are overloaded?" queries on a background thread, so huge blasts don't stall a single tick. | [Physics](physics.md#explosions) |
| `blast.max-scan-per-tick` | `4096` | How many blast-radius positions one big explosion scans per tick. Spreads a single huge blast's scan across ticks (only one blast scans at a time); the crater is identical to a one-tick solve. | [Physics](physics.md#explosions) |
| `blast.max-crater-removals-per-tick` | `64` | How many crater blocks turn to air per tick. The crater forms a few blocks per tick instead of all at once — a 2000-block crater spreads over ~30 ticks rather than freezing one. Raise for faster craters, lower if a giant blast still stutters. | [Physics](physics.md#explosions) |
| `blast.crater-effect-sample-rate` | `8` | Play the break sound/particle for 1 in this many removed crater blocks. Thousands of effects was a server *and* client spike, so they are sampled; the aggregate "cascade complete" boom still plays. `1` = every block. | [Physics](physics.md#explosions) |
| `blast.fawe-acceleration` | `true` | Let strux use FastAsyncWorldEdit to write the crater's air blocks in one bulk edit when present (strux still does protection, logging, effects and debris itself). The seam + flag are wired now; the FAWE writer ships in a later update, so today strux always uses its own streamed writes and just notes when FAWE is detected. | [Physics](physics.md#explosions) |

---

## Fire (heat) degradation

| Key | Default | What it does | More |
|-----|---------|--------------|------|
| `fire.enabled` | `true` | Master switch for fire weakening structures. | [Physics](physics.md#fire) |
| `fire.damage-per-tick` | `0.0006` | Capacity a fire-resistance-1 block on fire loses per tick. Higher = faster burn-down. | [Physics](physics.md#fire) |
| `fire.radiant-factor` | `0.25` | Damage to a block merely next to fire/lava, as a fraction of the direct rate. Lets fire affect stone/metal. | [Physics](physics.md#fire) |
| `fire.scan-interval-ticks` | `20` | How often the scorch scan runs (20 = once a second). | [Physics](physics.md#fire) |
| `fire.barren-burnout-ticks` | `600` | A flame with no flammable block beside it guts out after this many ticks. `0` = never. | [Physics](physics.md#fire) |
| `fire.tick-budget-ms` | `10.0` | Max ms per tick one scorch pass may spend. | [Physics](physics.md#fire) |

---

## Entity weight (players/mobs on blocks)

| Key | Default | What it does | More |
|-----|---------|--------------|------|
| `entity-weight.enabled` | `true` | Master switch for entity weight. | [Physics](physics.md#entity-weight) |
| `entity-weight.scan-interval-ticks` | `10` | How often to scan for entities on stressed blocks. | [Physics](physics.md#entity-weight) |
| `entity-weight.stress-threshold` | `0.7` | Only blocks at/above this stress fraction are checked. | [Physics](physics.md#entity-weight) |
| `entity-weight.damage-threshold` | `0.5` | …or at/above this damage fraction (either triggers the check). | [Physics](physics.md#entity-weight) |
| `entity-weight.standing.enabled` | `true` | Standing entities add continuous load. | [Physics](physics.md#entity-weight) |
| `entity-weight.standing.mass.<TYPE>` | see page | Per-entity mass; `default` covers unlisted types. | [Physics](physics.md#entity-weight) |
| `entity-weight.fall-impact.enabled` | `true` | Landing from height applies a kinetic spike. | [Physics](physics.md#entity-weight) |
| `entity-weight.fall-impact.energy-scale` | `1.0` | Multiplier on fall-impact energy. | [Physics](physics.md#entity-weight) |
| `entity-weight.fall-impact.min-fall-distance` | `2.0` | Falls shorter than this (blocks) are ignored. | [Physics](physics.md#entity-weight) |

---

## Container weight (heavy storage adds load)

| Key | Default | What it does | More |
|-----|---------|--------------|------|
| `container-weight.enabled` | `true` | Full barrels/chests/etc. add weight to the blocks below them. | [Physics](physics.md#entity-weight) |
| `container-weight.scan-interval-ticks` | `20` | How often to check weak blocks for heavy containers above. | [Physics](physics.md#entity-weight) |
| `container-weight.base-mass` | `1.0` | The empty container's own extra mass. | [Physics](physics.md#entity-weight) |
| `container-weight.content-weight` | `8.0` | Extra load a completely full container adds (scales with fill level). | [Physics](physics.md#entity-weight) |
| `container-weight.stress-threshold` | `0.7` | Only blocks at/above this stress fraction are checked. | [Physics](physics.md#entity-weight) |
| `container-weight.damage-threshold` | `0.5` | …or at/above this damage fraction (either triggers the check). | [Physics](physics.md#entity-weight) |

---

## Temperature strength (heat softens, shock cracks)

Ships **disabled** (`temperature-strength.enabled: false`).

| Key | Default | What it does |
|-----|---------|--------------|
| `temperature-strength.enabled` | `false` | Blocks near lava/fire soften; sudden cooling shocks them. |
| `temperature-strength.comfort-temperature-c` | `20.0` | Baseline temperature — no effect at or below this. |
| `temperature-strength.shock-onset-c` | `150.0` | Temperature drop where thermal shock starts. |
| `temperature-strength.shock-span-c` | `500.0` | Drop size that reaches full shock damage. |
| `temperature-strength.scan-radius` | `5` | How far around heat sources blocks are sampled. |
| `temperature-strength.heat-falloff-radius` | `4` | Distance over which heat fades. |
| `temperature-strength.solid-insulation-blocks` | `3.0` | Solid blocks between source and target damp the heat. |
| `temperature-strength.scan-interval-ticks` | `40` | How often the temperature scan runs. |
| `temperature-strength.tick-budget-ms` | `10.0` | Max ms one scan pass may spend before resuming next scan. |

---

## Weather (rain / thunder / snow)

| Key | Default | What it does | More |
|-----|---------|--------------|------|
| `weather.enabled` | `true` | Master switch for weather effects. | [Weather](weather.md) |
| `weather.scan-interval-ticks` | `40` | How often the weather scan runs. | [Weather](weather.md) |
| `weather.tick-budget-ms` | `10.0` | Max ms one weather scan may spend before resuming next scan. | [Weather](weather.md#big-worlds-dont-freeze) |
| `weather.require-sky-access` | `true` | Only blocks with open sky above are affected. | [Weather](weather.md) |
| `weather.rain.enabled` | `true` | Rain weakens exposed blocks. | [Weather](weather.md#rain) |
| `weather.rain.capacity-multiplier` | `0.95` | Effective capacity in rain (0.95 = 5% weaker). | [Weather](weather.md#rain) |
| `weather.thunder.enabled` | `true` | Thunder weakens + spikes. | [Weather](weather.md#thunder) |
| `weather.thunder.capacity-multiplier` | `0.88` | Effective capacity in a thunderstorm. | [Weather](weather.md#thunder) |
| `weather.thunder.stress-spikes.enabled` | `true` | Random stress spikes during thunder. | [Weather](weather.md#thunder) |
| `weather.thunder.stress-spikes.chance` | `0.02` | Spike chance per scan per stressed block. | [Weather](weather.md#thunder) |
| `weather.thunder.stress-spikes.amount` | `0.15` | Spike size as a fraction of remaining capacity. | [Weather](weather.md#thunder) |
| `weather.snow.enabled` | `true` | Snow accumulates as load in cold biomes. | [Weather](weather.md#snow) |
| `weather.snow.load-per-scan` | `0.005` | Load added per scan as a fraction of capacity. | [Weather](weather.md#snow) |
| `weather.snow.max-load` | `0.3` | Cap on accumulated snow load. | [Weather](weather.md#snow) |
| `weather.snow.decay-per-scan` | `0.002` | How fast snow melts when not snowing. | [Weather](weather.md#snow) |

---

## Effects (visuals, sounds, timing)

| Key | Default | What it does | More |
|-----|---------|--------------|------|
| `effects.collapse-delay-ticks` | `25` | Delay before blocks fall after a player break. Bundled config ships `20`. | [Effects](effects.md#collapse-timing) |
| `effects.explosion-collapse-delay-ticks` | `4` | Delay before blocks fall after an explosion. | [Effects](effects.md#collapse-timing) |
| `effects.max-collapses-per-tick` | `25` | Blocks animated falling per tick (smoothness vs. CPU). Bundled config ships `12`. | [Effects](effects.md#collapse-timing) |
| `effects.screen-shake-enabled` | `true` | Ground shakes near big collapses. | [Effects](effects.md#screen-shake) |
| `effects.screen-shake-threshold` | `15` | Min collapsed blocks before the shake triggers — both the per-batch shakes and the final settling shake. Bundled config ships `8`. | [Effects](effects.md#screen-shake) |
| `effects.screen-shake-radius` | `32.0` | How far the shake reaches (blocks). | [Effects](effects.md#screen-shake) |
| `effects.dust-clouds-enabled` | `true` | Dust during collapses. | [Effects](effects.md#dust) |
| `effects.dust-multiplier` | `1.0` | Dust amount. Bundled config ships `1.5`. | [Effects](effects.md#dust) |
| `effects.dust-wave-enabled` | `true` | Big spreading dust wave after a collapse. | [Effects](effects.md#dust) |
| `effects.dust-wave-max-radius` | `15.0` | How far the dust wave spreads. Bundled config ships `20.0`. | [Effects](effects.md#dust) |
| `effects.stress-caution-threshold` | `0.50` | Stress fraction for yellow particles. | [Effects](effects.md#stress-particles) |
| `effects.stress-danger-threshold` | `0.80` | Stress fraction for orange particles. | [Effects](effects.md#stress-particles) |
| `effects.stress-critical-threshold` | `0.95` | Stress fraction for red particles + flames. | [Effects](effects.md#stress-particles) |
| `effects.stress-particle-size` | `0.7` | Particle size. | [Effects](effects.md#stress-particles) |
| `effects.escalating-stress-audio` | `true` | Stress sound ramps as a block nears failure. | [Effects](effects.md#stress-particles) |
| `effects.cracking-warnings-enabled` | `true` | Cracking **sounds** before collapse. | [Effects](effects.md#cracking-warnings) |
| `effects.cracking-warning-interval` | `5` | Ticks between cracking sounds. Bundled config ships `3`. | [Effects](effects.md#cracking-warnings) |
| `effects.max-cracking-warnings-per-tick` | `6` | Cap on simultaneous cracking sounds. | [Effects](effects.md#cracking-warnings) |
| `effects.impact-feedback` | `true` | Per-hit puff + tick when an arrow damages a block. Visual only. | [Effects](effects.md#impact-feedback) |
| `effects.cracks-enabled` | `true` | Master switch for the crack **texture** overlay. | [Effects](effects.md#cracks) |
| `effects.min-visible-damage` | `0.15` | Min damage fraction before cracks show. | [Effects](effects.md#cracks) |
| `effects.damage-view-distance` | `32.0` | How far crack textures render (blocks). | [Effects](effects.md#cracks) |
| `effects.stress-cracks-enabled` | `true` | Also crack blocks from structural stress, not just damage. | [Effects](effects.md#cracks) |
| `effects.crack-hairline-threshold` | `0.60` | Distress for faint cracks. | [Effects](effects.md#cracks) |
| `effects.crack-cracked-threshold` | `0.78` | Distress for clear cracks. | [Effects](effects.md#cracks) |
| `effects.crack-crumbling-threshold` | `0.90` | Distress for heavy cracks (last warning). | [Effects](effects.md#cracks) |
| `effects.explosion-notify-radius` | `64.0` | How far explosion damage messages reach. | [Effects](effects.md#explosions) |
| `effects.max-debris-per-explosion` | `200` | Max falling debris pieces per explosion. | [Effects](effects.md#explosions) |
| `effects.critical-stress-warning-threshold` | `0.90` | Stress at which placing a block shows an actionbar warning. | [Effects](effects.md#actionbar-warning) |
| `effects.stress-summary-enabled` | `false` | Show the live world-stress readout in the action bar. | [Effects](effects.md#live-stress-summary) |
| `effects.stress-summary-interval-ticks` | `10` | How often the live stress summary refreshes (ticks). | [Effects](effects.md#live-stress-summary) |
| `effects.near-miss-notification-enabled` | `true` | Show "Close call" when a barely-holding block collapses. | [Effects](effects.md#near-miss-notification) |
| `effects.near-miss-threshold` | `0.98` | How close to failing a collapse must have been to count as a near miss. | [Effects](effects.md#near-miss-notification) |
| `effects.rubble-enabled` | `false` | Collapsed blocks leave physical rubble. | [Effects](effects.md#rubble) |
| `effects.return-collapsed-blocks` | `false` | Drop collapsed blocks as items players can recover. | [Effects](effects.md#rubble) |
| `effects.rubble-ground-offset` | `0` | Vertical offset for where rubble settles. | [Effects](effects.md#rubble) |
| `effects.big-collapse-broadcast-enabled` | `true` | Announce big collapses to the whole server in chat. | [Effects](effects.md#collapse-notifications-chat) |
| `effects.big-collapse-broadcast-threshold` | `15` | Only collapses bigger than this many blocks are announced. | [Effects](effects.md#collapse-notifications-chat) |
| `effects.first-collapse-hint-enabled` | `true` | One-time `/engineer` tip on a player's first collapse. | [Effects](effects.md#collapse-notifications-chat) |
| `effects.undermine.backfill-rubble` | `true` | Drift collapse rubble toward the dug-out tunnel (presentation only). | [Effects](effects.md#undermining) |
| `effects.undermine.max-rubble-per-collapse` | `200` | Cap on rubble entities a single collapse may spawn. | [Effects](effects.md#undermining) |

---

## Regions & protection

| Key | Default | What it does | More |
|-----|---------|--------------|------|
| `regions.enabled` | `true` | Master switch for region/world protection. | [Admin](../admin/index.md#protecting-areas) |
| `regions.disabled-worlds` | `[]` | Worlds where physics never runs (case-insensitive). | [Admin](../admin/index.md#protecting-areas) |
| `regions.respect-worldguard` | `true` | Honour the WorldGuard `strux-physics` flag. | [Admin](../admin/index.md#with-worldguard) |
| `regions.war-zone.enabled` | `false` | Restrict destruction to active war zones (siege mode). | [Admin](../admin/index.md#war-zones) |
| `regions.war-zone.providers` | `[towny, factions]` | Which plugin(s) define a war zone. Hooked reflectively. | [Admin](../admin/index.md#war-zones) |
| `regions.war-zone.allow-wilderness` | `false` | Count unclaimed wilderness as a war zone during war. | [Admin](../admin/index.md#war-zones) |

---

## Reinforcement

| Key | Default | What it does |
|-----|---------|--------------|
| `reinforcement.per-item` | `0.5` | Capacity added per Support Beam (0.5 = +50%). |
| `reinforcement.command-add` | `0.5` | Capacity added per `/strux reinforce`. |
| `reinforcement.max-multiplier` | `4.0` | Cap on the reinforcement multiplier. |
| `reinforcement.item-enabled` | `true` | Give the craftable Support Beam item + handler. |
| `reinforcement.recipe-enabled` | `true` | Register the Support Beam crafting recipe. |
| `reinforcement.recipe-yield` | `2` | Beams produced per craft. |

---

## Economy (optional — needs Vault)

All costs default to `0.0` (free). With no Vault/economy plugin, charges are
skipped entirely.

| Key | Default | What it does |
|-----|---------|--------------|
| `economy.reinforce-cost` | `0.0` | Cost to reinforce with a Support Beam item. |
| `economy.reinforce-command-cost` | `0.0` | Cost to reinforce via `/strux reinforce`. |
| `economy.repair-cost` | `0.0` | Cost to repair a block via `/strux repair`. |
| `economy.engineer-cost` | `0.0` | One-off cost to turn on engineer mode. |

---

## Materials

| Key | Default | What it does | More |
|-----|---------|--------------|------|
| `materials.<MATERIAL>.mass` | built-in | Override a block's mass. | [Physics](physics.md#materials) |
| `materials.<MATERIAL>.max-load` | built-in | Override how much load it carries. | [Physics](physics.md#materials) |
| `materials.<MATERIAL>.blast-resistance` | built-in | `>1` shrugs off blasts, `<1` is fragile. | [Physics](physics.md#materials) |
| `materials.<MATERIAL>.fire-resistance` | built-in | Higher = slower to weaken in fire. | [Physics](physics.md#materials) |
| `materials.default.*` | built-in | Same axes, applied to blocks with no explicit entry. | [Physics](physics.md#materials) |

The bundled config ships the `materials:` section commented out, so the built-in
defaults apply until you uncomment and edit it. Unknown block names are skipped
with a console warning.

---

## Collapse logging

| Key | Default | What it does | More |
|-----|---------|--------------|------|
| `logging.coreprotect` | `true` | Log collapse removals to CoreProtect under user `#strux`. | [Admin](../admin/index.md#coreprotect-integration) |

---

## Persistence

| Key | Default | What it does |
|-----|---------|--------------|
| `persistence.enabled` | `true` | Save/load structure data across restarts. |
| `persistence.type` | `file` | `file` (local) or `api` (external store). Unknown values fall back to `file`. |
| `persistence.auto-save-interval` | `300` | Auto-save period in **seconds**. `0` = only save on shutdown. |
| `persistence.api.url` | `http://localhost:8080` | API base URL (only used when `type: api`). |
| `persistence.api.api-key` | `""` | API auth key. |
| `persistence.api.timeout` | `30` | API request timeout in seconds. |

Startup loading happens **in the background**, so the server boots instantly
even with large saved structures. The blocks pop in a moment later, once each
world has finished loading. There is a brief window right after boot where a
just-loaded world is **not yet protected** — break or place there and nothing
collapses until its structures finish loading. That is on purpose: it keeps the
boot fast.

While a world is still loading, strux does **not** auto-save it, so an empty
just-booted world can never overwrite the good data on disk. If a world's load
**fails** (a corrupt or unreadable save file), strux runs without the saved
structures and **disables saving** for the session — again so the incomplete
state can never overwrite the good data on disk.

The shutdown save still waits at most **30 seconds** (not configurable). If it
times out, the server still shuts down; the log says the latest changes may not
have reached the disk.

---

## Event recording

| Key | Default | What it does |
|-----|---------|--------------|
| `recording.auto-record` | `false` | Start recording on world load. **Defaults false** so an old config can't silently start a boot session — see note below. |
| `recording.buffer-size` | `100` | Events buffered before an async flush (also flushes once a second, whichever comes first; minimum 10). |
| `recording.max-sessions` | `20` | Saved sessions kept (oldest deleted first). |
| `recording.include-stress-updates` | `false` | Record stress recalcs too (huge files). |
| `recording.max-events-per-tick` | `50` | Backpressure cap during mass destruction. |
| `recording.async-write` | `true` | Flush recordings off the main thread. |

!!! note "auto-record default was corrected"
    The recording loader defaults `auto-record` to **false**. A config written
    before the `recording` section existed used to fall through to a `true`
    default and silently start a boot recording, which blocked host plugins
    from starting their own scoped recordings. The
    fallback is now `false`, matching both the field default and the bundled
    `config.yml`.

---

## Metrics overlay

| Key | Default | What it does |
|-----|---------|--------------|
| `metrics-overlay.enabled` | `true` | Allow the real-time metrics boss bar (toggled with `/strux metrics`). |
| `metrics-overlay.update-interval-ticks` | `5` | How often the overlay refreshes. |

---

## Learn more

- [Physics Settings](physics.md)
- [Effects Settings](effects.md)
- [Weather Settings](weather.md)
- [Admin Guide](../admin/index.md) — commands, permissions, integrations
</content>
</invoke>
