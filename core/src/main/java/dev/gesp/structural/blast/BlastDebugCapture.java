package dev.gesp.structural.blast;

import dev.gesp.structural.model.NodePos;
import java.util.List;

/**
 * Optional sink for the geometry the {@link StruxExplosionEngine} computes during its
 * phase-1 scan and then throws away — the "explosion anatomy" a debugger renders:
 * per-candidate intensity and outcome, the occlusion ray each candidate cast, the
 * falloff radius shells, and the destroy threshold.
 *
 * <p><b>Zero cost when unused.</b> Every method is a default no-op and the engine
 * skips building any of this unless {@link #wantsBlastCapture()} returns {@code true}.
 * The production path attaches {@link #NONE} (or nothing), so the live server never
 * pays for capture — the same {@code wantsDebugCapture()} pattern as the solver's
 * {@link dev.gesp.structural.api.DebugCapture}.
 *
 * <p><b>Ray gating.</b> The per-candidate occlusion ray is the firehose (one DDA
 * walk per in-range block). By default a ray is reported only for candidates that
 * crater or crack — the ones a debugger actually highlights. Set
 * {@link #wantsFullRays()} to also report the rays of untouched candidates.
 *
 * <p>Core stays game-type-free: positions are {@link NodePos}, everything else is a
 * primitive or a small enum.
 */
public interface BlastDebugCapture {

    /** Whether the blast engine should drive this sink at all. Default {@code false}. */
    default boolean wantsBlastCapture() {
        return false;
    }

    /**
     * Whether to report the occlusion ray of EVERY in-range candidate, not just the
     * craters and cracks. Default {@code false} — the cheap, debugger-relevant subset.
     */
    default boolean wantsFullRays() {
        return false;
    }

    /**
     * The blast's falloff geometry, fired once before the candidate scan:
     * {@code radius} (blocks), the {@code shells} radii (concentric intensity bands a
     * debugger draws), and the {@code destroyThreshold} an intensity must reach to
     * crater. Lets a renderer place the shells without re-deriving them from config.
     */
    default void onBlastGeometry(double radius, List<Double> shells, double destroyThreshold) {}

    /**
     * One in-range candidate block examined by the scan. {@code distance} is its
     * Euclidean distance from the center; {@code rawIntensity} is the intensity the
     * scan computed (power × falloff × occlusion ÷ blast resistance);
     * {@code occlusionFactor} is the cover attenuation applied (1.0 = unobstructed);
     * {@code outcome} is what the scan decided. Out-of-range candidates are never
     * reported (they do no work).
     */
    default void onBlastCandidate(
            NodePos pos, double distance, double rawIntensity, double occlusionFactor, CandidateOutcome outcome) {}

    /**
     * The occlusion ray cast from the blast center to {@code target}: {@code cells}
     * are the voxels the 3D-DDA line of sight crossed strictly between center and
     * target (in traversal order); {@code attenuations} are the subset of those cells
     * that held a solid block and so counted as cover. Fired per candidate when
     * {@link #wantsFullRays()} is on, otherwise only for cratered/cracked candidates.
     */
    default void onBlastRay(NodePos target, List<NodePos> cells, List<NodePos> attenuations) {}

    /** What the phase-1 scan decided for a candidate. */
    enum CandidateOutcome {
        /** Intensity reached the destroy threshold — part of the crater. */
        DESTROYED,
        /** Intensity below the threshold but above zero — a persistent crack. */
        DAMAGED,
        /** Intensity zero (fully occluded / out of falloff) — untouched. */
        UNAFFECTED
    }

    /** A no-op sink. The production default — capture is entirely off. */
    BlastDebugCapture NONE = new BlastDebugCapture() {};
}
