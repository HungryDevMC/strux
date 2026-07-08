# BlastContext API Analysis

> **HISTORICAL — superseded by [`blast-design.md`](./blast-design.md) (noted 2026-06-05).**
> This is an early design scratchpad. The approach it explores — adding blast
> energy into the node *stress* field, an `applyStress` flag, a
> `StruxExplosionHook` interface, and reusing `CascadeResult` for explosions —
> was **not** the one built. It was rejected as a category error (blast
> overpressure is not gravity load) and because the solver's `resetStress()`
> would wipe any externally-added stress. The shipped design instead writes
> persistent `damage`, returns a `BlastResult`, and uses `BlastCallback`. None
> of the types proposed below (`StruxExplosionHook`, `StressApplicationResult`,
> `Node.addStress`, `applyStress`) exist in the codebase. Kept for the design
> rationale; do not treat its code as current API.

## Overview

This document analyzes how the BlastContext API specification fits into the existing Strux structural physics engine.

---

## Current System vs. BlastContext API

### What Exists Now

The current flow for **player block breaks**:

```
Player breaks block → BlockBreakListener → cascade(graph, triggerPos) → CascadeEngine → blocks collapse
```

The system currently has:
- `CascadeEngine` - handles chain-reaction collapses
- `StressSolver` - calculates stress on nodes
- `SolverCallback` - observer for cascade events
- **NO explosion handling** - only single-block triggers

### What BlastContext Adds

The BlastContext API introduces a **multi-block stress damage model** before cascade:

```
Explosion → BlastContext → StruxExplosionEngine → stress damage to N blocks → cascade on overloaded → result
```

Key difference: Instead of removing a single block and cascading, you're **applying stress damage** to multiple blocks simultaneously, then cascading only those that exceed their threshold.

---

## Happy Path Walkthrough

### Step 1: Event Capture (Adapter Layer)

```java
// In adapter-minecraft, a new ExplosionListener would capture this
@EventHandler
public void onExplosion(EntityExplodeEvent event) {
    BlockPos center = toNodePos(event.getLocation());

    BlastContext ctx = BlastContext.builder()
        .center(center)
        .power(4.0f)                    // TNT power
        .shape(BlastShape.SPHERE)       // Radial expansion
        .cause(ExplosionCause.TNT)
        .falloff(StressFalloff.LINEAR)  // Damage drops with distance
        .applyStress(true)              // Use stress model, don't instant-kill
        .build();

    CascadeResult result = explosionEngine.process(ctx);

    // Apply results to world...
}
```

### Step 2: Hook Interception (onBlastReceived)

```java
// Registered hooks fire BEFORE any physics runs
boolean proceed = true;
for (StruxExplosionHook hook : hooks) {
    if (!hook.onBlastReceived(ctx)) {
        proceed = false;  // Hook cancelled the explosion
        break;
    }
}
if (!proceed) return CascadeResult.empty();
```

Hooks can **modify** `ctx` here (e.g., double power during raid hours) or **cancel** entirely.

### Step 3: Calculate Affected Blocks

Based on `power`, `shape`, and `falloff`:

```java
// For SPHERE with LINEAR falloff:
float radius = power * 1.5f;  // TNT: 4.0 × 1.5 = 6 blocks

Set<NodePos> affected = new HashSet<>();
for (NodePos pos : graph.getAllNodes()) {
    double distance = center.distanceTo(pos);
    if (distance <= radius) {
        affected.add(pos);
    }
}
```

### Step 4: Apply Stress Damage

This is the **critical difference** from current system. Instead of removing blocks, you're adding stress:

```java
Map<NodePos, Float> stressDamage = new HashMap<>();

for (NodePos pos : affected) {
    float distance = center.distanceTo(pos);
    float stressDamageAmount;

    switch (falloff) {
        case LINEAR:
            // Full damage at center, zero at edge
            stressDamageAmount = power * (1 - distance / radius);
            break;
        case QUADRATIC:
            // Gentler at edges
            stressDamageAmount = power * (1 - (distance/radius) * (distance/radius));
            break;
        case FLAT:
            // Equal damage everywhere
            stressDamageAmount = power;
            break;
    }

    Node node = graph.getNode(pos);
    node.addStress(stressDamageAmount);  // NEW: accumulate stress damage
    stressDamage.put(pos, node.stressPercent());
}
```

### Step 5: Partition Results

After stress is applied, categorize blocks:

```java
List<NodePos> damaged = new ArrayList<>();      // Took damage but survived
List<NodePos> overloaded = new ArrayList<>();   // Exceeded threshold → will cascade

for (NodePos pos : affected) {
    Node node = graph.getNode(pos);
    if (node.isOverloaded()) {
        overloaded.add(pos);
    } else if (node.stressPercent() > previousStress.get(pos)) {
        damaged.add(pos);
    }
}

StressApplicationResult stressResult = new StressApplicationResult(damaged, overloaded, stressMap, ctx);
```

### Step 6: Hook Notification (onStressApplied)

```java
for (StruxExplosionHook hook : hooks) {
    hook.onStressApplied(stressResult);
    // Can't cancel here — damage is done, cascade is inevitable
}

// Also fire individual damage notifications
for (NodePos pos : damaged) {
    for (StruxExplosionHook hook : hooks) {
        hook.onBlockDamaged(pos, graph.getNode(pos).stressPercent());
    }
}
```

### Step 7: Cascade Overloaded Blocks

The existing `CascadeEngine` takes over, but starting from **multiple** overloaded blocks:

```java
// Similar to current code, but seeded with multiple trigger points
List<NodePos> collapsed = new ArrayList<>();
int step = 0;

while (!overloaded.isEmpty()) {
    NodePos breaking = selectMostOverloaded(overloaded);

    // Fire hook — can halt cascade at any step
    CascadeStepEvent stepEvent = new CascadeStepEvent(breaking, step, ...);
    boolean continueC = true;
    for (StruxExplosionHook hook : hooks) {
        if (!hook.onCascadeStep(stepEvent)) {
            continueC = false;
            break;
        }
    }
    if (!continueC) break;

    // Remove block from graph
    graph.removeNode(breaking);
    collapsed.add(breaking);
    step++;

    // Recalculate stress — may create new overloaded blocks
    solver.solveAll(graph);
    overloaded = findOverloadedBlocks(graph);
}
```

### Step 8: Complete and Return

```java
CascadeResult result = new CascadeResult(
    collapsed,                    // All blocks that fell
    damaged,                      // Stressed but surviving
    graph.getStressMap(),         // Final stress state
    step,                         // Total cascade steps
    System.currentTimeMillis() - startTime,
    checkVaultBreach(collapsed),
    ctx
);

// Final hook notification
for (StruxExplosionHook hook : hooks) {
    hook.onCascadeComplete(result);
}

return result;
```

---

## Mapping to Existing Code

| BlastContext API | Current Strux Component | Notes |
|------------------|------------------------|-------|
| `NodePos center` | `NodePos` | Direct mapping |
| `StruxExplosionEngine` | `CascadeEngine` | Wraps/extends it |
| `CascadeResult` | `CascadeResult` | Add more fields |
| `SolverCallback` | Becomes `StruxExplosionHook` | Richer interface |
| `StressSolver` | `StressSolver` | Add stress damage accumulation |
| `BlastShape`, `StressFalloff` | **NEW** | Enums for configuration |

---

## What Needs to Be Built

1. **BlastContext** builder class (core)
2. **StruxExplosionEngine** that wraps CascadeEngine (core)
3. **StruxExplosionHook** interface (core)
4. **StressApplicationResult**, **CascadeStepEvent** DTOs (core)
5. **Stress damage accumulation** in Node (modify existing)
6. **ExplosionListener** in adapter-minecraft
7. **StruxApi** facade class

---

## Key Design Insight

The spec uses **stress damage, not instant removal**. This means:

- A weak explosion might crack blocks (raise stress to 80%) without breaking them
- A second smaller explosion on the same structure finishes the job
- Blocks accumulate damage over time — strategic multi-hit gameplay
- `applyStress=false` bypasses this for instant-kill scenarios
