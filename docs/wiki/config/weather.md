# Weather Settings <img class="title-icon" src="../assets/img/system/weather-moisture.png" alt="">

<div class="page-aside left"><img class="page-mascot" src="../assets/img/mascot/freezing.png" alt="The Strux Mason"></div>












Rain, thunder, and snow weaken **exposed** structures. All keys live under the
`weather:` section of `config.yml`. See the [full key reference](index.md) for
the at-a-glance table.

---

## Master switches

```yaml
weather:
  enabled: true

  # How often the weather scan runs (ticks; 40 = 2 seconds).
  scan-interval-ticks: 40

  # Most ms one scan may spend before it yields the tick (see below).
  tick-budget-ms: 10.0

  # Only affect blocks with open sky above (interior is safe).
  require-sky-access: true
```

---

## Big worlds don't freeze

On a world with many tracked blocks, one weather scan could otherwise look at
every block at once and stall the server when weather changed. Instead the scan
walks chunk by chunk and stops when it runs out of time. `tick-budget-ms` is how
long one scan may run before it yields and resumes on the next scan.

```yaml
weather:
  # 10.0 = half a tick. Lower = gentler on the server, but the weather effect
  # takes a little longer to spread across a huge build.
  tick-budget-ms: 10.0
```

So on a huge world the effect arrives over a few ticks instead of all at once. If
the weather changes again mid-spread, the current pass finishes first, then the
new weather applies on the next scan.

The scan is also cheap per block. "Is the sky open here?" and "what biome is this?"
are the same for every block in a column (same x and z), so the scan looks each up
once per column and reuses the answer for every block stacked there. A tall tower
asks the world once, not once per floor. It re-reads the world on the next full scan,
so digging a roof off or changing terrain is picked up right away.

---

## Rain

```yaml
weather:
  rain:
    enabled: true
    # 0.95 = exposed blocks hold 5% less load while it's raining.
    capacity-multiplier: 0.95
```

Usually harmless, but a building already at 95%+ stress can tip over when it
gets wet.

---

## Thunder

```yaml
weather:
  thunder:
    enabled: true
    # 0.88 = exposed blocks hold 12% less load in a thunderstorm.
    capacity-multiplier: 0.88

    stress-spikes:
      enabled: true
      chance: 0.02   # per scan, per exposed stressed block
      amount: 0.15   # spike size as a fraction of remaining capacity
```

Thunder both weakens blocks and randomly rattles them. A building above ~80%
stress can collapse during a storm.

---

## Snow

Snow accumulates as additive load in cold biomes and melts when it stops snowing.

```yaml
weather:
  snow:
    enabled: true
    load-per-scan: 0.005   # load added per scan, as a fraction of capacity
    max-load: 0.3          # cap on accumulated snow load (30% of capacity)
    decay-per-scan: 0.002  # how fast it melts when not snowing
```

Snow piles up over time, so a flat roof already at 75% can pass 100% after a long
snowfall. Sloped roofs shed it; breaking the snow block above a roof relieves the
load.

---

## Where weather applies

Only blocks with direct sky access are affected — anything under a roof is safe.

| Weather | Effect | Severity |
|---------|--------|----------|
| Rain | ~5% weaker | Minor |
| Thunder | ~12% weaker + random spikes | Dangerous |
| Snow | Adds load over time | Builds up |

---

## Turning specific weather off

```yaml
weather:
  enabled: true
  rain:
    enabled: false   # no rain effect
  thunder:
    enabled: true    # thunder still works
  snow:
    enabled: false   # no snow effect
```

---

## Learn more

- [Full key reference](index.md)
- [Physics Settings](physics.md)
- [Effects Settings](effects.md)
</content>
