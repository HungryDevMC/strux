# Quick Start

<div class="page-aside"><img class="page-mascot" src="../assets/img/mascot/pointing.png" alt="The Strux Mason"></div>












Let's make some buildings fall down!

---

## Your First Collapse

### Step 1: Build a Tower

Build a tall tower out of stone. Make it about 10 blocks high.

```
   [STONE]  ← Top
   [STONE]
   [STONE]
   [STONE]
   [STONE]
   [STONE]
   [STONE]
   [STONE]
   [STONE]
   [STONE]  ← Bottom
   ════════
   Ground
```

### Step 2: Scan It

Stand back and run:

```
/strux scan
```

This registers everything in your selection (or your WorldEdit selection) for physics. Blocks that haven't been scanned won't collapse.

!!! tip "Pick your corners"
    Run `/strux wand` to get the scanner. Left-click one corner, right-click the opposite corner, then run `/strux scan`.

### Step 3: Break the Bottom

Break one of the bottom blocks.

### Step 4: Watch!

**CRASH!** Everything falls down!

---

## See the Stress

Type this command:

```
/engineer
```

Now look at your buildings. Colored dust shows how hard each block is working:

| Color | What It Means |
|-------|---------------|
| 🟢 Green | No stress (under 50%) |
| 🟡 Yellow | Working a bit (50%+) |
| 🟠 Orange | Working hard (80%+) |
| 🔴 Red | About to break! (95%+) |
| 🔵 Cyan | Foundation block (never breaks) |

Type `/engineer` again to turn it off.

---

## Fun Experiments

Scan each build first, or nothing will collapse!

### Experiment 1: The Bridge

Build a bridge sticking out from a wall:

```
[WALL]──[1]──[2]──[3]──[4]──[5]──???
```

How far can you go before it falls?

### Experiment 2: Heavy on Light

Put heavy blocks on weak blocks:

```
   [IRON]
   [IRON]
   [IRON]
   [GLASS]  ← Uh oh!
   ════════
```

What happens?

### Experiment 3: TNT Time

1. Build a small house and scan it.
2. Put TNT at one corner.
3. Light it and run!
4. Watch the chain reaction!

---

## Useful Commands

| Command | What It Does |
|---------|--------------|
| `/strux scan` | Give the selected region structural integrity |
| `/strux wand` | Get the corner-selection scanner |
| `/engineer` | Show stress colors |
| `/strux predict` | See how much falls if you break the block you're looking at |
| `/strux grade` | Structural grade of this world's builds |
| `/strux` | List all commands |

---

## What Your Server Can Turn On

Strux has no paid tiers — every feature is a config setting your server admin controls. Some are on by default; some are off until enabled.

| Feature | Default | Notes |
|---------|:-------:|-------|
| Collapse physics | On | The core mechanic |
| `/engineer` overlay | On | Stress colors |
| Fire damage | On | Burns flammable blocks, weakens others |
| Weather effects | On | Rain, thunder, snow |
| Projectile/ram damage | On | Arrows, fireballs, etc. |
| Reinforcement | On | Support Beam item + `/strux reinforce` |
| War-zone scoping | Off | Siege-style mode — destruction only during an active war (needs Towny or Factions) |

---

## Next Steps

- [How Buildings Work](../player-guide/index.md) — Learn the physics
- [Building Tips](../player-guide/tips.md) — Build better
- [Admin Guide](../admin/index.md) — Server settings
