package dev.gesp.structural.minecraft.visual;

import dev.gesp.structural.model.NodePos;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Pre-collapse shake: a heavily-stressed block visibly wobbles, telegraphing
 * that it is about to fail.
 *
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────────────┐
 *   │                      PRE-COLLAPSE SHAKE                            │
 *   ├─────────────────────────────────────────────────────────────────────┤
 *   │                                                                     │
 *   │  A stressed block trembles in place — faint in the danger zone,     │
 *   │  building to a violent shake as it nears 100%:                      │
 *   │                                                                     │
 *   │        ▒▒▒          ▒▒▒              ▒▒▒                            │
 *   │       ▒▒▒▒▒    →   ▒▒▒▒▒▒     →     ▒▒▒▒▒    (wobble, wobble…)       │
 *   │       █████        █████            █████                          │
 *   │                                                                     │
 *   │  A short-lived BlockDisplay overlay does the wobbling. It is grown  │
 *   │  slightly and centred so it fully occludes the static real block    │
 *   │  underneath (no "double"), and lit from its neighbours so it isn't  │
 *   │  a black silhouette. Crucially it touches NOTHING else — the real   │
 *   │  block, the world, the physics graph, and other overlays (engineer  │
 *   │  mode, damage cracks) are all left exactly as they were. It is      │
 *   │  purely a cosmetic, self-contained telegraph.                       │
 *   │                                                                     │
 *   └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class PreCollapseShake {

    private final Plugin plugin;

    /** One active shake per block position — re-triggers while wobbling are ignored. */
    private final Map<NodePos, ShakeTask> active = new ConcurrentHashMap<>();

    /** Config-driven kill switch. */
    private volatile boolean enabled = true;

    // The overlay is grown a touch and centred so the un-moving real block stays
    // hidden behind it through the wobble — no need to alter the real block.
    private static final float SCALE = 1.05f;
    private static final Vector3f SCALE_VEC = new Vector3f(SCALE, SCALE, SCALE);
    private static final Vector3f CENTRE = new Vector3f(0.5f, 0.5f, 0.5f);

    /**
     * Constant fed to the transform so the centre-scaled model rotates about the
     * block centre. The Bukkit transform is
     * {@code translation + leftRotation·(scale·point)}; choosing
     * {@code translation = R·BASE_OFFSET + CENTRE} with {@code BASE_OFFSET = −scale·CENTRE}
     * reduces it to {@code R·(scaleAboutCentre(point) − CENTRE) + CENTRE}.
     */
    private static final Vector3f BASE_OFFSET = new Vector3f(-SCALE * CENTRE.x, -SCALE * CENTRE.y, -SCALE * CENTRE.z);

    // Shake lasts longer the further past the floor the block is.
    private static final int MIN_DURATION_TICKS = 10; // ~0.5s at the floor
    private static final int MAX_DURATION_TICKS = 30; // ~1.5s at/over 100%
    // Peak tilt amplitude in radians (~3.4°). Small enough that the centre-pivot
    // rotation never swings a corner past the oversize margin, so the real block
    // stays occluded — yet fast oscillation reads clearly as a tremble.
    private static final float MAX_TILT = 0.06f;
    // Shaking starts in the "danger" band and ramps to full violence at 100%.
    // A block rarely *sits* in a narrow 95–100% window (discrete block loads jump
    // in big steps), so starting at 80% gives a visible faint→violent escalation.
    private static final double SHAKE_FLOOR = 0.80;
    private static final double SHAKE_WINDOW = 0.20; // 80% → 100%
    // Don't spawn an overlay nobody can see — a display entity is real and ticked.
    private static final double VIEW_DISTANCE_SQ = 48.0 * 48.0;

    private static final BlockFace[] NEIGHBOURS = {
        BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
        BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    public PreCollapseShake(Plugin plugin) {
        this.plugin = plugin;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Start a wobble on the block at {@code pos}, or do nothing if one is
     * already running there. Safe to call every visualizer tick.
     *
     * @param world         the block's world
     * @param pos           the block position
     * @param stressPercent current stress (≥ {@value #SHAKE_FLOOR}); drives intensity
     */
    public void shake(World world, NodePos pos, double stressPercent) {
        if (!enabled || world == null || active.containsKey(pos)) {
            return;
        }

        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (block.getType().isAir()) {
            return; // already gone — nothing to telegraph
        }
        if (!hasNearbyViewer(world, pos)) {
            return; // no one around to see it — skip the entity
        }

        BlockData data = block.getBlockData();
        Location blockLoc = block.getLocation();

        ShakeTask task;
        try {
            // Spawn at the block corner so the model's [0,1]³ cube lines up with
            // the real block cell. The lambda runs before the entity goes live.
            BlockDisplay display = world.spawn(blockLoc, BlockDisplay.class, d -> {
                d.setBlock(data);
                // A display inside a solid block samples the (dark) interior light
                // and renders black — light it from the surrounding open space.
                d.setBrightness(neighbourBrightness(block));
                d.setPersistent(false); // never written to disk — no orphans on restart
                d.addScoreboardTag("strux_shake");
            });
            task = new ShakeTask(pos, blockLoc, display, durationFor(stressPercent), amplitudeFor(stressPercent));
        } catch (Throwable t) {
            // Display entities may be unsupported (e.g. headless test server) —
            // degrade silently rather than break the visualizer loop.
            return;
        }

        active.put(pos, task);
        task.runTaskTimer(plugin, 0L, 1L);
    }

    /** Remove every active wobble and despawn its overlay (e.g. on plugin disable). */
    public void stopAll() {
        for (ShakeTask task : new ArrayList<>(active.values())) {
            task.finish();
        }
        active.clear();
    }

    /** Is any player close enough for the wobble to be worth spawning? */
    private static boolean hasNearbyViewer(World world, NodePos pos) {
        double cx = pos.x() + 0.5, cy = pos.y() + 0.5, cz = pos.z() + 0.5;
        for (Player player : world.getPlayers()) {
            Location loc = player.getLocation();
            double dx = loc.getX() - cx, dy = loc.getY() - cy, dz = loc.getZ() - cz;
            if (dx * dx + dy * dy + dz * dz <= VIEW_DISTANCE_SQ) {
                return true;
            }
        }
        return false;
    }

    /** Brightest light among the block's neighbours, so the overlay is lit like its faces. */
    private static Display.Brightness neighbourBrightness(Block block) {
        int sky = 0, blockLight = 0;
        for (BlockFace face : NEIGHBOURS) {
            Block n = block.getRelative(face);
            sky = Math.max(sky, n.getLightFromSky());
            blockLight = Math.max(blockLight, n.getLightFromBlocks());
        }
        return new Display.Brightness(blockLight, sky);
    }

    /** Map stress in [0.80, 1.0+] to a 0..1 ramp across the shake window. */
    private static double ramp(double stressPercent) {
        double r = (stressPercent - SHAKE_FLOOR) / SHAKE_WINDOW;
        return Math.max(0.0, Math.min(1.0, r));
    }

    private static int durationFor(double stressPercent) {
        return MIN_DURATION_TICKS + (int) Math.round(ramp(stressPercent) * (MAX_DURATION_TICKS - MIN_DURATION_TICKS));
    }

    private static float amplitudeFor(double stressPercent) {
        // A faint tremble at the 80% floor, ramping to full violent tilt at 100%.
        return MAX_TILT * (0.2f + 0.8f * (float) ramp(stressPercent));
    }

    /**
     * Drives one block's wobble: oscillates the overlay, escalating toward the
     * end of its life, then despawns it.
     */
    private final class ShakeTask extends BukkitRunnable {
        private final NodePos pos;
        private final Location loc;
        private final BlockDisplay display;
        private final int duration;
        private final float amplitude;
        private int tick = 0;
        private float phase = (float) (Math.random() * Math.PI * 2.0);

        ShakeTask(NodePos pos, Location loc, BlockDisplay display, int duration, float amplitude) {
            this.pos = pos;
            this.loc = loc;
            this.display = display;
            this.duration = duration;
            this.amplitude = amplitude;
        }

        @Override
        public void run() {
            // End when it has run its course, the overlay is gone, or the real
            // block has since collapsed (don't keep wobbling empty space).
            if (tick >= duration
                    || !display.isValid()
                    || loc.getBlock().getType().isAir()) {
                finish();
                return;
            }

            // Escalate the wobble as the block nears failure.
            float amp = amplitude * (0.5f + 0.5f * ((float) tick / duration));
            phase += 1.15f; // advance fast enough to read as a shiver

            float tiltX = amp * (float) Math.sin(phase);
            float tiltZ = amp * (float) Math.cos(phase * 1.3f);
            Quaternionf rot = new Quaternionf().rotateX(tiltX).rotateZ(tiltZ);

            // Rotate the centre-scaled model about the block centre.
            Vector3f translation = rot.transform(new Vector3f(BASE_OFFSET)).add(CENTRE);
            display.setTransformation(new Transformation(translation, rot, SCALE_VEC, new Quaternionf()));

            tick++;
        }

        /** Cancel the task and despawn the overlay. Idempotent. */
        void finish() {
            try {
                cancel();
            } catch (IllegalStateException ignored) {
                // already cancelled
            }
            try {
                if (display.isValid()) {
                    display.remove();
                }
            } catch (Throwable ignored) {
                // entity already gone
            }
            active.remove(pos, this);
        }
    }
}
