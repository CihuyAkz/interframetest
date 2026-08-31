package dev.fallingcloud.interframe;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Interframe — AI frame generation for Minecraft. Fabric port.
 *
 * <p>Captures each finished frame and synthesises one or more <em>in-between</em> frames, presenting
 * them between the real ones to raise the perceived frame rate and smooth motion. The synthesiser is
 * layered and degrades gracefully (see {@link dev.fallingcloud.interframe.synth.FrameSynthesizer}):
 * an always-correct cross-blend, a rotational motion-reprojection ("timewarp") that sharpens the common
 * mouse-look case, and an optional neural RIFE backend (ONNX Runtime) for true learned interpolation.
 * The held item, hotbar, crosshair and rest of the HUD are captured separately from the 3D scene and
 * are never warped/blended — see {@link FrameGenerator} and {@code preserveHud}.
 *
 * <p>Ported to MC 1.21.11 with Sodium 0.8 + Iris, here on Fabric Loader (originally built against
 * 1.21.1 — see PORTING_NOTES.md for what changed in the jump between them, notably that
 * {@code RenderTarget} no longer exposes a raw GL framebuffer id; see {@link dev.fallingcloud.interframe.gl.GpuInterop}).
 * It hooks a different pipeline stage than terrain-shading-rate mods (frame <em>presentation</em>),
 * captures the final composited image downstream of Sodium/Iris, and registers its settings page
 * through Sodium's config API (the {@code sodium:config_api_user} entrypoint on Fabric) so it appears
 * in the video settings menu — including when rendered by Reese's Sodium Options. Configuration lives
 * in {@link InterframeConfig}; the GL work is in {@link FrameGenerator}.
 */
public final class Interframe implements ClientModInitializer {
    public static final String MOD_ID = "interframe";
    public static final String VERSION = "1.3.0-fabric";
    public static final Logger LOGGER = LoggerFactory.getLogger("Interframe");

    @Override
    public void onInitializeClient() {
        InterframeConfig.get(); // load (or create defaults) eagerly so the file exists on first launch
        LOGGER.info("[Interframe] Initialised (Fabric).");
    }
}
