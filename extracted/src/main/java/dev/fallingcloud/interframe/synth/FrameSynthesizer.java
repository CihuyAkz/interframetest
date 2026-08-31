package dev.fallingcloud.interframe.synth;

/**
 * Produces one synthetic in-between frame. Implementations render a fullscreen image into the framebuffer
 * and viewport that {@link dev.fallingcloud.interframe.FrameGenerator} has already bound (normally the
 * back buffer, FBO 0), using the prev/curr colour textures and motion data in the {@link SynthContext}.
 *
 * <p>The backends form a graceful-degradation ladder:
 * <ul>
 *   <li>{@link BlendReprojectSynthesizer} — GPU cross-blend + optional rotational reprojection. Always
 *       available; no model, no extra dependency.</li>
 *   <li>{@code OnnxSynthesizer} — neural RIFE interpolation via ONNX Runtime. Highest quality, but needs
 *       a model file and the (unbundled) runtime, so it is created only when both are present.</li>
 * </ul>
 */
public interface FrameSynthesizer {

    /** Short human name for logs / the config tooltip. */
    String name();

    /** Lazily create GL resources / load the model. @return true if this synthesiser is ready to run. */
    boolean init();

    /** True once {@link #init()} succeeded and the backend can produce frames this session. */
    boolean isAvailable();

    /** Render the in-between frame for {@code ctx} into the currently-bound framebuffer. */
    void synthesize(SynthContext ctx);

    /** Release GL / native resources. */
    void dispose();
}
