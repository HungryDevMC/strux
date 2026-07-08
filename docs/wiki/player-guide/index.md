# How Buildings Work <img class="title-icon" src="../assets/img/system/physics-stress.png" alt="">

<div class="page-aside left"><img class="page-mascot" src="../assets/img/mascot/reading-blueprint.png" alt="The Strux Mason"></div>












Let's learn why buildings fall down!

---

## The Big Idea

**Blocks are heavy. Heavy things fall down.**

A block holds up the blocks above it. Pile on too much, and it gives way — just like you couldn't hold 100 books over your head.

---

## Two Numbers per Block

Every block has two numbers.

**Weight** — how heavy it is. Iron is heavy, wool is light.

**Strength** — how much it can hold before breaking. Iron holds 150; glass holds only 5.

See [What Blocks to Use](materials.md) for the full table.

---

## When Things Break

The rule is simple:

!!! warning "The Breaking Rule"
    **If the weight on a block is more than its strength... CRASH!**

```
This is fine:

   [STONE]   ← holds 100
   [STONE]   ← only a little weight on it
   [GROUND]


This is NOT fine:

   [STONE]
   [STONE]
   [STONE]
   [STONE]   ← weight of all these: about 150
   [GLASS]   ← glass only holds 5
   [GROUND]      💥 CRASH! 💥
```

---

## The Domino Effect

When one block breaks, the blocks it was holding up fall too. This is a **cascade**.

```
BEFORE:                AFTER:

   [D]                    (gone)
    |
   [C]                    (gone)
    |
   [B] ← break this
    |
   [A]                    [A] (still here!)
    |                      |
 [GROUND]              [GROUND]
```

B was holding up C and D. When B broke, they had nothing to stand on.

A huge collapse doesn't all happen in one instant — the server finishes it over the next few ticks so nothing is left floating in mid-air.

---

## Building Sideways

Build out from a wall and the first block does the most work.

```
[WALL]───[1]───[2]───[3]───[4]───[5]
          ↑
     Block 1 holds up 2, 3, 4, and 5!
```

The longer you build out, the harder block 1 works. Build too far and... **SNAP!**

!!! tip "Support both ends"
    A bridge anchored to solid ground at **both** ends is far stronger than one sticking out from a single side — the two ends share the load:
    ```
    [WALL]───[1]───[2]───[3]───[4]───[5]───[WALL]
    ```

!!! note "Thick isn't always stronger"
    Adding a second block beside a support that is the **same distance** from the ground doesn't relieve it — that side path carries no load. Only a path that reaches the ground by a **shorter** route, or actual reinforcement, takes weight off a strained block.

---

## Stress = How Hard a Block Works

"Stress" is how close a block is to its limit.

| Stress | How the Block Feels |
|--------|---------------------|
| Under 50% | Fine, no problem |
| 50–80% | Starting to strain |
| 80–95% | Danger zone |
| 95–100% | About to break — **run!** |

Use `/engineer` to see these as colored dust on your blocks. It keeps working if you
log out and back in — turning it on again shows the dust right away.

If a block you place leaves a nearby block dangerously loaded (but not quite falling),
a **⚠ CRITICAL STRESS** warning flashes on your action bar — a heads-up to add support
before it gives way.

---

## Warning Signs

The game warns you before a block breaks.

### Cracks

Blocks crack as they take damage or come under heavy load:

```
[  BLOCK  ]     ← Healthy (no cracks)
[ ─BLOCK─ ]     ← Hairline cracks (60%)
[ ╱BLOCK╲ ]     ← Clear cracks (78%)
[ ▓BLOCK▓ ]     ← Crumbling — about to break! (90%+)
```

### Sounds

- **Creak** = working hard
- **Crack** = about to break
- **Loud crack** = breaking right now!

### Dust

- **Yellow dust** = getting stressed (50%+)
- **Orange dust + smoke** = danger (80%+)
- **Red dust + flames** = run away! (95%+)

Cracks, sounds, and dust are all on by default, and your server admin can tune or turn off any of them.

---

## Keep Learning!

- [What Blocks to Use](materials.md) — Which blocks are strong?
- [Dangerous Things](hazards.md) — TNT, fire, weather
- [Building Tips](tips.md) — Build better!
