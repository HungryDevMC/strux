# Building Tips

<div class="page-aside"><img class="page-mascot" src="../assets/img/mascot/thumbs-up.png" alt="The Strux Mason"></div>












Want to build things that DON'T fall down? Here's how.

---

## The Golden Rules

### Rule 1: Light on Top, Heavy on Bottom

```
GOOD:                    BAD:

  [Wool]  ← Light          [Iron]  ← Heavy!
  [Wood]                   [Stone]
  [Stone]                  [Wood]
  [Iron]  ← Strong         [Wool]  ← Weak!
  ══════                   ══════

  Pyramid = Strong!        Upside down = 💥
```

### Rule 2: Support Your Bridges

A bridge anchored to solid ground at **both** ends is far stronger than one sticking out from one side.

```
GOOD:                    BAD:

[W]─[1]─[2]─[3]─[W]     [W]─[1]─[2]─[3]─[4]─[5]─[6]...
   Both ends share          One side does all the work = 💥
```

### Rule 3: Build on Solid Ground

Your build needs to reach the **ground** to stand up. The ground is what holds all the
weight, so it never breaks.

On most servers, the ground is bedrock or the very bottom of the world. But in a survival
world you build on dirt and stone, far above bedrock!

If your server turns on **foundations**, you don't need bedrock. A block counts as standing
on the ground when it has enough solid natural blocks (dirt, stone, sand, gravel, ore…)
straight below it — like a foundation reaching down. Ask your admin if it's on, and how deep
the ground needs to be.

Two things to know:

- The column below must be **unbroken**. A cave or air gap under your build stops it from
  counting as grounded.
- Only **natural** blocks count — dirt, stone, and the like. Your own placed blocks (planks,
  glass, wool, bricks) don't, so a hollow tower can't ground itself off its own walls.

Your admin may also set a **foundation block** — a special block (like reinforced deepslate)
that anchors your build when you place it on solid ground. Use it as the base of a big build.

### Rule 4: Watch for Cracks

```
[ ─BLOCK─ ]  ← Hairline cracks = fix it soon
[ ╱BLOCK╲ ]  ← Clear cracks = fix it now
[ ▓BLOCK▓ ]  ← Crumbling = RUN!
```

---

## How to Make Blocks Stronger

### Reinforcement

Use a **Support Beam** to make a block stronger.

```
Before:              After one beam:

[STONE]              [STONE+]
Holds 100            Holds 150  (+50%)
```

Each beam adds +50% load capacity, up to a cap your admin sets (default up to +300%).

**Two ways to reinforce:**

- **Support Beam item** — craft it (3 iron ingots in a column), then right-click a tracked block.
- **`/strux reinforce`** — look at a tracked block and run the command.

!!! note "Scan first"
    You can only reinforce a block that's already part of a tracked structure. Run `/strux scan` over the build first. Foundation blocks are already immovable, so you can't reinforce those.

### Repair Damaged Blocks

If a block has cracks, clear the damage:

```
/strux repair
```

Look at the cracked block and run the command. Full strength restored.

---

## Check Before You Break

Want to know what a block is holding up before you touch it?

```
/strux predict
```

Look at a block and run it — Strux tells you how many blocks would lose support if it broke. Handy for finding the keystone of a build (or an enemy's).

`/strux grade` gives the whole world's builds a letter grade (S is best, F is worst).

---

## Good Building Patterns

### The Pyramid

Wide at the bottom, narrow at the top. Weight spreads out. Very stable.

```
        [A]
       [BBB]
      [CCCCC]
     [DDDDDDD]
    ═══════════
```

### Pillars for Wide Spaces

Building a big room? Add pillars so the roof isn't one long unsupported span.

```
[ROOF][ROOF][ROOF][ROOF][ROOF]
   |           |
[PILLAR]   [PILLAR]
   |           |
═══════════════════════════════
```

### Arches

The curve of an arch shares the weight down both sides — stronger than a flat bridge.

```
     [TOP]
    /     \
  [SIDE] [SIDE]
    |       |
 [BASE]  [BASE]
```

---

## DO and DON'T

### ✅ DO

- Use strong materials at the bottom.
- Add support pillars for wide spaces.
- Reinforce important blocks with Support Beams.
- Build sloped roofs (snow slides off).
- Fix cracks when you see them.

### ❌ DON'T

- Put heavy stuff on weak stuff.
- Build long bridges with no support.
- Use glass for walls (windows only).
- Ignore cracks.
- Jump on damaged floors.

---

## Fun Experiments

Scan each build first, or nothing will collapse!

1. **How high?** Build a stone tower. How tall before the base gives way?
2. **How far?** Build a bridge from a wall. How far with no support?
3. **Boom test.** Build a house, blow up one corner with TNT, watch what happens.
4. **Jump test.** Damage a floor with TNT, then jump on it from different heights.
5. **Snow watch.** Build a flat roof in a snowy biome and wait.

---

## Remember!

```
1. Heavy on top of light = BAD
2. Long bridges need supports
3. Cracks = danger
4. Don't jump on damaged floors
5. When in doubt, add more pillars!
```

Happy building!
