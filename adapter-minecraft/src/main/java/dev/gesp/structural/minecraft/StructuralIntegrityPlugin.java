package dev.gesp.structural.minecraft;

import dev.gesp.structural.blast.StruxExplosionEngine;
import dev.gesp.structural.config.PhysicsConfig;
import dev.gesp.structural.fire.FireModel;
import dev.gesp.structural.impact.ImpactEngine;
import dev.gesp.structural.minecraft.command.EngineerModeCommand;
import dev.gesp.structural.minecraft.config.EffectsConfig;
import dev.gesp.structural.minecraft.config.FoundationConfig;
import dev.gesp.structural.minecraft.config.PersistenceConfig;
import dev.gesp.structural.minecraft.config.PluginConfigLoader;
import dev.gesp.structural.minecraft.config.RegionConfig;
import dev.gesp.structural.minecraft.container.ContainerWeightTask;
import dev.gesp.structural.minecraft.entity.EntityMassRegistry;
import dev.gesp.structural.minecraft.entity.EntityWeightTask;
import dev.gesp.structural.minecraft.fire.FireScorchTask;
import dev.gesp.structural.minecraft.hook.EconomyCharges;
import dev.gesp.structural.minecraft.hook.StruxPlaceholders;
import dev.gesp.structural.minecraft.hook.VaultEconomy;
import dev.gesp.structural.minecraft.hook.WarZoneHooks;
import dev.gesp.structural.minecraft.hook.WarZoneService;
import dev.gesp.structural.minecraft.item.ReinforcementItem;
import dev.gesp.structural.minecraft.listener.AsyncSettleCoordinator;
import dev.gesp.structural.minecraft.listener.BlastProcessor;
import dev.gesp.structural.minecraft.listener.BlockBreakListener;
import dev.gesp.structural.minecraft.listener.BlockChangeListener;
import dev.gesp.structural.minecraft.listener.BlockPlaceListener;
import dev.gesp.structural.minecraft.listener.CascadeResumeManager;
import dev.gesp.structural.minecraft.listener.CollapseNotifier;
import dev.gesp.structural.minecraft.listener.CraterApplier;
import dev.gesp.structural.minecraft.listener.CraterBlockRemover;
import dev.gesp.structural.minecraft.listener.CraterBlockRemovers;
import dev.gesp.structural.minecraft.listener.DebrisVisuals;
import dev.gesp.structural.minecraft.listener.DelayedCollapseManager;
import dev.gesp.structural.minecraft.listener.ExplosionListener;
import dev.gesp.structural.minecraft.listener.ImpactProcessor;
import dev.gesp.structural.minecraft.listener.PistonListener;
import dev.gesp.structural.minecraft.listener.ProjectileImpactListener;
import dev.gesp.structural.minecraft.listener.WorldLifecycleListener;
import dev.gesp.structural.minecraft.manager.EvictionWiring;
import dev.gesp.structural.minecraft.manager.StructureManager;
import dev.gesp.structural.minecraft.material.MaterialRegistry;
import dev.gesp.structural.minecraft.perf.TaskTimings;
import dev.gesp.structural.minecraft.persistence.ApiPersistenceAdapter;
import dev.gesp.structural.minecraft.protect.CollapseGuard;
import dev.gesp.structural.minecraft.protect.CoreProtectRestoreDetector;
import dev.gesp.structural.minecraft.protect.CoreProtectRestoreDetector.RestoreScanConfig;
import dev.gesp.structural.minecraft.protect.ProtectionFactory;
import dev.gesp.structural.minecraft.recording.MinecraftEventRecorder;
import dev.gesp.structural.minecraft.recording.RecordingCommand;
import dev.gesp.structural.minecraft.recording.RecordingConfig;
import dev.gesp.structural.minecraft.recording.RecordingService;
import dev.gesp.structural.minecraft.rubble.RubbleHandler;
import dev.gesp.structural.minecraft.scan.RegionScanner;
import dev.gesp.structural.minecraft.scan.StruxCommand;
import dev.gesp.structural.minecraft.temperature.TemperatureLoadTask;
import dev.gesp.structural.minecraft.temperature.TemperatureProvider;
import dev.gesp.structural.minecraft.visual.ActionbarArbiter;
import dev.gesp.structural.minecraft.visual.CollapseEffects;
import dev.gesp.structural.minecraft.visual.DamageVisualizer;
import dev.gesp.structural.minecraft.visual.PreCollapseShake;
import dev.gesp.structural.minecraft.visual.StressSummaryTask;
import dev.gesp.structural.minecraft.visual.StressVisualizer;
import dev.gesp.structural.minecraft.weather.WeatherLoadTask;
import dev.gesp.structural.persistence.FilePersistenceAdapter;
import dev.gesp.structural.persistence.PersistenceAdapter;
import dev.gesp.structural.solver.CascadeEngine;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Structural Integrity plugin for Minecraft.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │               STRUCTURAL INTEGRITY PLUGIN                          │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  Makes blocks behave like real structures!                         │
 *   │                                                                     │
 *   │  • Blocks have weight and strength                                 │
 *   │  • Structures can collapse if overloaded                           │
 *   │  • Breaking a support beam = everything above falls                │
 *   │                                                                     │
 *   │                                                                     │
 *   │       [D]  💥                                                       │
 *   │        │    │                                                      │
 *   │       [C]  💥                                                       │
 *   │        │    │                                                      │
 *   │       [B] ──┘   ← Player breaks this                               │
 *   │        │                                                           │
 *   │       [A]       ← Still standing (connected to ground)             │
 *   │        │                                                           │
 *   │      [GND]                                                         │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class StructuralIntegrityPlugin extends JavaPlugin {

    private PhysicsConfig physicsConfig;
    private PersistenceConfig persistenceConfig;
    private EffectsConfig effectsConfig;
    private RegionConfig regionConfig;
    private FoundationConfig foundationConfig;
    private CollapseGuard collapseGuard;
    /** Set in onLoad() if WorldGuard is on the classpath and the flag registered. */
    private boolean worldGuardAvailable = false;

    private MaterialRegistry materialRegistry;
    private StructureManager structureManager;
    private StressVisualizer stressVisualizer;
    private DamageVisualizer damageVisualizer;
    private CoreProtectRestoreDetector coreProtectRestoreDetector;
    private StressSummaryTask stressSummaryTask;
    private ActionbarArbiter actionbarArbiter;
    private PreCollapseShake preCollapseShake;
    private FireScorchTask fireScorchTask;
    private EntityWeightTask entityWeightTask;
    private ContainerWeightTask containerWeightTask;
    private WeatherLoadTask weatherLoadTask;
    private TemperatureLoadTask temperatureLoadTask;
    private ImpactProcessor impactProcessor;
    private BlastProcessor blastProcessor;
    private DelayedCollapseManager delayedCollapseManager;
    private CascadeResumeManager cascadeResumeManager;
    private ExecutorService stressSolveWorker;
    private AsyncSettleCoordinator asyncSettleCoordinator;
    private EngineerModeCommand engineerModeCommand;
    private ReinforcementItem reinforcementItem;
    private PersistenceAdapter persistenceAdapter;
    private BukkitTask autoSaveTask;
    private RecordingConfig recordingConfig;
    private MinecraftEventRecorder eventRecorder;
    private RecordingService recordingService;
    private dev.gesp.structural.minecraft.recording.MetricsConfig metricsConfig;
    private dev.gesp.structural.minecraft.recording.MetricsOverlay metricsOverlay;

    /**
     * Per-task wall-clock + work-count timings for every repeating task, shared
     * across all of them and read by {@code /strux perf}. Created here so the
     * registry outlives any single task and is owned by the plugin.
     */
    private final TaskTimings taskTimings = new TaskTimings();

    @Override
    public void onLoad() {
        // WorldGuard custom flags MUST be registered during onLoad, before WorldGuard
        // enables. Guard behind Class.forName so this is a clean no-op when WorldGuard
        // is absent (and immune to plugin load-order, unlike getPlugin()).
        try {
            Class.forName("com.sk89q.worldguard.WorldGuard");
            ProtectionFactory.registerWorldGuardFlag(getLogger());
            worldGuardAvailable = true;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            getLogger()
                    .info("WorldGuard not found - region-flag gating disabled " + "(per-world toggles still apply).");
        } catch (Throwable t) {
            getLogger().warning("WorldGuard flag registration failed: " + t.getMessage());
        }
    }

    @Override
    public void onEnable() {
        // Save default config if it doesn't exist
        saveDefaultConfig();

        // Load configs. All config parsing lives in PluginConfigLoader (SRP); this
        // method only wires the parsed objects into the engine.
        PluginConfigLoader configLoader = new PluginConfigLoader(getConfig(), getLogger());
        physicsConfig = configLoader.loadPhysicsConfig();
        persistenceConfig = configLoader.loadPersistenceConfig();
        effectsConfig = configLoader.loadEffectsConfig();
        regionConfig = configLoader.loadRegionConfig();
        foundationConfig = configLoader.loadFoundationConfig();
        getLogger().info("Physics config loaded: " + physicsConfig);
        getLogger().info("Persistence config loaded: " + persistenceConfig);
        getLogger().info("Effects config loaded: " + effectsConfig);
        getLogger().info("Region config loaded: " + regionConfig);
        getLogger().info("Foundation config loaded: " + foundationConfig);

        // Optional economy (Vault). Null hook → all actions free.
        EconomyCharges economy = new EconomyCharges(VaultEconomy.tryHook(this));

        // War-zone scoping (Towny/Factions): destruction only inside active war
        // zones when enabled. ALLOW_ALL pass-through otherwise.
        WarZoneService warZone = WarZoneHooks.resolve(
                this,
                regionConfig.isWarZoneEnabled(),
                regionConfig.getWarZoneProviders(),
                regionConfig.isWarZoneAllowWilderness());

        // Build the collapse guard: gates physics by region/world (WorldGuard) +
        // war state (Towny/Factions) and logs every collapse removal (CoreProtect).
        collapseGuard = ProtectionFactory.create(
                this,
                regionConfig.isEnabled(),
                regionConfig.getDisabledWorlds(),
                regionConfig.isRespectWorldGuard(),
                regionConfig.isCoreProtectLogging(),
                worldGuardAvailable,
                warZone);

        // Initialize components
        materialRegistry = new MaterialRegistry();
        configLoader.loadMaterialOverrides(materialRegistry);
        structureManager = new StructureManager(materialRegistry, physicsConfig);
        structureManager.setLogger(getLogger());
        // Anti-freeze seatbelt: per-call wall-clock budget for main-thread settles
        // (break cascades + cascade resumes). A settle that exceeds it pauses and
        // finishes over later ticks via the resume manager — collapses get delayed,
        // the tick never freezes. ≤ 0 disables (legacy unbounded behavior).
        structureManager.setSettleBudgetMs(getConfig().getDouble("cascade.settle-budget-ms", 8.0));
        structureManager.setCollapseGuard(collapseGuard);
        structureManager.setFoundationConfig(foundationConfig);

        // Initialize event recording. Stamp the plugin version onto each session so a
        // replay can detect drift between the engine that recorded and the one replaying.
        recordingConfig = configLoader.loadRecordingConfig();
        eventRecorder = new MinecraftEventRecorder(
                getDataFolder().toPath(),
                recordingConfig,
                physicsConfig,
                getDescription().getVersion(),
                getLogger());
        structureManager.setEventRecorder(eventRecorder);

        // Host-facing recording API: lets dependent plugins (e.g. Siege) auto-record
        // tagged match/build sessions. Registered with ServicesManager so an external
        // plugin can resolve it, and also exposed via getRecordingService().
        recordingService = new RecordingService(eventRecorder, structureManager);
        getServer()
                .getServicesManager()
                .register(RecordingService.class, recordingService, this, ServicePriority.Normal);

        // Schedule per-tick reset for event throttling
        getServer().getScheduler().runTaskTimer(this, eventRecorder::onTickStart, 1L, 1L);

        // Initialize metrics overlay
        metricsConfig = configLoader.loadMetricsConfig();
        metricsOverlay = new dev.gesp.structural.minecraft.recording.MetricsOverlay(
                this,
                structureManager,
                eventRecorder,
                metricsConfig.getUpdateIntervalTicks(),
                metricsConfig.isEnabled());
        metricsOverlay.start();

        // Initialize persistence
        if (persistenceConfig.isEnabled()) {
            initializePersistence();
            loadAllWorlds();
        }

        // Auto-start recording if enabled
        if (recordingConfig.isAutoRecord()) {
            World firstWorld = getServer().getWorlds().isEmpty()
                    ? null
                    : getServer().getWorlds().get(0);
            if (firstWorld != null) {
                String sessionId = eventRecorder.startRecording(
                        firstWorld.getUID().toString(), structureManager.getGraph(firstWorld));
                getLogger().info("Auto-recording started: " + sessionId);
            }
        }

        // Create collapse effects (single source of truth for all visual effects)
        CollapseEffects collapseEffects = new CollapseEffects(effectsConfig, this);

        // Create rubble handler (for falling debris and item returns)
        RubbleHandler rubbleHandler = new RubbleHandler(this, physicsConfig, effectsConfig, materialRegistry);

        // Start delayed collapse manager (progressive failure) - must be before BlockBreakListener
        delayedCollapseManager = new DelayedCollapseManager(
                this, structureManager, effectsConfig, collapseEffects, collapseGuard, taskTimings);
        delayedCollapseManager.setRubbleHandler(rubbleHandler);
        delayedCollapseManager.start();

        // Resume manager: finishes cascades the per-event step cap cut short, a
        // little more each tick (still capped), so a giant collapse settles over
        // the next ticks instead of leaving chunks floating. Must exist before the
        // BlockBreakListener, which hands it truncated cascades.
        // Async stress solve: compute keep-sized settles off the main thread over a
        // graph snapshot, apply the result main-thread through the existing budgeted
        // collapse path. Gated by cascade.async-settle (default true); false keeps the
        // byte-identical synchronous settle. Reuses the blast worker recipe: a single
        // daemon thread, and an UNMETERED CascadeEngine built with the same physics
        // config (so the snapshot solve is bit-identical to the synchronous one).
        if (getConfig().getBoolean("cascade.async-settle", true)) {
            // Under a MockBukkit server there are no scheduler threads and tests drive
            // ticks synchronously, so a real background worker would race the tick loop
            // non-deterministically. Fall back to the inline seam there (worker == null →
            // the coordinator solves at submit time): same code path, deterministic tests.
            boolean mockServer = getServer().getClass().getName().toLowerCase().contains("mockbukkit");
            stressSolveWorker = mockServer
                    ? null
                    : Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "strux-stress-solve");
                        t.setDaemon(true);
                        return t;
                    });
            asyncSettleCoordinator = new AsyncSettleCoordinator(
                    structureManager, new CascadeEngine(physicsConfig), stressSolveWorker, getLogger(), taskTimings);
        }

        cascadeResumeManager = new CascadeResumeManager(
                this,
                structureManager,
                delayedCollapseManager,
                getLogger(),
                getConfig().getInt("cascade.max-resume-ticks", 200),
                taskTimings,
                asyncSettleCoordinator);
        cascadeResumeManager.start();

        // Chat feedback for collapses: big-collapse server broadcast + one-time
        // /engineer hint. Shared by both block listeners (the PDC "seen" flag means
        // the hint fires once per player whether they break or place into a collapse).
        CollapseNotifier collapseNotifier = new CollapseNotifier(this, effectsConfig);

        // Late-loaded worlds (Multiverse /mv load, dynamic creation) must still read
        // their saved structures — onEnable's boot load only covers worlds present then.
        getServer().getPluginManager().registerEvents(new WorldLifecycleListener(this, structureManager), this);

        // Register event listeners
        getServer()
                .getPluginManager()
                .registerEvents(
                        new BlockBreakListener(
                                structureManager,
                                delayedCollapseManager,
                                cascadeResumeManager,
                                collapseEffects,
                                collapseGuard,
                                rubbleHandler,
                                effectsConfig,
                                collapseNotifier),
                        this);
        // Shared action-bar arbiter: the critical-stress warning and the live
        // stress summary both write the action bar, so they route through here and
        // the higher-priority warning wins a contended tick (no flicker).
        actionbarArbiter = new ActionbarArbiter(() -> getServer().getCurrentTick());
        getServer()
                .getPluginManager()
                .registerEvents(
                        new BlockPlaceListener(
                                structureManager,
                                effectsConfig,
                                collapseEffects,
                                collapseGuard,
                                false,
                                collapseNotifier,
                                actionbarArbiter),
                        this);

        // Route piston movements through the structure graph (pushed block = removal + placement).
        getServer()
                .getPluginManager()
                .registerEvents(
                        new PistonListener(
                                this,
                                structureManager,
                                delayedCollapseManager,
                                cascadeResumeManager,
                                collapseEffects,
                                collapseGuard),
                        this);

        // Catch block changes that bypass normal break/place events: fire burn, liquid flow,
        // tree growth, enderman pickup, falling block landing.
        getServer()
                .getPluginManager()
                .registerEvents(
                        new BlockChangeListener(
                                this,
                                structureManager,
                                physicsConfig,
                                delayedCollapseManager,
                                cascadeResumeManager,
                                collapseEffects,
                                collapseGuard),
                        this);

        // Component memory eviction (SCALING.md §5, off by default): park dormant
        // structures out of the live graph once all their chunks unload, restore them
        // bit-identically on reload. Wiring lives in EvictionWiring (no-op when disabled).
        EvictionWiring.install(this, structureManager, configLoader.loadMemoryEvictionConfig());

        // Route explosions through the strux blast model (crater + cascade + debris).
        // The event handler only gates + claims + queues; a tick-budgeted processor
        // does the settle, so a TNT chain in one tick can never freeze a single tick.
        DebrisVisuals debrisVisuals = new DebrisVisuals(this);
        getServer().getPluginManager().registerEvents(debrisVisuals, this);
        StruxExplosionEngine explosionEngine = new StruxExplosionEngine(physicsConfig);
        // The crater is applied a few blocks per tick (not all at once) by a streaming
        // applier. FAWE does the raw AIR writes in one bulk edit when present + enabled;
        // otherwise a plain streamed setType. strux still does protection / logging /
        // sampled effects / budgeted debris itself either way.
        CraterBlockRemover craterRemover =
                CraterBlockRemovers.create(getConfig().getBoolean("blast.fawe-acceleration", true), getLogger());
        getLogger().info("Crater block writer: " + craterRemover.describe());
        CraterApplier craterApplier = new CraterApplier(
                collapseGuard,
                collapseEffects,
                debrisVisuals,
                craterRemover,
                getConfig().getInt("blast.crater-effect-sample-rate", 8));
        // The settle's overload stress query is the one operation whose cost scales
        // with structure size and cannot be split; off-thread it can never stall a
        // tick (the session parks; the worker answers against a snapshot —
        // bit-identical physics). Disable to run it inline (legacy).
        ExecutorService blastSolveWorker = getConfig().getBoolean("blast.async-overload-queries", true)
                ? Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "strux-blast-solve");
                    t.setDaemon(true);
                    return t;
                })
                : null;
        blastProcessor = new BlastProcessor(
                this,
                structureManager,
                explosionEngine,
                delayedCollapseManager,
                cascadeResumeManager,
                craterApplier,
                effectsConfig,
                collapseEffects,
                getLogger(),
                getConfig().getDouble("blast.tick-budget-ms", 10.0),
                getConfig().getInt("blast.max-scan-per-tick", 4096),
                getConfig().getInt("blast.max-crater-removals-per-tick", 64),
                taskTimings,
                blastSolveWorker);
        blastProcessor.start();
        if (asyncSettleCoordinator != null) {
            // While a blast is still carving its crater the graph mutates every tick,
            // so any settle solved mid-blast is stale on arrival. Defer async settle
            // submits (and park conflicted jobs) until the blast work drains — see
            // AsyncSettleCoordinator#setDeferWhile.
            asyncSettleCoordinator.setDeferWhile(blastProcessor::hasActiveBlast);
        }
        // Let callers (e.g. siege's trebuchet) route custom blasts through the same model.
        structureManager.setBlastProcessor(blastProcessor);
        getServer()
                .getPluginManager()
                .registerEvents(new ExplosionListener(structureManager, collapseGuard, blastProcessor), this);

        // Route projectile/ram hits through the strux impact model (E=½mv² → penetration).
        // The hit handler only queues; a tick-budgeted processor does the settle, so a
        // volley against a huge structure can never freeze a single server tick.
        ImpactEngine impactEngine = new ImpactEngine(physicsConfig);
        impactProcessor = new ImpactProcessor(
                this,
                structureManager,
                impactEngine,
                delayedCollapseManager,
                cascadeResumeManager,
                debrisVisuals,
                effectsConfig,
                collapseEffects,
                collapseGuard,
                getLogger(),
                getConfig().getDouble("impact.tick-budget-ms", 10.0),
                taskTimings);
        impactProcessor.start();
        getServer()
                .getPluginManager()
                .registerEvents(
                        new ProjectileImpactListener(
                                structureManager,
                                impactProcessor,
                                collapseGuard,
                                getConfig().getBoolean("impact.enabled", true),
                                getConfig().getDouble("impact.energy-scale", 1.0)),
                        this);

        // Route sustained fire through the strux fire model (heat → weakening → collapse)
        FireModel fireModel = new FireModel(physicsConfig);
        fireScorchTask = new FireScorchTask(
                this,
                structureManager,
                fireModel,
                physicsConfig,
                delayedCollapseManager,
                cascadeResumeManager,
                collapseGuard,
                getConfig().getInt("fire.scan-interval-ticks", 20),
                getConfig().getInt("fire.barren-burnout-ticks", 600),
                getConfig().getBoolean("fire.enabled", true),
                getConfig().getDouble("fire.tick-budget-ms", 10.0),
                taskTimings);
        getServer().getPluginManager().registerEvents(fireScorchTask, this);
        fireScorchTask.start();

        // Entity weight + fall impact: entities standing on or landing on weak blocks add stress
        EntityMassRegistry entityMassRegistry = new EntityMassRegistry();
        configLoader.loadEntityMassConfig(entityMassRegistry);
        entityWeightTask = new EntityWeightTask(
                this,
                structureManager,
                entityMassRegistry,
                physicsConfig,
                delayedCollapseManager,
                cascadeResumeManager,
                collapseGuard,
                configLoader.loadEntityWeightConfig(),
                taskTimings);
        getServer().getPluginManager().registerEvents(entityWeightTask, this);
        entityWeightTask.start();

        // Container weight: a full chest/barrel/shulker on a weak block adds load
        containerWeightTask = new ContainerWeightTask(
                this,
                structureManager,
                physicsConfig,
                delayedCollapseManager,
                cascadeResumeManager,
                collapseGuard,
                configLoader.loadContainerWeightConfig(),
                taskTimings);
        containerWeightTask.start();

        // Weather load: rain/thunder weaken exposed structures, snow accumulates
        weatherLoadTask = new WeatherLoadTask(
                this,
                structureManager,
                physicsConfig,
                delayedCollapseManager,
                cascadeResumeManager,
                collapseGuard,
                configLoader.loadWeatherConfig(),
                taskTimings);
        getServer().getPluginManager().registerEvents(weatherLoadTask, this);
        weatherLoadTask.start();

        // Temperature-based strength: blocks near lava/fire weaken; heat-then-douse cracks.
        // Off by default (physicsConfig flag); shares the active-fire set with the scorch
        // task so it never double-counts heat on a block fire is already damaging (rule A).
        TemperatureProvider temperatureProvider = new TemperatureProvider(
                physicsConfig.getComfortTemperatureC(),
                getConfig().getInt("temperature-strength.heat-falloff-radius", 4),
                getConfig().getDouble("temperature-strength.solid-insulation-blocks", 3.0));
        temperatureLoadTask = new TemperatureLoadTask(
                this,
                structureManager,
                physicsConfig,
                delayedCollapseManager,
                cascadeResumeManager,
                collapseGuard,
                temperatureProvider,
                fireScorchTask,
                getConfig().getInt("temperature-strength.scan-interval-ticks", 40),
                getConfig().getInt("temperature-strength.scan-radius", 5),
                getConfig().getBoolean("temperature-strength.enabled", false),
                getConfig().getDouble("temperature-strength.tick-budget-ms", 10.0),
                taskTimings);
        temperatureLoadTask.start();

        // Pre-collapse shake: critical blocks wobble before they fail
        preCollapseShake = new PreCollapseShake(this);
        preCollapseShake.setEnabled(physicsConfig.isPreCollapseShake());

        // Start stress visualizer
        stressVisualizer = new StressVisualizer(structureManager, this, preCollapseShake, effectsConfig, taskTimings);
        stressVisualizer.start(physicsConfig.getVisualUpdateTicks());

        // Start damage/crack visualizer (cracks from accumulated damage AND stress)
        damageVisualizer = new DamageVisualizer(
                structureManager, materialRegistry, this, effectsConfig, physicsConfig, taskTimings);
        damageVisualizer.start(physicsConfig.getVisualUpdateTicks());

        // CoreProtect restore detector: clears stale damage when CoreProtect restores
        // blocks (Block.setType bypasses BlockPlaceEvent, so we query the API).
        RestoreScanConfig restoreScanConfig = RestoreScanConfig.fromConfig(getConfig());
        BooleanSupplier restoreScanBusy = () -> blastProcessor.hasActiveBlast()
                || blastProcessor.queueSize() > 0
                || cascadeResumeManager.pendingWorlds() > 0;
        coreProtectRestoreDetector =
                CoreProtectRestoreDetector.tryCreate(structureManager, this, restoreScanConfig, restoreScanBusy);
        if (coreProtectRestoreDetector != null) {
            coreProtectRestoreDetector.start();
            getLogger().info("CoreProtect restore detection enabled.");
        }

        // Start the live stress summary (action bar). Off by default; start() is a
        // no-op when disabled, so a disabled server pays nothing.
        stressSummaryTask = new StressSummaryTask(
                structureManager, this, actionbarArbiter, taskTimings, effectsConfig.isStressSummaryEnabled());
        stressSummaryTask.start(effectsConfig.getStressSummaryIntervalTicks());

        // Reinforcement: a craftable Support Beam item + the right-click handler.
        FileConfiguration cfg = getConfig();
        double reinforcePerItem = cfg.getDouble("reinforcement.per-item", 0.5);
        double reinforceMax = cfg.getDouble("reinforcement.max-multiplier", 4.0);
        double reinforceCommandAdd = cfg.getDouble("reinforcement.command-add", 0.5);
        double reinforceItemCost = cfg.getDouble("economy.reinforce-cost", 0.0);
        double reinforceCommandCost = cfg.getDouble("economy.reinforce-command-cost", 0.0);
        double repairCost = cfg.getDouble("economy.repair-cost", 0.0);
        double engineerCost = cfg.getDouble("economy.engineer-cost", 0.0);
        int recipeYield = cfg.getInt("reinforcement.recipe-yield", 2);

        reinforcementItem = new ReinforcementItem(
                this, structureManager, economy, reinforcePerItem, reinforceMax, reinforceItemCost, recipeYield);
        if (cfg.getBoolean("reinforcement.item-enabled", true)) {
            getServer().getPluginManager().registerEvents(reinforcementItem, this);
            if (cfg.getBoolean("reinforcement.recipe-enabled", true)) {
                reinforcementItem.registerRecipe();
            }
        }

        // Register commands
        engineerModeCommand = new EngineerModeCommand(structureManager, this, economy, engineerCost);
        getCommand("engineer").setExecutor(engineerModeCommand);

        // Region scan + reinforce/repair/grade
        StruxCommand struxCommand = new StruxCommand(
                this,
                new RegionScanner(structureManager, materialRegistry, physicsConfig),
                structureManager,
                economy,
                reinforcementItem,
                reinforceCommandAdd,
                reinforceMax,
                reinforceCommandCost,
                repairCost);
        struxCommand.setRecordingCommand(new RecordingCommand(this, eventRecorder, recordingService));
        struxCommand.setMetricsOverlay(metricsOverlay);
        struxCommand.setTaskTimings(taskTimings);
        getCommand("strux").setExecutor(struxCommand);
        getServer().getPluginManager().registerEvents(struxCommand, this);

        // PlaceholderAPI: expose %strux_grade% etc. when PAPI is installed.
        registerPlaceholders();

        // Start auto-save task
        if (persistenceConfig.isEnabled() && persistenceConfig.getAutoSaveIntervalSeconds() > 0) {
            startAutoSave();
        }

        getLogger().info("Structural Integrity enabled! Blocks will now collapse realistically.");
    }

    /**
     * Initialize the persistence adapter based on config.
     */
    private void initializePersistence() {
        try {
            persistenceAdapter = createPersistenceAdapter();
            persistenceAdapter.initialize();
            structureManager.setPersistenceAdapter(persistenceAdapter);
            getLogger().info("Persistence initialized: " + persistenceAdapter.getName());
        } catch (Exception e) {
            getLogger().severe("Failed to initialize persistence: " + e.getMessage());
            getLogger().warning("Structures will NOT be saved!");
            persistenceAdapter = null;
        }
    }

    /**
     * Create the appropriate persistence adapter based on config.
     */
    private PersistenceAdapter createPersistenceAdapter() {
        switch (persistenceConfig.getType()) {
            case API:
                return new ApiPersistenceAdapter(
                        persistenceConfig.getApiUrl(),
                        persistenceConfig.getApiKey(),
                        Duration.ofSeconds(persistenceConfig.getApiTimeoutSeconds()));
            case FILE:
            default:
                return new FilePersistenceAdapter(Path.of(getDataFolder().getAbsolutePath()));
        }
    }

    /**
     * Kick off loading structures for all currently loaded worlds <b>without
     * blocking</b> {@code onEnable}. Disk read + deserialization run on the
     * persistence executor; each finished graph is published back on the main
     * thread (see {@link StructureManager#loadAllWorldsAsync}). The server boots
     * instantly; structures pop in a moment later. A world whose load fails
     * disables persistence so the half-loaded state can never overwrite good
     * disk data.
     */
    private void loadAllWorlds() {
        structureManager.loadAllWorldsAsync(this, getServer().getWorlds());
    }

    /**
     * Start the auto-save task.
     *
     * <p>Runs on the main thread, not an async scheduler. {@code StructureGraph}
     * is not thread-safe: serializing it (via {@code StructureConverter.toData})
     * iterates the live node map, which the main thread mutates on every
     * cascade/blast — doing that off-thread raced into a
     * {@code ConcurrentModificationException}. The snapshot is cheap, so we take
     * it on the main thread and only the disk write stays async (handled inside
     * {@link StructureManager#saveAllAsync()} by the persistence executor). We do
     * not {@code join()} that write, so the main thread never blocks on disk I/O.
     */
    private void startAutoSave() {
        int intervalTicks = persistenceConfig.getAutoSaveIntervalSeconds() * 20;
        autoSaveTask = getServer()
                .getScheduler()
                .runTaskTimer(
                        this,
                        () -> {
                            if (physicsConfig.isDebugLogging()) {
                                getLogger().info("Auto-saving structures...");
                            }
                            structureManager.saveAllAsync();
                        },
                        intervalTicks,
                        intervalTicks);
        getLogger().info("Auto-save enabled (every " + persistenceConfig.getAutoSaveIntervalSeconds() + " seconds)");
    }

    /**
     * Register the PlaceholderAPI expansion when PAPI is installed. Guarded so
     * the plugin loads fine without PAPI on the classpath.
     */
    private void registerPlaceholders() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().info("PlaceholderAPI not found - placeholders disabled.");
            return;
        }
        try {
            new StruxPlaceholders(this, structureManager).register();
            getLogger().info("PlaceholderAPI expansion 'strux' registered (%strux_grade% etc.).");
        } catch (Throwable t) {
            getLogger().warning("Failed to register PlaceholderAPI expansion: " + t.getMessage());
        }
    }

    /**
     * Load recording settings from config.yml. Package-private so the recording
     * E2E test can drive the missing-key fallback path directly. Delegates to
     * {@link PluginConfigLoader} (the single home for config parsing).
     */
    RecordingConfig loadRecordingConfig() {
        return new PluginConfigLoader(getConfig(), getLogger()).loadRecordingConfig();
    }

    /**
     * Reload the physics config from file.
     */
    public void reloadPhysicsConfig() {
        reloadConfig();
        physicsConfig = new PluginConfigLoader(getConfig(), getLogger()).loadPhysicsConfig();
        getLogger().info("Physics config reloaded: " + physicsConfig);
    }

    @Override
    public void onDisable() {
        // Stop auto-save task
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }

        // Stop visualizers
        if (stressVisualizer != null) {
            stressVisualizer.stop();
        }
        if (damageVisualizer != null) {
            damageVisualizer.stop();
        }
        if (coreProtectRestoreDetector != null) {
            coreProtectRestoreDetector.stop();
        }
        if (stressSummaryTask != null) {
            stressSummaryTask.stop();
        }
        if (asyncSettleCoordinator != null) {
            asyncSettleCoordinator.clear();
        }
        if (stressSolveWorker != null) {
            stressSolveWorker.shutdownNow();
        }
        if (cascadeResumeManager != null) {
            cascadeResumeManager.stop();
        }
        if (delayedCollapseManager != null) {
            delayedCollapseManager.stop();
        }
        if (fireScorchTask != null) {
            fireScorchTask.stop();
        }
        if (entityWeightTask != null) {
            entityWeightTask.stop();
        }
        if (containerWeightTask != null) {
            containerWeightTask.stop();
        }
        if (weatherLoadTask != null) {
            weatherLoadTask.stop();
        }
        if (temperatureLoadTask != null) {
            temperatureLoadTask.stop();
        }
        if (impactProcessor != null) {
            impactProcessor.stop();
        }
        if (blastProcessor != null) {
            blastProcessor.stop();
        }

        // Remove any in-flight wobble overlays
        if (preCollapseShake != null) {
            preCollapseShake.stopAll();
        }

        // Stop engineer mode for all players
        if (engineerModeCommand != null) {
            engineerModeCommand.disableAll();
        }

        // Remove the Support Beam recipe so a reload doesn't duplicate it
        if (reinforcementItem != null) {
            reinforcementItem.unregisterRecipe();
        }

        // Stop metrics overlay
        if (metricsOverlay != null) {
            metricsOverlay.stop();
        }

        // Stop event recording
        if (eventRecorder != null) {
            eventRecorder.close();
        }

        // Save all structures before shutdown
        if (persistenceConfig != null && persistenceConfig.isEnabled() && structureManager != null) {
            getLogger().info("Saving structures...");
            structureManager.saveAll();
            getLogger().info("Structures saved.");
        }

        // Shutdown persistence adapter
        if (persistenceAdapter != null) {
            persistenceAdapter.shutdown();
        }

        if (structureManager != null) {
            structureManager.clearAll();
        }
        getLogger().info("Structural Integrity disabled.");
    }

    /**
     * Get the material registry for customization.
     */
    public MaterialRegistry getMaterialRegistry() {
        return materialRegistry;
    }

    /**
     * Get the engineer-mode visualizer so other plugins can toggle the stress
     * overlay for a player (e.g. Siege turns it on inside its build designer).
     */
    public EngineerModeCommand getEngineerModeCommand() {
        return engineerModeCommand;
    }

    /**
     * Get the structure manager for API access.
     */
    public StructureManager getStructureManager() {
        return structureManager;
    }

    /**
     * Get the effects config (sounds, particles, shake thresholds) for API tuning.
     */
    public EffectsConfig getEffectsConfig() {
        return effectsConfig;
    }

    /**
     * Get the persistence adapter (may be null if disabled).
     */
    public PersistenceAdapter getPersistenceAdapter() {
        return persistenceAdapter;
    }

    /**
     * Get the delayed collapse manager for progressive failure effects.
     */
    public DelayedCollapseManager getDelayedCollapseManager() {
        return delayedCollapseManager;
    }

    /**
     * Get the cascade resume manager that finishes cap-truncated collapses over
     * the following ticks.
     */
    public CascadeResumeManager getCascadeResumeManager() {
        return cascadeResumeManager;
    }

    /**
     * Get the tick-budgeted impact processor that settles queued projectile hits.
     */
    public ImpactProcessor getImpactProcessor() {
        return impactProcessor;
    }

    /**
     * Get the tick-budgeted blast processor that settles queued explosions.
     */
    public BlastProcessor getBlastProcessor() {
        return blastProcessor;
    }

    /**
     * Get the fire scorch task (periodic heat-degradation scan).
     */
    public FireScorchTask getFireScorchTask() {
        return fireScorchTask;
    }

    /**
     * Get the collapse guard that gates physics by region / war state.
     */
    public CollapseGuard getCollapseGuard() {
        return collapseGuard;
    }

    /**
     * Get the event recorder for recording commands.
     */
    public MinecraftEventRecorder getEventRecorder() {
        return eventRecorder;
    }

    /**
     * Host-facing recording API for dependent plugins (e.g. the Siege gamemode)
     * to start/stop tagged recordings programmatically. Also registered with
     * Bukkit's {@code ServicesManager} under {@link RecordingService}.
     */
    public RecordingService getRecordingService() {
        return recordingService;
    }

    /**
     * Get the metrics overlay for toggling the boss bar display.
     */
    public dev.gesp.structural.minecraft.recording.MetricsOverlay getMetricsOverlay() {
        return metricsOverlay;
    }

    /**
     * Get the per-task wall-clock timing registry that every repeating task
     * records into and {@code /strux perf} reads from.
     */
    public TaskTimings getTaskTimings() {
        return taskTimings;
    }
}
