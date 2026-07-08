# Installation <img class="title-icon" src="../assets/img/system/settings.png" alt="">

<div class="page-aside left"><img class="page-mascot" src="../assets/img/mascot/lifting-chest.png" alt="The Strux Mason"></div>












Let's get Strux running on your server!

---

## What You Need

| Thing | Version |
|-------|---------|
| Minecraft | 1.21 or newer |
| Server | Paper (best) or Spigot |
| Java | 21 or newer |

---

## Step 1: Install

1. **Stop your server** (if it's running).

2. **Put the plugin jar in your `plugins` folder.** The file is called something like `structural-integrity-1.0.0-all.jar`.

```
your-server/
└── plugins/
    └── structural-integrity-1.0.0-all.jar  ← Put it here!
```

3. **Start your server.**

---

## Step 2: Check It Works

Look at your server console. You should see:

```
[StructuralIntegrity] Structural Integrity enabled! Blocks will now collapse realistically.
```

That's it! You're done.

---

## Try It Out

1. Build a tall tower (about 10 blocks high).
2. Run `/strux scan` over it (only scanned blocks have physics).
3. Break a block near the bottom.
4. Watch everything fall!

---

## The Config File

After the first run, you'll have a config file. Every feature lives in here as a setting — fire, weather, projectile damage, reinforcement, and siege war zones are all turned on or off in config.

```
your-server/
└── plugins/
    └── StructuralIntegrity/
        └── config.yml  ← All the settings!
```

See [Configuration](../config/index.md) to learn what you can change.

---

## Something Wrong?

### Server won't start?

- Check your Java version: `java -version` should say 21 or higher.
- Check you have Paper or Spigot 1.21+.

### Nothing is collapsing?

- Run `/strux scan` over the build first — only scanned blocks have physics.
- Check you're not in a protected area (a WorldGuard region with `strux-physics deny`, or a world in the `disabled-worlds` list).
- If war-zone mode is on, destruction only happens inside an active war zone.

### Need more help?

- Check the [Admin Guide](../admin/index.md).
- Ask in our Discord.
- Report bugs on the issue tracker.

---

## Next Steps

- [Quick Start](quickstart.md) — Try some experiments!
- [Admin Guide](../admin/index.md) — All the admin stuff
- [Configuration](../config/index.md) — Change settings
