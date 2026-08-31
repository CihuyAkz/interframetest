package dev.fallingcloud.interframe.synth;

import dev.fallingcloud.interframe.InterframeConfig;
import dev.fallingcloud.interframe.Interframe;

/**
 * Chooses and constructs the active {@link FrameSynthesizer} for the configured mode.
 *
 * <p>The neural backend is instantiated <b>reflectively</b> on purpose: that keeps any symbolic reference
 * to {@link OnnxSynthesizer} (and through it, the {@code ai.onnxruntime} classes) out of this class's
 * constant pool, so loading/verifying the factory never fails when ONNX Runtime is absent — which is the
 * normal case, since it is not bundled. If neural is requested but unavailable, we transparently return
 * the always-present built-in reprojection synthesiser.
 */
public final class SynthesizerFactory {

    private SynthesizerFactory() {
    }

    public static boolean onnxRuntimePresent() {
        try {
            Class.forName("ai.onnxruntime.OrtEnvironment", false, SynthesizerFactory.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static FrameSynthesizer create(InterframeConfig.Mode mode) {
        if (mode == InterframeConfig.Mode.NEURAL) {
            FrameSynthesizer neural = tryCreateNeural();
            if (neural != null) {
                return neural;
            }
            Interframe.LOGGER.info("[Interframe] Neural mode unavailable; using the built-in synthesiser.");
        }
        return new BlendReprojectSynthesizer();
    }

    private static FrameSynthesizer tryCreateNeural() {
        if (!onnxRuntimePresent()) {
            Interframe.LOGGER.info("[Interframe] ONNX Runtime is not on the classpath, so the neural backend "
                    + "can't run. See the README to enable it; falling back for now.");
            return null;
        }
        try {
            Class<?> cls = Class.forName("dev.fallingcloud.interframe.synth.OnnxSynthesizer");
            return (FrameSynthesizer) cls.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            Interframe.LOGGER.error("[Interframe] Could not instantiate the neural backend.", t);
            return null;
        }
    }
}
