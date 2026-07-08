# adapter-minecraft

Paper/Spigot plugin that wires the strux physics engine into Minecraft. Compiled against Paper 1.21 API, tested against MockBukkit.

## Build and install

```bash
./gradlew :adapter-minecraft:shadowJar
# → adapter-minecraft/build/libs/adapter-minecraft-0.1.0-SNAPSHOT-all.jar
```

Drop the shaded jar into your server's `plugins/` folder and restart.

Quick local iteration via the bundled Docker Paper server: `docker compose -f docker/docker-compose.yml up` (rebuild + restart on plugin changes).

## What it binds to core

| Bukkit concept                             | strux core equivalent                                                 |
| ------------------------------------------ | --------------------------------------------------------------------- |
| `org.bukkit.block.Block`                   | `dev.gesp.structural.model.Node` (via `StructureManager.toBlockPos`)  |
| `Material` → mass / maxLoad                | `MaterialRegistry` → `MaterialSpec`                                   |
| `BlockBreakEvent` / `BlockPlaceEvent`      | `CascadeEngine.cascade(...)` / `StressSolver.solveProgressively(...)` |
| `EntityExplodeEvent` / `BlockExplodeEvent` | `StruxExplosionEngine.process(...)`                                   |
| Per-world `StructureGraph`                 | `Map<UUID, StructureGraph>` in `StructureManager`                     |

## Optional integrations

All three are _soft-dependencies_ — the plugin works without them, with reduced features when missing:

* **WorldGuard** — region/world physics gating via a `strux-physics` custom flag. Disabled cleanly if WorldGuard isn't on the classpath.
* **WorldEdit** — schematic-based prefab support.
* **CoreProtect** — every cascade removal is logged for inspect/rollback.

See [`docker/docker-compose.yml`](https://gitlab.com/gespstudios/products/strux/-/blob/main/docker/docker-compose.yml) for the dev-server mount layout and which optional plugins it ships with.

## Notable subsystems

* `listener/DelayedCollapseManager` — spreads cascade _visuals_ over ticks so a 500-block collapse doesn't freeze the server.
* `listener/ImpactProcessor` — queues projectile hits and settles them under a per-tick wall-clock budget (`impact.tick-budget-ms`), so a volley can't freeze a single tick.
* `visual/CollapseEffects`, `PreCollapseShake`, `StressVisualizer`, `DamageVisualizer` — the "game feel" layer (particles, sounds, screen shake, dust). Configured via `effects.yml`.
* `persistence/ApiPersistenceAdapter` — HTTP-backed structure persistence using shaded Jackson. Lives here (not in core) because core has a zero-runtime-deps rule.
* `protect/CollapseGuard` — the single chokepoint that decides whether a block may be physics-removed, consulting WorldGuard regions + world disabled-list + CoreProtect logging.

## Configuration

| File            | Purpose                                                                            |
| --------------- | ---------------------------------------------------------------------------------- |
| `config.yml`    | Physics (mass, maxLoad, cascade caps) + persistence (file vs API) + region toggles |
| `effects.yml`   | Visual/audio knobs: particle density, sound volumes, screen shake                  |
| `materials.yml` | Mass + maxLoad per Bukkit `Material`                                               |

Auto-generated on first run with safe defaults.
