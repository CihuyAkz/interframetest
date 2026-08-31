package dev.fallingcloud.interframe.compat;

import java.lang.reflect.Method;

/**
 * Optional, reflective bridge to Fovea's scaled world-render phase (both mods stay independent; loading
 * either alone is fine).
 *
 * <p><b>Why it exists.</b> During a center-priority Fovea frame the world is rendered into a reduced
 * offscreen target with a horizontally TRIMMED projection, then presented onto the real main target through
 * a non-linear remap (center 1:1 vanilla, periphery compressed geometry stretched back out). That breaks two
 * assumptions our reprojection makes:
 *
 * <ul>
 *   <li>The scene depth we copy at the end of the level render lives in Fovea's <i>source</i> space (linear,
 *       trimmed FOV), while the colour we capture at present time is the <i>presented</i> image (non-linear,
 *       full window). The same normalized UV names different content in the two textures, so the parallax
 *       (translation) warp reads depth for the wrong pixel and everything with parallax — entities, water,
 *       ice — lands at the wrong screen position on every synthesised frame. The error is multiplied by the
 *       camera delta, so it is exactly zero when standing still and grows with motion.</li>
 *   <li>{@code 1/m00} of the live projection is the TRIMMED half-FOV tangent, but the presented image's
 *       center slice behaves like vanilla's untrimmed FOV — the rotation warp under-rotates the whole frame
 *       by the trim factor unless corrected.</li>
 * </ul>
 *
 * <p><b>What we do about it.</b> While a frame is presented through a warped (center-priority) remap:
 * skip the depth copy (parallax degrades smoothly to rotation-only, same as under an Iris shaderpack), and
 * divide the captured horizontal tangent by Fovea's {@code fovTanScale} so the rotation warp is exact in the
 * presented 1:1 center (approximate in the stretched periphery). In Fovea's uniform mode, when standing
 * still (its geometry crossfades to identity), or with Fovea absent/off, {@code fovTanScale} is 1 and
 * everything behaves exactly as before — depth warp included, which is geometrically sound there.</p>
 *
 * <p><b>ABI note:</b> resolved via {@code dev.fovea.render.WorldRenderPhase#fovTanScale()} (public static,
 * returns 1.0 outside center-priority frames). Fail-safe: any resolution or invocation problem permanently
 * degrades to "no Fovea" (tan scale 1, depth copy on).</p>
 */
public final class FoveaBridge {

    private static volatile Method fovTanScale;
    private static volatile boolean resolved;
    private static volatile boolean broken;

    /**
     * Horizontal FOV tan-scale of the frame currently being rendered: {@code < 1} only while Fovea renders
     * a center-priority (warped-present) frame; exactly {@code 1} otherwise (uniform mode, still, absent).
     */
    public static float fovTanScale() {
        if (broken) {
            return 1.0f;
        }
        if (!resolved) {
            resolve();
        }
        final Method m = fovTanScale;
        if (m == null) {
            return 1.0f;
        }
        try {
            final float s = (float) m.invoke(null);
            return s > 0.0f && s <= 1.0f ? s : 1.0f;
        } catch (Throwable t) {
            broken = true;
            return 1.0f;
        }
    }

    /** @return true while the frame being rendered will be presented through a non-linear remap. */
    public static boolean warpedFrame() {
        return fovTanScale() < 0.9995f;
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        try {
            final Class<?> phase = Class.forName("dev.fovea.render.WorldRenderPhase");
            final Method m = phase.getMethod("fovTanScale");
            m.invoke(null); // probe once so a broken resolve can never surface per-frame
            fovTanScale = m;
        } catch (Throwable t) {
            fovTanScale = null;
        }
        resolved = true;
    }

    private FoveaBridge() {}
}
