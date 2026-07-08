package dev.gesp.structural.minecraft.config;

/**
 * Configuration for visual effects, sounds, and collapse behavior.
 *
 * <p>All settings are designed to be easily understood and tweaked
 * by players of any age. Each field has sensible defaults.
 */
public class EffectsConfig {

    // ═══════════════════════════════════════════════════════════════════════
    //  COLLAPSE TIMING
    // ═══════════════════════════════════════════════════════════════════════

    /** How long blocks take to collapse after being broken (in ticks, 20 = 1 second) */
    private int collapseDelayTicks = 25; // 1.25 seconds

    /** How long blocks take to collapse after an explosion (faster = more dramatic) */
    private int explosionCollapseDelayTicks = 4; // 0.2 seconds

    /** Max blocks that can collapse each game tick (higher = smoother but uses more CPU) */
    private int maxCollapsesPerTick = 25;

    /**
     * Below this measured TPS, strux shrinks the per-tick collapse rate so a giant
     * collapse can never drag the server further down — it just takes more ticks. 0
     * disables the throttle (collapses always run at the full {@link #maxCollapsesPerTick}).
     */
    private double tpsFloor = 18.0;

    /** Floor on blocks collapsed per tick even under heavy load, so a collapse always finishes. */
    private int minCollapsesPerTick = 4;

    /**
     * Max blocks per tick that play collapse particles/sounds. The blocks still vanish;
     * this just bounds the visual/packet load so a huge collapse can't flood clients.
     */
    private int maxCollapseEffectsPerTick = 8;

    // ═══════════════════════════════════════════════════════════════════════
    //  SCREEN SHAKE
    // ═══════════════════════════════════════════════════════════════════════

    /** Enable screen shake when buildings collapse? */
    private boolean screenShakeEnabled = true;

    /** Minimum blocks that must collapse before screen shakes */
    private int screenShakeThreshold = 15;

    /** How far away players can feel the shake (in blocks) */
    private double screenShakeRadius = 32.0;

    // ═══════════════════════════════════════════════════════════════════════
    //  DUST AND PARTICLES
    // ═══════════════════════════════════════════════════════════════════════

    /** Enable dust clouds when buildings collapse? */
    private boolean dustCloudsEnabled = true;

    /** How much dust to spawn (1.0 = normal, 2.0 = double, 0.5 = half) */
    private double dustMultiplier = 1.0;

    /** Enable the big outward-spreading dust wave? */
    private boolean dustWaveEnabled = true;

    /** Max distance the dust wave can spread (in blocks) */
    private double dustWaveMaxRadius = 15.0;

    // ═══════════════════════════════════════════════════════════════════════
    //  STRESS PARTICLES (the colored warning particles)
    // ═══════════════════════════════════════════════════════════════════════

    /** Stress level where YELLOW particles start showing (0.0-1.0) */
    private double stressCautionThreshold = 0.50; // 50%

    /** Stress level where ORANGE particles start showing (0.0-1.0) */
    private double stressDangerThreshold = 0.80; // 80%

    /** Stress level where RED particles + flames start showing (0.0-1.0) */
    private double stressCriticalThreshold = 0.95; // 95%

    /** Size of stress particles (0.1-2.0) */
    private float stressParticleSize = 0.7f;

    /**
     * Ramp the ambient stress sound with proximity to failure (soft creak →
     * groan → sharp crack) instead of a flat danger/critical sound.
     */
    private boolean escalatingStressAudioEnabled = true;

    /** Multiplier on ambient stress-sound volume (1.0 = full volume, lower = quieter). */
    private float stressAudioVolume = 0.6f;

    // ═══════════════════════════════════════════════════════════════════════
    //  CRACKING WARNINGS (sounds/particles before collapse)
    // ═══════════════════════════════════════════════════════════════════════

    /** Enable cracking sounds and particles before blocks collapse? */
    private boolean crackingWarningsEnabled = true;

    /** How often to play cracking effects (in ticks, lower = more frequent) */
    private int crackingWarningInterval = 5;

    /** Max blocks to play cracking sounds for at once (prevents sound spam) */
    private int maxCrackingWarningsPerTick = 6;

    // ═══════════════════════════════════════════════════════════════════════
    //  DAMAGE CRACKS (visual cracks on damaged blocks)
    // ═══════════════════════════════════════════════════════════════════════

    /** Minimum damage before cracks become visible (0.0-1.0) — low so cracks show progressively. */
    private double minVisibleDamage = 0.05; // 5% — subtle early, intensifying with damage

    /** How far away players can see damage cracks (in blocks) */
    private double damageViewDistance = 32.0;

    /**
     * Show a small particle-and-sound "bite" each time a projectile impact settles
     * and actually damages a block (it cracked the struck block, or punched through
     * at least one). Pure feedback — the physics is identical either way; this just
     * lets a player see that each arrow added its own little chunk of damage.
     */
    private boolean impactFeedbackEnabled = true;

    /**
     * Master switch for the block-break crack overlay. Off = no crack textures at
     * all (from damage OR stress); the structure still collapses, it just doesn't
     * telegraph with cracks. Independent of pre-collapse shake (the wobble), so an
     * owner can run cracks, wobble, both, or neither.
     */
    private boolean cracksEnabled = true;

    /**
     * Also crack blocks from structural STRESS, not just accumulated blast/impact
     * damage. When on, a heavily-loaded wall visibly cracks under its own load and
     * the cracks spread toward a failure as load redistributes. Thresholds for the
     * crack levels live in PhysicsConfig (crack-*-threshold). Has no effect unless
     * {@link #cracksEnabled} is on.
     */
    private boolean stressCracksEnabled = true;

    // ═══════════════════════════════════════════════════════════════════════
    //  EXPLOSION EFFECTS
    // ═══════════════════════════════════════════════════════════════════════

    /** How far away to notify players about explosion damage (in blocks) */
    private double explosionNotifyRadius = 64.0;

    /** Max debris pieces to spawn per explosion */
    private int maxDebrisPerExplosion = 200;

    // ═══════════════════════════════════════════════════════════════════════
    //  ACTIONBAR WARNINGS
    // ═══════════════════════════════════════════════════════════════════════

    /** Stress level to show "CRITICAL STRESS" warning when placing blocks (0.0-1.0) */
    private double criticalStressWarningThreshold = 0.90; // 90%

    /**
     * Show the always-on live stress summary in the action bar while a player
     * looks at (or stands on) a tracked structure. Off by default so existing
     * servers are not surprised by a new HUD element.
     */
    private boolean stressSummaryEnabled = false;

    /** How often the live stress summary refreshes (in ticks, 10 = twice a second). */
    private int stressSummaryIntervalTicks = 10;

    // ═══════════════════════════════════════════════════════════════════════
    //  NEAR-MISS NOTIFICATION
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Tell the player "Close call" when a block they triggered collapses while it was
     * barely holding — overloaded, but only just past its limit.
     */
    private boolean nearMissNotificationEnabled = true;

    /**
     * How loaded a collapsing block must have been to count as a near miss, as a
     * fraction of its capacity (1.0 = exactly at the limit). Just above 1.0 means it
     * was holding right up until it failed; far above 1.0 means it was hopelessly
     * overloaded and no message is shown.
     */
    private double nearMissThreshold = 0.98;

    // ═══════════════════════════════════════════════════════════════════════
    //  RUBBLE (FALLING DEBRIS)
    // ═══════════════════════════════════════════════════════════════════════

    /** Enable rubble falling from collapsed blocks? */
    private boolean rubbleEnabled = false;

    /** Return collapsed blocks as items to the player who triggered collapse? */
    private boolean returnCollapsedBlocks = false;

    /** How high above ground level to estimate rubble spawn Y (for survival calculations) */
    private int rubbleGroundOffset = 0;

    // ═══════════════════════════════════════════════════════════════════════
    //  COLLAPSE NOTIFICATIONS (chat)
    // ═══════════════════════════════════════════════════════════════════════

    /** Broadcast a server-wide chat message when a big collapse happens? */
    private boolean bigCollapseBroadcastEnabled = true;

    /** Minimum blocks that must collapse in one action before the server is told */
    private int bigCollapseBroadcastThreshold = 15;

    /** Send a one-time tip suggesting /engineer the first time a player collapses their build? */
    private boolean firstCollapseHintEnabled = true;
    //  UNDERMINING (dig-under-a-wall presentation)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * When a break-triggered collapse spawns rubble, nudge the falling debris
     * sideways toward the dug-out column (the block the player broke) so the
     * tunnel partially backfills instead of rubble landing straight down. Pure
     * presentation — the physics is identical. Only matters when rubble is on.
     */
    private boolean undermineBackfillRubble = true;

    /**
     * Hard cap on how many rubble {@link org.bukkit.entity.FallingBlock} entities
     * a single collapse may spawn, so a huge undermine can't litter the world with
     * hundreds of entities. Shares the explosion debris budget by default.
     */
    private int maxRubblePerCollapse = 200;

    // ═══════════════════════════════════════════════════════════════════════
    //  GETTERS AND SETTERS
    // ═══════════════════════════════════════════════════════════════════════

    public int getCollapseDelayTicks() {
        return collapseDelayTicks;
    }

    public void setCollapseDelayTicks(int ticks) {
        this.collapseDelayTicks = ticks;
    }

    public int getExplosionCollapseDelayTicks() {
        return explosionCollapseDelayTicks;
    }

    public void setExplosionCollapseDelayTicks(int ticks) {
        this.explosionCollapseDelayTicks = ticks;
    }

    public int getMaxCollapsesPerTick() {
        return maxCollapsesPerTick;
    }

    public void setMaxCollapsesPerTick(int max) {
        this.maxCollapsesPerTick = max;
    }

    public double getTpsFloor() {
        return tpsFloor;
    }

    public void setTpsFloor(double tpsFloor) {
        this.tpsFloor = tpsFloor;
    }

    public int getMinCollapsesPerTick() {
        return minCollapsesPerTick;
    }

    public void setMinCollapsesPerTick(int min) {
        this.minCollapsesPerTick = min;
    }

    public int getMaxCollapseEffectsPerTick() {
        return maxCollapseEffectsPerTick;
    }

    public void setMaxCollapseEffectsPerTick(int max) {
        this.maxCollapseEffectsPerTick = max;
    }

    public boolean isScreenShakeEnabled() {
        return screenShakeEnabled;
    }

    public void setScreenShakeEnabled(boolean enabled) {
        this.screenShakeEnabled = enabled;
    }

    public int getScreenShakeThreshold() {
        return screenShakeThreshold;
    }

    public void setScreenShakeThreshold(int threshold) {
        this.screenShakeThreshold = threshold;
    }

    public double getScreenShakeRadius() {
        return screenShakeRadius;
    }

    public void setScreenShakeRadius(double radius) {
        this.screenShakeRadius = radius;
    }

    public boolean isDustCloudsEnabled() {
        return dustCloudsEnabled;
    }

    public void setDustCloudsEnabled(boolean enabled) {
        this.dustCloudsEnabled = enabled;
    }

    public double getDustMultiplier() {
        return dustMultiplier;
    }

    public void setDustMultiplier(double multiplier) {
        this.dustMultiplier = multiplier;
    }

    public boolean isDustWaveEnabled() {
        return dustWaveEnabled;
    }

    public void setDustWaveEnabled(boolean enabled) {
        this.dustWaveEnabled = enabled;
    }

    public double getDustWaveMaxRadius() {
        return dustWaveMaxRadius;
    }

    public void setDustWaveMaxRadius(double radius) {
        this.dustWaveMaxRadius = radius;
    }

    public double getStressCautionThreshold() {
        return stressCautionThreshold;
    }

    public void setStressCautionThreshold(double threshold) {
        this.stressCautionThreshold = threshold;
    }

    public double getStressDangerThreshold() {
        return stressDangerThreshold;
    }

    public void setStressDangerThreshold(double threshold) {
        this.stressDangerThreshold = threshold;
    }

    public double getStressCriticalThreshold() {
        return stressCriticalThreshold;
    }

    public void setStressCriticalThreshold(double threshold) {
        this.stressCriticalThreshold = threshold;
    }

    public float getStressParticleSize() {
        return stressParticleSize;
    }

    public void setStressParticleSize(float size) {
        this.stressParticleSize = size;
    }

    public boolean isEscalatingStressAudioEnabled() {
        return escalatingStressAudioEnabled;
    }

    public void setEscalatingStressAudioEnabled(boolean escalatingStressAudioEnabled) {
        this.escalatingStressAudioEnabled = escalatingStressAudioEnabled;
    }

    public float getStressAudioVolume() {
        return stressAudioVolume;
    }

    public void setStressAudioVolume(float stressAudioVolume) {
        this.stressAudioVolume = stressAudioVolume;
    }

    public boolean isCrackingWarningsEnabled() {
        return crackingWarningsEnabled;
    }

    public void setCrackingWarningsEnabled(boolean enabled) {
        this.crackingWarningsEnabled = enabled;
    }

    public int getCrackingWarningInterval() {
        return crackingWarningInterval;
    }

    public void setCrackingWarningInterval(int interval) {
        this.crackingWarningInterval = interval;
    }

    public int getMaxCrackingWarningsPerTick() {
        return maxCrackingWarningsPerTick;
    }

    public void setMaxCrackingWarningsPerTick(int max) {
        this.maxCrackingWarningsPerTick = max;
    }

    public double getMinVisibleDamage() {
        return minVisibleDamage;
    }

    public void setMinVisibleDamage(double damage) {
        this.minVisibleDamage = damage;
    }

    public boolean isCracksEnabled() {
        return cracksEnabled;
    }

    public void setCracksEnabled(boolean cracksEnabled) {
        this.cracksEnabled = cracksEnabled;
    }

    public boolean isStressCracksEnabled() {
        return stressCracksEnabled;
    }

    public void setStressCracksEnabled(boolean stressCracksEnabled) {
        this.stressCracksEnabled = stressCracksEnabled;
    }

    public double getDamageViewDistance() {
        return damageViewDistance;
    }

    public void setDamageViewDistance(double distance) {
        this.damageViewDistance = distance;
    }

    public boolean isImpactFeedbackEnabled() {
        return impactFeedbackEnabled;
    }

    public void setImpactFeedbackEnabled(boolean enabled) {
        this.impactFeedbackEnabled = enabled;
    }

    public double getExplosionNotifyRadius() {
        return explosionNotifyRadius;
    }

    public void setExplosionNotifyRadius(double radius) {
        this.explosionNotifyRadius = radius;
    }

    public int getMaxDebrisPerExplosion() {
        return maxDebrisPerExplosion;
    }

    public void setMaxDebrisPerExplosion(int max) {
        this.maxDebrisPerExplosion = max;
    }

    public double getCriticalStressWarningThreshold() {
        return criticalStressWarningThreshold;
    }

    public void setCriticalStressWarningThreshold(double threshold) {
        this.criticalStressWarningThreshold = threshold;
    }

    public boolean isStressSummaryEnabled() {
        return stressSummaryEnabled;
    }

    public void setStressSummaryEnabled(boolean enabled) {
        this.stressSummaryEnabled = enabled;
    }

    public int getStressSummaryIntervalTicks() {
        return stressSummaryIntervalTicks;
    }

    public void setStressSummaryIntervalTicks(int ticks) {
        this.stressSummaryIntervalTicks = ticks;
    }

    public boolean isNearMissNotificationEnabled() {
        return nearMissNotificationEnabled;
    }

    public void setNearMissNotificationEnabled(boolean enabled) {
        this.nearMissNotificationEnabled = enabled;
    }

    public double getNearMissThreshold() {
        return nearMissThreshold;
    }

    public void setNearMissThreshold(double threshold) {
        this.nearMissThreshold = threshold;
    }

    public boolean isRubbleEnabled() {
        return rubbleEnabled;
    }

    public void setRubbleEnabled(boolean enabled) {
        this.rubbleEnabled = enabled;
    }

    public boolean isReturnCollapsedBlocks() {
        return returnCollapsedBlocks;
    }

    public void setReturnCollapsedBlocks(boolean returnBlocks) {
        this.returnCollapsedBlocks = returnBlocks;
    }

    public int getRubbleGroundOffset() {
        return rubbleGroundOffset;
    }

    public void setRubbleGroundOffset(int offset) {
        this.rubbleGroundOffset = offset;
    }

    public boolean isBigCollapseBroadcastEnabled() {
        return bigCollapseBroadcastEnabled;
    }

    public void setBigCollapseBroadcastEnabled(boolean enabled) {
        this.bigCollapseBroadcastEnabled = enabled;
    }

    public int getBigCollapseBroadcastThreshold() {
        return bigCollapseBroadcastThreshold;
    }

    public void setBigCollapseBroadcastThreshold(int threshold) {
        this.bigCollapseBroadcastThreshold = threshold;
    }

    public boolean isFirstCollapseHintEnabled() {
        return firstCollapseHintEnabled;
    }

    public void setFirstCollapseHintEnabled(boolean enabled) {
        this.firstCollapseHintEnabled = enabled;
    }

    public boolean isUndermineBackfillRubble() {
        return undermineBackfillRubble;
    }

    public void setUndermineBackfillRubble(boolean enabled) {
        this.undermineBackfillRubble = enabled;
    }

    public int getMaxRubblePerCollapse() {
        return maxRubblePerCollapse;
    }

    public void setMaxRubblePerCollapse(int max) {
        this.maxRubblePerCollapse = max;
    }

    @Override
    public String toString() {
        return "EffectsConfig{" + "collapseDelay="
                + collapseDelayTicks + "t" + ", explosionDelay="
                + explosionCollapseDelayTicks + "t" + ", shakeThreshold="
                + screenShakeThreshold + ", dustMult="
                + dustMultiplier + "}";
    }
}
