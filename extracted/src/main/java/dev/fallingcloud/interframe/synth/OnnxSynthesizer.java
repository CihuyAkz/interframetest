package dev.fallingcloud.interframe.synth;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import dev.fallingcloud.interframe.Interframe;
import dev.fallingcloud.interframe.gl.ShaderProgram;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Neural in-between-frame synthesiser: runs a RIFE-style interpolation model through ONNX Runtime.
 *
 * <p><b>This class is only ever loaded when ONNX Runtime is actually on the classpath</b> — see
 * {@link SynthesizerFactory}, which checks for {@code ai.onnxruntime.OrtEnvironment} via reflection
 * before touching this type. ONNX Runtime is intentionally NOT bundled (its GPU natives are hundreds of
 * MB); the user drops the runtime jar + a model in to enable this path. See the README.
 *
 * <p>Pipeline each synthetic frame: GPU-downscale prev/curr to the inference size, read them back, run
 * the model (two RGB frames → one RGB frame, NCHW float in [0,1]; a scalar/small "timestep" input, if the
 * model exposes one, receives the in-between time), then upload and bilinearly upscale the result to the
 * screen. Per-frame readback + inference is the cost of true learned interpolation; it only nets a win if
 * the model is fast enough on the user's GPU, which is why the built-in reprojection backend is default.
 *
 * <p>Supported model signature (validated at load, not assumed): ≥2 image inputs shaped like NCHW with a
 * channel dim of 3, exactly 1 image output, dynamic H/W. Anything else is rejected with a clear log and
 * Interframe falls back to {@link BlendReprojectSynthesizer}.
 */
public final class OnnxSynthesizer implements FrameSynthesizer {

    private static final String PASSTHROUGH_VERT = """
            #version 150 core
            out vec2 vUv;
            void main() {
                vec2 p = vec2((gl_VertexID == 1) ? 3.0 : -1.0, (gl_VertexID == 2) ? 3.0 : -1.0);
                vUv = p * 0.5 + 0.5;
                gl_Position = vec4(p, 0.0, 1.0);
            }
            """;

    private static final String PASSTHROUGH_FRAG = """
            #version 150 core
            in vec2 vUv;
            out vec4 fragColor;
            uniform sampler2D src;     // upscaled model output
            uniform sampler2D worldTex; // curr frame's WORLD-only capture, unwarped reference
            uniform sampler2D guiTex;   // curr frame's FULL capture (world+hand+HUD), unwarped reference
            uniform int hasGui;
            void main() {
                vec3 outc = texture(src, vUv).rgb;
                // Same hand/HUD passthrough trick as the built-in synthesiser: worldTex and guiTex are
                // the SAME real frame at the SAME screen position, one before and one after the hand/HUD
                // were drawn, so any difference between them there is hand/HUD (screen-locked, so vUv is
                // always its correct spot) — copy it straight through instead of the model's output.
                if (hasGui == 1) {
                    vec3 guiColor = texture(guiTex, vUv).rgb;
                    vec3 worldRef = texture(worldTex, vUv).rgb;
                    vec3 d = abs(guiColor - worldRef);
                    // See BlendReprojectSynthesizer: 0.02 let low-contrast hand/item pixels (colour
                    // close to the background behind them) slip under the threshold and get treated as
                    // world, so they still showed model interpolation artefacts despite "Preserve Hand
                    // & HUD" being on. worldTex/guiTex are bit-identical outside the hand/HUD, so ~1/255
                    // is safe and catches those low-contrast cases too.
                    if (max(d.r, max(d.g, d.b)) > 0.004) {
                        outc = guiColor;
                    }
                }
                fragColor = vec4(outc, 1.0);
            }
            """;

    /** Inference is capped to this longest-edge size; the result is bilinearly upscaled to the screen. */
    private static final int MAX_INFER_EDGE = 960;

    private OrtEnvironment env;
    private OrtSession session;
    private final List<String> imageInputs = new ArrayList<>();
    private String timestepInput; // null if the model has no timestep input (midpoint-only model)
    private String outputName;

    private final ShaderProgram passthrough = new ShaderProgram();

    // GL scratch (lazily sized to the inference resolution).
    private int srcFbo;     // read FBO we attach the source texture to for the downscale blit
    private int inferFbo;   // draw FBO holding the small downscaled texture we read back
    private int inferTex;
    private int outTex;     // model result, uploaded here, then drawn fullscreen
    private int iw = -1, ih = -1;
    private FloatBuffer readbackPrev, readbackCurr, upload;

    private boolean ready;

    @Override
    public String name() {
        return "Neural RIFE (ONNX Runtime)";
    }

    private static Path modelPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("interframe").resolve("model.onnx");
    }

    @Override
    public boolean init() {
        if (ready) {
            return true;
        }
        Path model = modelPath();
        if (!Files.exists(model)) {
            Interframe.LOGGER.info("[Interframe] Neural mode selected but no model at {} — using the built-in "
                    + "synthesiser instead. Drop a RIFE .onnx there to enable it.", model);
            return false;
        }
        try {
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            addBestProvider(opts);
            session = env.createSession(model.toString(), opts);
            if (!classifyIo()) {
                disposeNative();
                return false;
            }
            if (!passthrough.compile(PASSTHROUGH_VERT, PASSTHROUGH_FRAG)) {
                disposeNative();
                return false;
            }
            ready = true;
            Interframe.LOGGER.info("[Interframe] Neural backend ready: model={}, imageInputs={}, timestep={}, output={}",
                    model.getFileName(), imageInputs, timestepInput, outputName);
            return true;
        } catch (Throwable t) {
            Interframe.LOGGER.error("[Interframe] Failed to load neural model; falling back to built-in.", t);
            disposeNative();
            return false;
        }
    }

    /** Try CUDA then DirectML (reflectively, so we compile/run regardless of which ORT build is present), else CPU. */
    private static void addBestProvider(OrtSession.SessionOptions opts) {
        for (String m : new String[]{"addCUDA", "addDirectML"}) {
            try {
                Method method = opts.getClass().getMethod(m, int.class);
                method.invoke(opts, 0);
                Interframe.LOGGER.info("[Interframe] ONNX execution provider: {}", m.substring(3));
                return;
            } catch (Throwable ignored) {
                // provider not available in this build — try the next, then CPU
            }
        }
        Interframe.LOGGER.info("[Interframe] ONNX execution provider: CPU (no GPU provider available).");
    }

    private boolean classifyIo() throws Exception {
        Map<String, NodeInfo> inputs = session.getInputInfo();
        for (Map.Entry<String, NodeInfo> e : inputs.entrySet()) {
            if (e.getValue().getInfo() instanceof TensorInfo ti && hasChannel(ti, 3)) {
                imageInputs.add(e.getKey());
            }
        }
        if (imageInputs.size() < 2) {
            Interframe.LOGGER.warn("[Interframe] Model has {} RGB image inputs; need ≥2. Unsupported signature.",
                    imageInputs.size());
            return false;
        }
        for (String key : inputs.keySet()) {
            if (!imageInputs.contains(key)) {
                timestepInput = key; // first non-image input acts as the timestep
                break;
            }
        }
        Map<String, NodeInfo> outputs = session.getOutputInfo();
        if (outputs.size() != 1) {
            Interframe.LOGGER.warn("[Interframe] Model has {} outputs; need exactly 1.", outputs.size());
            return false;
        }
        outputName = outputs.keySet().iterator().next();
        return true;
    }

    private static boolean hasChannel(TensorInfo ti, int channels) {
        long[] shape = ti.getShape();
        // NCHW: channel is dim 1. Treat dynamic (-1) as compatible.
        return shape.length == 4 && (shape[1] == channels || shape[1] == -1);
    }

    @Override
    public boolean isAvailable() {
        return ready;
    }

    @Override
    public void synthesize(SynthContext ctx) {
        if (!ready) {
            return;
        }
        try {
            ensureScratch(ctx.width, ctx.height);
            downscaleAndRead(ctx.prevColorTex, ctx.width, ctx.height, readbackPrev);
            downscaleAndRead(ctx.currColorTex, ctx.width, ctx.height, readbackCurr);

            float[] result = runModel(ctx.phase);
            if (result == null) {
                return;
            }
            uploadResult(result);
            drawFullscreen(ctx);
        } catch (Throwable t) {
            Interframe.LOGGER.error("[Interframe] Neural synth failed this frame; disabling neural backend.", t);
            ready = false; // stop hammering a broken model; FrameGenerator will fall back next frame
        }
    }

    private void ensureScratch(int w, int h) {
        int tw = w, th = h;
        if (Math.max(w, h) > MAX_INFER_EDGE) {
            float scale = MAX_INFER_EDGE / (float) Math.max(w, h);
            tw = Math.max(32, Math.round(w * scale) / 32 * 32);
            th = Math.max(32, Math.round(h * scale) / 32 * 32);
        }
        if (tw == iw && th == ih && inferTex != 0) {
            return;
        }
        iw = tw;
        ih = th;

        if (inferTex != 0) GL11C.glDeleteTextures(inferTex);
        if (outTex != 0) GL11C.glDeleteTextures(outTex);
        if (inferFbo != 0) GL30C.glDeleteFramebuffers(inferFbo);
        if (srcFbo == 0) srcFbo = GL30C.glGenFramebuffers();

        inferTex = newColorTex(iw, ih);
        outTex = newColorTex(iw, ih);
        inferFbo = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, inferFbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11C.GL_TEXTURE_2D, inferTex, 0);

        int pixels = iw * ih * 4;
        readbackPrev = realloc(readbackPrev, pixels);
        readbackCurr = realloc(readbackCurr, pixels);
        upload = realloc(upload, pixels);
        Interframe.LOGGER.info("[Interframe] Neural inference resolution set to {}x{}.", iw, ih);
    }

    private static int newColorTex(int w, int h) {
        int t = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, t);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, w, h, 0,
                GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        return t;
    }

    /** GPU-downscale a full-res source texture into inferTex, then read it back as normalized floats. */
    private void downscaleAndRead(int srcTex, int w, int h, FloatBuffer dst) {
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, srcFbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_READ_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11C.GL_TEXTURE_2D, srcTex, 0);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, inferFbo);
        GL30C.glBlitFramebuffer(0, 0, w, h, 0, 0, iw, ih, GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_LINEAR);

        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, inferFbo);
        dst.clear();
        GL11C.glReadPixels(0, 0, iw, ih, GL11C.GL_RGBA, GL11C.GL_FLOAT, dst);
    }

    private float[] runModel(float phase) throws Exception {
        long[] imgShape = {1, 3, ih, iw};
        OnnxTensor t0 = OnnxTensor.createTensor(env, FloatBuffer.wrap(planarRgb(readbackPrev)), imgShape);
        OnnxTensor t1 = OnnxTensor.createTensor(env, FloatBuffer.wrap(planarRgb(readbackCurr)), imgShape);
        Map<String, OnnxTensor> feed = new LinkedHashMap<>();
        feed.put(imageInputs.get(0), t0);
        feed.put(imageInputs.get(1), t1);
        OnnxTensor ts = null;
        if (timestepInput != null) {
            ts = OnnxTensor.createTensor(env, FloatBuffer.wrap(new float[]{phase}), new long[]{1, 1, 1, 1});
            feed.put(timestepInput, ts);
        }
        try (OrtSession.Result out = session.run(feed)) {
            for (Map.Entry<String, OnnxValue> e : out) {
                return flatten(e.getValue().getValue());
            }
            return null;
        } finally {
            t0.close();
            t1.close();
            if (ts != null) ts.close();
        }
    }

    /** RGBA interleaved [0,1] floats -> planar RGB NCHW float[3*ih*iw]. */
    private float[] planarRgb(FloatBuffer rgba) {
        float[] out = new float[3 * iw * ih];
        int plane = iw * ih;
        for (int p = 0; p < plane; p++) {
            int base = p * 4;
            out[p] = rgba.get(base);
            out[plane + p] = rgba.get(base + 1);
            out[2 * plane + p] = rgba.get(base + 2);
        }
        return out;
    }

    /** Accepts the common float[1][3][h][w] tensor shape and flattens to a planar RGB float[]. */
    private float[] flatten(Object value) {
        if (value instanceof float[][][][] a && a.length >= 1 && a[0].length >= 3) {
            float[][][] img = a[0];
            float[] out = new float[3 * iw * ih];
            int plane = iw * ih;
            for (int c = 0; c < 3; c++) {
                for (int y = 0; y < ih; y++) {
                    for (int x = 0; x < iw; x++) {
                        out[c * plane + y * iw + x] = img[c][y][x];
                    }
                }
            }
            return out;
        }
        Interframe.LOGGER.warn("[Interframe] Unexpected model output type {}; disabling neural backend.",
                value == null ? "null" : value.getClass());
        return null;
    }

    private void uploadResult(float[] planar) {
        int plane = iw * ih;
        upload.clear();
        for (int p = 0; p < plane; p++) {
            upload.put(clamp01(planar[p]));
            upload.put(clamp01(planar[plane + p]));
            upload.put(clamp01(planar[2 * plane + p]));
            upload.put(1.0f);
        }
        upload.flip();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, outTex);
        GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, iw, ih, GL11C.GL_RGBA, GL11C.GL_FLOAT, upload);
    }

    private void drawFullscreen(SynthContext ctx) {
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
        passthrough.bind();
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, outTex);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, ctx.currColorTex);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE2);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, ctx.hasGui ? ctx.guiTex : 0);
        passthrough.setInt("src", 0);
        passthrough.setInt("worldTex", 1);
        passthrough.setInt("guiTex", 2);
        passthrough.setInt("hasGui", ctx.hasGui ? 1 : 0);
        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDisable(GL11C.GL_BLEND);
        GL11C.glViewport(0, 0, ctx.width, ctx.height);
        passthrough.drawFullscreen();
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static FloatBuffer realloc(FloatBuffer old, int floats) {
        if (old != null) {
            MemoryUtil.memFree(old);
        }
        return MemoryUtil.memAllocFloat(floats);
    }

    private void disposeNative() {
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        session = null;
        // OrtEnvironment is a shared singleton; leave it.
        imageInputs.clear();
        timestepInput = null;
        outputName = null;
    }

    @Override
    public void dispose() {
        disposeNative();
        passthrough.dispose();
        if (inferTex != 0) { GL11C.glDeleteTextures(inferTex); inferTex = 0; }
        if (outTex != 0) { GL11C.glDeleteTextures(outTex); outTex = 0; }
        if (inferFbo != 0) { GL30C.glDeleteFramebuffers(inferFbo); inferFbo = 0; }
        if (srcFbo != 0) { GL30C.glDeleteFramebuffers(srcFbo); srcFbo = 0; }
        if (readbackPrev != null) { MemoryUtil.memFree(readbackPrev); readbackPrev = null; }
        if (readbackCurr != null) { MemoryUtil.memFree(readbackCurr); readbackCurr = null; }
        if (upload != null) { MemoryUtil.memFree(upload); upload = null; }
        iw = ih = -1;
        ready = false;
    }
}
