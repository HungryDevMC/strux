# How Buildings Work in This Plugin
### A Simple Guide for Everyone!

---

## What Does This Plugin Do?

In regular Minecraft, you can build floating houses in the sky. Blocks just... stay there!

```
     [HOUSE]     <-- This floats in vanilla Minecraft
        |
      (AIR)
      (AIR)
      (AIR)
    ~~~~~~~~
     GROUND
```

**This plugin changes that!** Now blocks act like real building materials. They have weight, and they can break if you put too much on them.

---

## The Basics: Weight and Strength

Every block has two important numbers:

### 1. Weight (Mass)
How heavy the block is. Heavy blocks push down on whatever is below them.

```
   [IRON]  ← Very heavy! (5.0)
   [STONE] ← Pretty heavy (3.0)
   [WOOD]  ← Light (1.0)
   [WOOL]  ← Very light (0.3)
```

### 2. Strength (Max Load)
How much weight a block can hold before it breaks.

```
   [IRON]  ← Super strong! Can hold a LOT
   [STONE] ← Strong
   [WOOD]  ← Medium strength
   [GLASS] ← Very weak - breaks easily!
```

**The Rule:** If you stack too much weight on a weak block, it breaks!

---

## Stress: Is My Block About to Break?

"Stress" is how hard a block is working to hold everything up. Think of it like this:

```
Imagine holding a book:        Easy! No problem.        [  10%  ]

Imagine holding 5 books:       Getting heavy...         [  50%  ]

Imagine holding 20 books:      SO HEAVY! Arms shaking!  [  95%  ]

Imagine holding 30 books:      *CRASH* You drop them!   [ 100%+ ]
```

When a block hits 100% stress, it BREAKS and falls!

---

## What Happens When Something Breaks?

When one block breaks, everything it was holding up might fall too! This is called a **cascade**.

```
BEFORE:                    AFTER:

   [D]                        [D] 💥 Falls!
    |                          |
   [C]                        [C] 💥 Falls!
    |                          |
   [B]  ← You break this      (air)
    |                          |
   [A]                        [A]  ← Still standing
    |                          |
  [GND]                      [GND]
```

Block B was holding up C and D. When you break B, they have nothing to stand on, so they fall!

---

## Cantilevers: Sideways Building

A "cantilever" is when blocks stick out sideways with no support underneath.

```
   [GROUND]──[1]──[2]──[3]──[4]──[5]
              ↑
         This block is working VERY hard!
         It's holding up blocks 2, 3, 4, and 5!
```

The longer your bridge sticks out, the more stress on the anchor block. Build too far and... SNAP!

**Pro Tip:** Support from both ends is much stronger:

```
   [GROUND]──[1]──[2]──[3]──[4]──[5]──[GROUND]
              ↑                   ↑
         Both sides share the work = MUCH stronger!
```

---

## Ways Buildings Get Damaged

### 1. Explosions (TNT, Creepers)

BOOM! Explosions blast a crater and send shockwaves through the structure.

```
                💥 BOOM!
               /  |  \
           crack crack crack
             \   |   /
              WEAKEN
```

- Blocks near the blast get damaged
- Damaged blocks are weaker
- A weakened wall might collapse on its own!

### 2. Fire

Fire slowly cooks your building!

```
   🔥🔥🔥
   [WOOD]  ← Burns fast!
   [STONE] ← Heats up slowly
   [IRON]  ← Takes forever to heat
```

- **Burning blocks** (wood on fire) get damaged quickly
- **Nearby blocks** heat up slowly (even stone!)
- Water puts out fire and saves your building!

### 3. Projectiles (Arrows, Cannonballs)

Fast, heavy things hit hard!

```
   🏹 ───────────→ *CRACK*  [WALL]

   ● ════════════→ *SMASH* [WALL] *CRACK* [WALL]
   ^ cannonball punches through!
```

The formula is **Energy = ½ × weight × speed × speed**

So a heavy cannonball going fast does WAY more damage than a slow arrow!

### 4. Entities Standing on Weak Blocks (NEW!)

When blocks are already damaged or stressed, having someone stand on them adds more weight!

```
   🧍 Player (weight: 2.0)
   ┃
   [CRACKED FLOOR]  ← Already at 80% stress

   80% + player weight = TOO MUCH!

   💥 COLLAPSE!
```

But don't worry - healthy blocks don't care if you stand on them. Only WEAK blocks feel your weight.

### 5. Falling From Height (NEW!)

Jumping from high up onto a weak floor? That's a LOT of force!

```
   🧍 Player
    ↓
    ↓  Falling 10 blocks...
    ↓
   💥 SLAM!
   [WEAK FLOOR]

   Impact energy = ½ × weight × speed × speed
   Higher fall = MUCH more impact!
```

**Examples:**
- Jumping 3 blocks onto a cracked floor? It might break!
- Walking across? Totally fine.
- Falling 20 blocks onto a damaged bridge? DEFINITELY breaking!

### 6. Weather (NEW!)

Mother Nature can hurt your buildings too!

**RAIN** ☔
```
   ☔ ☔ ☔ ☔ ☔
   [WET ROOF]  ← 5% weaker when wet!
```
Exposed blocks are slightly weaker in rain.

**THUNDER** ⛈️
```
   ⚡ CRACK!  ⚡
   [RATTLED BLOCKS]  ← 12% weaker!

   Sometimes lightning rattles the structure
   causing random stress spikes!
```

**SNOW** ❄️
```
   ❄️ ❄️ ❄️ ❄️ ❄️
   [SNOWY ROOF]  ← Snow piles up over time!

   Heavy snow = extra weight on your roof
   Clean off the snow or it might collapse!
```

Only blocks that can see the sky are affected. Inside is safe!

---

## Protecting Your Buildings

### 1. Reinforcement

You can make blocks stronger! Use a **Support Beam** item:

```
   Before:              After:
   [STONE]              [STONE+]
   Holds 100            Holds 150! (+50%)
```

Craft Support Beams and right-click blocks to reinforce them.

### 2. Repair

Damaged blocks can be fixed! Use `/strux repair` on a cracked block:

```
   Before:              After:
   [CRACKED]            [FIXED!]
   80% damage           0% damage
```

### 3. Good Building Practices

**DO:**
- ✅ Use strong materials at the bottom (stone, iron)
- ✅ Add support pillars for wide spans
- ✅ Reinforce important blocks
- ✅ Build roofs with slopes (snow slides off!)

**DON'T:**
- ❌ Stack heavy blocks on weak ones
- ❌ Build super long bridges with no support
- ❌ Use glass as load-bearing walls
- ❌ Ignore cracks! They mean danger!

---

## Warning Signs: How to Know When Something's Wrong

The plugin shows you when blocks are stressed!

### Cracks
```
   [  BLOCK  ]     Healthy - no cracks

   [ ─BLOCK─ ]     Hairline cracks (60%+ stress)

   [ ╱BLOCK╲ ]     Cracked (78%+ stress)

   [ ▓BLOCK▓ ]     Crumbling! (90%+ stress) - RUN!
```

### Sounds
- **Creaking**: Block is under strain
- **Cracking**: Block is about to fail
- **LOUD CRACK**: It's breaking NOW!

### Particles
- **Yellow dust**: Getting stressed
- **Orange dust**: Danger zone!
- **Red dust + flames**: About to collapse!

---

## The Stress Formula (For Curious Kids!)

Here's how the plugin decides if a block breaks:

```
Total Stress = Weight Pushing Down + Pulling Force from Sideways Blocks

If Total Stress > Block's Strength → BREAK!
```

**Simple Example:**

```
   [STONE C]
      ↓
   [STONE B]
      ↓
   [STONE A]  ← Holding up B and C
      |
   [GROUND]

Stone A feels:
- Its own weight (3.0)
- Stone B's weight (3.0)
- Stone C's weight (3.0)
Total: 9.0

Stone's strength: 100.0
9.0 / 100.0 = 9% stress ← Easy!
```

But stack 50 stones? That's 150.0 weight on a block that can only hold 100.0 = BREAK!

---

## Fun Experiments to Try!

### Experiment 1: The Tower Test
Build a tall tower of stone. How high can you go before the bottom breaks?

### Experiment 2: The Bridge Challenge
Build a bridge across a gap. How far can you span with no supports?

### Experiment 3: TNT Demolition
Build a building, then blow up one corner. Watch the cascade!

### Experiment 4: The Weight Test
Damage a floor block with TNT, then jump on it from increasing heights!

### Experiment 5: Weather Watch
Build a flat roof in a snowy biome and wait. Will it collapse under the snow?

---

## Quick Reference Card

| Material | Weight | Strength | Good For |
|----------|--------|----------|----------|
| Bedrock | ∞ | ∞ | Foundation (unbreakable!) |
| Iron Block | 5.0 | 150 | Strong pillars, supports |
| Stone | 3.0 | 100 | Walls, foundations |
| Wood | 1.0 | 40 | Light structures, roofs |
| Glass | 0.5 | 5 | Windows ONLY! |
| Wool | 0.3 | 15 | Decoration, not structure |

| Entity | Weight | Notes |
|--------|--------|-------|
| Player | 2.0 | Average |
| Iron Golem | 8.0 | Very heavy! |
| Horse | 4.0 | Heavy |
| Chicken | 0.3 | Basically nothing |

| Weather | Effect |
|---------|--------|
| Rain | 5% weaker |
| Thunder | 12% weaker + stress spikes |
| Snow | Adds weight over time (up to 30%) |

---

## Remember!

1. **Heavy on top of light = BAD**
2. **Long bridges need supports**
3. **Cracks mean danger - reinforce or repair!**
4. **Don't jump on damaged floors**
5. **Weather affects exposed blocks**
6. **When in doubt, add more pillars!**

Happy Building! 🏗️
