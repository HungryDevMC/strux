# API Reference

<div class="page-aside left"><img class="page-mascot" src="../assets/img/mascot/tinkering.png" alt="The Strux Mason"></div>












Programmatic access to the Minecraft plugin (`adapter-minecraft`). For the
game-agnostic physics API, see [Engine SDK](engine-sdk.md).

## Getting the API

```java
Plugin plugin = Bukkit.getPluginManager().getPlugin("StructuralIntegrity");
if (plugin instanceof StructuralIntegrityPlugin strux) {
    StructureManager manager = strux.getStructureManager();
    // ...
}
```

The public accessors are `getStructureManager()`, `getMaterialRegistry()`,
`getCollapseGuard()`, and `getRecordingService()` — all documented below. The
plugin exposes a few more accessors for internal wiring; those aren't part of the
public API and may change between versions (see the
[public contract](index.md#the-public-contract)).

## StructureManager

Main entry point for structure operations. It keeps one `StructureGraph` per
world and routes block/explosion/impact events through the core.

### Query a block's stress

```java
double stress = manager.getStress(block);   // stress fraction, 1.0 = at capacity
boolean tracked = manager.isTracked(block);
```

Or go through the graph directly:

```java
StructureGraph graph = manager.getGraph(world);   // null if the world has no graph yet
NodePos pos = StructureManager.toBlockPos(block);  // static; also overloaded for Location
Node node = graph.getNode(pos);
if (node != null) {
    double percent = node.stressPercent();
    double damage = node.damage();
}
```

### Grade a world

```java
StructureReport report = manager.assessWorld(world);
StructureGrade grade = report.grade();   // S / A / B / C / F
```

### Predict a collapse (no simulation)

```java
int wouldFall = manager.predictCollapse(block);  // blocks that lose support if removed
CollapseAtlas atlas = manager.atlasFor(world);   // richer what-if queries
```

### Reinforce / repair

```java
manager.reinforce(block, /* add */ 2.0, /* max */ 5.0);  // returns a Reinforced result
manager.repair(block);                                    // clears accumulated damage
```

### Force recalculation

```java
manager.markDirty(world);
```

> There are **no** custom Bukkit events (`StructureCollapseEvent`,
> `BlockStressChangeEvent`) — collapses are driven internally and surfaced via
> the core `SolverCallback` on `StructureManager.onBlockBroken(block, callback)`,
> not as listenable events. To react to a collapse, pass a callback when you
> trigger one, or watch the world for removed blocks.

## Protection integration (`CollapseGuard`)

`CollapseGuard` gates physics behind the server's protection plugins (WorldGuard,
Towny, Factions via the bundled `ProtectionService`). It is a query surface, not
a registration hook:

```java
CollapseGuard guard = strux.getCollapseGuard();
boolean allowed = guard.physicsAllowed(location);
boolean canRemove = guard.claimRemoval(block);
String why = guard.describeProtection();
```

There is no `addProtectionCheck(...)` — integration is through the protection
services `CollapseGuard` already consults.

## Material overrides

Customize material properties at runtime via `MaterialRegistry`:

```java
MaterialRegistry registry = strux.getMaterialRegistry();

registry.register(Material.STONE, new MaterialSpec(5.0, 150.0));  // heavier, stronger
// or the convenience form:
registry.register(Material.STONE, /* mass */ 5.0, /* maxLoad */ 150.0);

MaterialSpec spec = registry.getSpec(Material.STONE);
boolean ground = registry.isGround(Material.BEDROCK);
```

## Recording service

Start/stop tagged determinism recordings programmatically (e.g. from a gamemode
plugin) without the `/strux record` command:

```java
RecordingService recording = strux.getRecordingService();

RecordingHandle handle = recording.startRecording(
    RecordingRequest.of(RecordingRequest.MATCH, world)
        .label("arena3")
        .verifyOnStop(true)        // replay-verify determinism when stopped (default)
        .build());
// ... match plays out ...
handle.stop();   // idempotent; only stops if still the active session
```

The recorder is **single-session/global**: `startRecording` throws
`IllegalStateException` if a recording is already running. Sessions are written
to `recordings/<tag>/<tag>-<label>-<timestamp>.json`.

## Commands

| Command | Description |
|---------|-------------|
| `/engineer` (aliases `/stress`, `/loadpath`) | Toggle engineer mode — visualize load paths |
| `/strux scan` | Scan a region into the structure graph |
| `/strux wand` / `/strux pos1` / `/strux pos2` | Select a region |
| `/strux grade` | Grade the structure you're looking at / selected |
| `/strux predict` | Predict the collapse from removing a block |
| `/strux reinforce` | Reinforce a block |
| `/strux repair` | Repair a damaged block |
| `/strux beam` | Obtain Support Beam items (admin) |
| `/strux perf` | Show performance counters (solver + per-task timings) |
| `/strux demo` | Run a built-in demo |
| `/strux record …` | Start/stop/verify a determinism recording |

Run programmatically with `Bukkit.dispatchCommand(sender, "strux grade")`.

## Permissions

Permissions are namespaced `structuralintegrity.*` (matching `plugin.yml`):

| Permission | Default | Description |
|------------|---------|-------------|
| `structuralintegrity.engineer` | true | Use engineer mode |
| `structuralintegrity.reinforce` | true | `/strux reinforce` + Support Beams |
| `structuralintegrity.repair` | true | `/strux repair` |
| `structuralintegrity.admin` | op | Admin actions (e.g. `/strux beam`) |

## PlaceholderAPI

If PlaceholderAPI is installed, the `strux` expansion exposes the world's
structure grade (the placeholders are world-grade stats, not per-block):

| Placeholder | Description |
|-------------|-------------|
| `%strux_grade%` | Overall grade: S / A / B / C / F |
| `%strux_peak_stress%` | Highest stress %, whole number (e.g. 87) |
| `%strux_avg_stress%` | Average stress % |
| `%strux_overloaded%` | Count of overloaded blocks |
| `%strux_tracked%` | Count of load-bearing blocks assessed |
