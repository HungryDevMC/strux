# Integrations <img class="title-icon" src="../assets/img/system/settings.png" alt="">

<div class="page-aside"><img class="page-mascot" src="../assets/img/mascot/tinkering.png" alt="The Strux Mason"></div>

Strux plays nicely with the plugins you already run. Every one of these is **optional** and
**auto-detected** — install it and strux uses it; leave it out and strux runs fine without it.

## PlaceholderAPI — structural stats anywhere

With **PlaceholderAPI** installed, strux exposes your structure's live health for
scoreboards, holograms, chat, or TAB. The numbers are for the **world the player is in**.

| Placeholder | Shows |
|---|---|
| `%strux_grade%` | The structural grade — `S`, `A`, `B`, `C`, or `F` |
| `%strux_peak_stress%` | The highest stress on any block, as a whole-number percent (e.g. `87`) |
| `%strux_avg_stress%` | The average stress across load-bearing blocks, as a percent |
| `%strux_overloaded%` | How many blocks are overloaded right now |
| `%strux_tracked%` | How many load-bearing blocks were checked |

Each value is cached for about a second per world, so scoreboards and holograms can poll as
often as they like without slowing the server down.

## WorldGuard — physics on or off per region

With **WorldGuard** installed, strux registers a region flag called **`strux-physics`**. Use
it to make safe zones collapse-proof:

```
/rg flag spawn strux-physics deny     # spawn can never collapse
/rg flag arena strux-physics allow    # physics on (this is the default)
```

The flag defaults to **allow**, so physics is on everywhere until you `deny` it somewhere.

## FastAsyncWorldEdit / WorldEdit — faster big explosions

When a large explosion craters a structure, strux has to turn a lot of blocks to air. If
**FastAsyncWorldEdit** (or plain **WorldEdit**) is installed, strux does that in one bulk
async edit instead of block-by-block — so big blasts land faster.

- It's **on by default** and needs no setup. Strux detects WorldEdit/FAWE at startup and
  logs which writer it picked — look for `Crater block writer: …` in the console.
- Turn it off with `blast.fawe-acceleration: false` in `config.yml` to force the built-in
  streamed writer.
- With no WorldEdit/FAWE installed, strux uses the streamed writer automatically — same
  result, just not as fast.

## CoreProtect — inspect & roll back collapses

With **CoreProtect** installed, strux logs every block a collapse removes under the special
user **`#strux`**. That keeps physics damage separate from player edits, so you can:

```
/co inspect                  # then click a block to see if a collapse took it
/co rollback u:#strux        # undo strux's collapses (leaves player builds alone)
/co rollback u:#strux t:1h   # …just the last hour, for example
```

No setup needed — strux detects CoreProtect at startup (CoreProtect API v9+).

!!! mason "The Mason says…"
    Mix and match — none of these are required. Strux checks what's installed when the
    server starts and quietly uses whatever's there.
