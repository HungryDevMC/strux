# Local test server (Docker)

A Paper 1.21.4 server that loads the `adapter-minecraft` plugin
(StructuralIntegrity), for validating changes in a live environment.

## Runtime

Docker Desktop isn't required — this repo uses [Colima](https://github.com/abiosoft/colima)
as a headless Docker runtime:

```bash
brew install colima docker docker-compose
colima start --cpu 2 --memory 4
```

## Build the plugin, then start the server

```bash
# from the repo root
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :adapter-minecraft:shadowJar

# from this folder
docker compose up -d
docker compose logs -f mc      # watch until "Done (...)! For help, type help"
```

Connect a Minecraft 1.21.4 client to `localhost:25565` (offline mode).

## Use it

```
/strux demo      # build a demo fort in one command
/engineer        # toggle stress-visualisation mode
/strux scan      # structural grade of what you're looking at
/strux perf      # live solve-time / safety-cap readout
```

Break a support or set off the demo TNT and watch the cascade. The strux
config the server actually uses lives in
`data/plugins/StructuralIntegrity/config.yml` (tuned to a snappy playtest profile).

## Region protection (WorldGuard + CoreProtect)

The compose file auto-downloads WorldGuard and CoreProtect, so you can test the
adoption-safety features end to end. Op yourself first (see below), then in-game:

```
# Protect a region from collapses
//wand                                   # WorldEdit wand; left/right-click two corners
/rg define spawn                         # define the region from your selection
/rg flag spawn strux-physics deny        # physics OFF inside "spawn"
#  → build a tower in spawn, break a support: it will NOT collapse.
#  → trigger a collapse just outside that propagates in: blocks inside stay up.

/rg flag spawn strux-physics allow       # back to normal (allow is the default)
```

```
# Inspect / roll back a collapse (CoreProtect)
/co inspect                              # then click a collapsed block → shows "#strux"
/co rollback u:#strux t:5m r:#global     # restore everything strux collapsed in the last 5 min
```

Per-world disable lives in `data/plugins/StructuralIntegrity/config.yml` under
`regions.disabled-worlds` (and the master switch `regions.enabled`).

## Op yourself / run console commands

```bash
docker exec strux-mc rcon-cli op <your_username>
docker exec strux-mc rcon-cli "say hello"
```

## Update after a code change

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :adapter-minecraft:shadowJar
docker compose restart mc
```

## Stop / reset

```bash
docker compose down            # stop
rm -rf data                    # wipe the world + configs
```
