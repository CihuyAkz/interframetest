package dev.fallingcloud.interframe;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.fallingcloud.interframe.compat.FoveaBridge;
import dev.fallingcloud.interframe.gl.GlGuard;
import dev.fallingcloud.interframe.gl.GpuInterop;
import dev.fallingcloud.interframe.synth.FrameSynthesizer;
import dev.fallingcloud.interframe.synth.SynthContext;
import dev.fallingcloud.interframe.synth.SynthesizerFactory;
import net.minecraft.client.Minecraft;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL30C;

import java.lang.reflect.Method;

/**
 * The frame-generation engine. Runs entirely on the render thread, driven from {@code MixinWindow} right
 * before Minecraft swaps buffers.
 *
 * <p>Per real frame N (in {@link #onPresent()}):
 * <ol>
 *   <li>Capture the final composited image from the main render target into a ping-pong texture (a GPU
 *       framebuffer blit — so it sits downstream of Sodium, Iris and Foveate's VRS). Scene depth was
 *       already copied at the end of the level render (before vanilla clears it for the hand/GUI).</li>
 *   <li>If a previous frame N-1 is held, synthesise the in-between frames and present each with its own
 *       {@link RenderSystem#flipFrame} swap, <b>paced onto an even schedule</b> (below).</li>
 *   <li>Re-blit N to the back buffer so Minecraft's own swap (which runs right after this returns) shows
 *       the real frame. Net display order: …, N-1, [in-between…], N, [in-between…], N+1, …</li>
 * </ol>
 *
 * <p><b>Pacing is what makes frame generation visible.</b> Swapping a synthetic frame and the real frame
 * back-to-back means the monitor never scans the synthetic one out — you pay for it and see nothing. So:
 * <ul>
 *   <li><b>V-Sync off:</b> the presents are spread across the measured real-frame interval — synthetic
 *       frame i at {@code T0 + i·interval/(g+1)}, and the real frame is held to land on its own slot.
 *       The hold is the classic interpolation latency cost (~half a frame at 2x); the pacing strength
 *       setting scales it. The waits are spin-based but hitch-hardened and capped, and the generator
 *       subtracts its own added delay from the interval estimate so pacing can never feed back into
 *       itself and snowball.</li>
 *   <li><b>V-Sync on:</b> every swap already blocks until a vblank, so vblank IS the pacer. Instead of
 *       spinning, we insert only as many frames as there are <em>empty vblank slots</em> in the measured
 *       interval (e.g. 30 real FPS on a 60 Hz panel has exactly one free slot → perfect 2x). At/near the
 *       refresh rate nothing is inserted — there is no room, and blindly swapping would halve the real
 *       rate.</li>
 * </ul>
 *
 * <p>All view math is exact: per synthetic frame the relative pose between the synthesised viewpoint and
 * each capture is computed from the cameras' orientation quaternions (slerp for the in-between fraction)
 * plus their world positions, and handed to the synthesiser as a rotation matrix + view-space translation.
 * TIMEWARP extrapolates along a time-based angular/linear velocity estimate (rad/s and m/s, so variable
 * frame times don't jitter the prediction).
 */
public final class FrameGenerator {
    public static final FrameGenerator INSTANCE = new FrameGenerator();

    /** Pass real frames straight through for this many frames after any hitch / transition, so the world
     *  load and menu exits stay clean instead of warping a stale camera estimate. */
    private static final int WARMUP_FRAMES = 5;
    /** A real-frame interval longer than this AND far above the running average is treated as a hitch. */
    private static final long HITCH_NANOS = 40_000_000L; // 40 ms (~25 fps instantaneous)
    /** Hard ceiling on a single pacing wait, so it can never stall the render thread (freeze). */
    private static final long MAX_WAIT_NANOS = 30_000_000L; // 30 ms
    /** Camera movement above this (blocks per frame) is a teleport: treat as a cut, never warp across. */
    private static final double TELEPORT_BLOCKS = 5.0;
    /** Angular velocity below this (rad/s, ~1.7°/s) snaps to zero so the warp is rock-still at rest. */
    private static final float ANG_VEL_SNAP = 0.03f;
    /** Linear velocity below this (m/s) snaps to zero. */
    private static final float POS_VEL_SNAP = 0.05f;

    private final GlGuard guard = new GlGuard();
    private final SynthContext ctx = new SynthContext();

    private FrameSynthesizer synth;
    private InterframeConfig.Mode synthMode;

    // Ping-pong capture: textures[idx] holds the newest frame, textures[1-idx] the previous one. These
    // are the FULL composited image (world + hand + GUI), captured at present time — used as the
    // unwarped "guiTex" reference so hand/HUD pixels can be copied through untouched (see worldColorTex
    // below, which is what actually gets warped).
    private final int[] colorTex = {0, 0};
    private final int[] captureFbo = {0, 0};
    private final CameraSnapshot[] cameras = {CameraSnapshot.INVALID, CameraSnapshot.INVALID};
    private int idx = 0;
    private boolean prevValid;

    private int builtW = -1;
    private int builtH = -1;

    // Scene depth, copied at the end of the level render (single texture: only the current frame's
    // depth is needed — the surface found there is reprojected into BOTH captures).
    private int depthTex;
    private int depthW = -1;
    private int depthH = -1;
    private boolean depthCopied;
    private boolean depthCopyBroken;
    private int depthProbesLeft;

    // World-only colour (no hand/GUI), copied at the end of the level render — the same moment the
    // depth is copied, before vanilla draws the hand and HUD. This is what gets warped/blended; pairing
    // it against the FULL frame in colorTex[] at the same screen position is how the shader tells
    // "moved because the world moved" apart from "moved because it's hand/HUD" (see BlendReprojectSynthesizer).
    // pendingWorldColorTex/pendingWorldFbo hold the single most-recent capture; worldColorTex[]/
    // worldCaptureFbo[] are the ping-pong pair consumed by the synthesiser, filled from the pending
    // texture once per real frame at present time (mirrors how colorTex[] itself is filled).
    private int pendingWorldColorTex;
    private int pendingWorldFbo;
    private int worldColorW = -1;
    private int worldColorH = -1;
    private boolean worldColorCopied;
    private boolean worldColorCopyBroken;
    private final int[] worldColorTex = {0, 0};
    private final int[] worldCaptureFbo = {0, 0};
    /** True for slot i when worldColorTex[i] holds a valid pre-hand/GUI capture for that real frame. */
    private final boolean[] worldColorValid = {false, false};

    // Set from the level-render mixin each world frame; consumed at present time.
    private volatile CameraSnapshot pendingCamera = CameraSnapshot.INVALID;

    // Smoothed camera velocity for TIMEWARP prediction: angular (rad/s, camera-local axis-angle) and
    // linear (m/s, world space). Time-based so variable frame times don't jitter the extrapolation.
    private final Vector3f angVel = new Vector3f();
    private final Vector3f posVel = new Vector3f();
    private boolean velInit;

    // Real-frame interval tracking + warm-up after hitches/transitions. selfDelayLastFrame is the
    // presentation time WE added last frame (pacing waits / inserted vblanks); subtracting it from the
    // measured interval yields the natural render interval, which keeps pacing from feeding back.
    private long lastPresentNanos;
    private long avgPresentNanos;
    private long selfDelayLastFrame;
    private int validStreak;

    // Monitor refresh period (for V-Sync slot fitting), re-queried occasionally.
    private long refreshPeriodNanos = 16_666_667L;
    private int refreshRecheck;

    // Iris shaderpack detection (reflective; depth under a shaderpack isn't reliably the world's).
    private Method irisShaderInUse;
    private Object irisApi;
    private boolean irisLookupDone;
    private boolean irisActive;
    private int irisRecheck;

    private boolean loggedEngage;
    private boolean loggedIrisDepthOff;
    private boolean loggedFoveaDepthOff;

    // JOML scratch (no per-frame allocation).
    private final Quaternionf qMid = new Quaternionf();
    private final Quaternionf qWork = new Quaternionf();
    private final Quaternionf qEase = new Quaternionf();
    private final Vector3f vWork = new Vector3f();

    private FrameGenerator() {
    }

    /** Called by the level-render mixin with the world camera for the frame about to be drawn. */
    public void onWorldCamera(CameraSnapshot snapshot) {
        this.pendingCamera = snapshot;
        this.depthCopied = false; // depth (if any) will be re-captured at the end of this level render
        this.worldColorCopied = false; // likewise the pre-hand/GUI colour snapshot
    }

    /**
     * Called at the end of {@code LevelRenderer.renderLevel} — the last moment the true scene depth
     * exists in the main target (vanilla clears it right after, for the hand and GUI). Copies it into
     * our own depth texture for the translational reprojection.
     */
    public void onWorldDepth() {
        InterframeConfig cfg = InterframeConfig.get();
        if (!cfg.enabled || !cfg.translationWarp || cfg.mode() == InterframeConfig.Mode.BLEND
                || depthCopyBroken) {
            return;
        }
        if (cfg.inGameOnly && !inGame()) {
            return;
        }
        if (irisShaderPackActive()) {
            if (!loggedIrisDepthOff) {
                loggedIrisDepthOff = true;
                Interframe.LOGGER.info("[Interframe] Iris shaderpack active — translational reprojection "
                        + "disabled (the vanilla depth buffer isn't the shaderpack's); rotation warp still on.");
            }
            return;
        }
        if (FoveaBridge.warpedFrame()) {
            // Fovea center-priority frame: the depth here lives in Fovea's scaled SOURCE space while the
            // colour we pair it with is the warped PRESENTED image — a parallax warp reading this depth
            // misplaces everything with parallax (entities, water, ice). Skip the copy; the synthesiser
            // degrades smoothly to rotation-only for this frame. Uniform/still frames keep full parallax.
            if (!loggedFoveaDepthOff) {
                loggedFoveaDepthOff = true;
                Interframe.LOGGER.info("[Interframe] Fovea center-priority frame detected — translational "
                        + "reprojection paused during warped frames (depth space mismatch); rotation warp "
                        + "still on, full parallax resumes on uniform/still frames.");
            }
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.getMainRenderTarget();
        if (target == null || target.width <= 0 || target.height <= 0) {
            return;
        }
        int w = target.width;
        int h = target.height;

        int prevReadFbo = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int prevTex2D = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        try {
            boolean fresh = ensureDepthTex(w, h);
            boolean probe = fresh || depthProbesLeft > 0;
            if (probe) {
                while (GL11C.glGetError() != GL11C.GL_NO_ERROR) {
                    // drain unrelated pre-existing errors so the probe below is attributable to the copy
                }
            }
            int mainDepthFbo = GpuInterop.mainDepthFbo(target);
            if (mainDepthFbo < 0) {
                depthCopyBroken = true;
                return;
            }
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, mainDepthFbo);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, depthTex);
            GL11C.glCopyTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
            if (probe) {
                int err = GL11C.glGetError();
                if (err != GL11C.GL_NO_ERROR) {
                    depthCopyBroken = true;
                    Interframe.LOGGER.warn("[Interframe] Depth copy rejected by the driver (GL error 0x{}); "
                            + "translational reprojection disabled, rotation warp still on.",
                            Integer.toHexString(err));
                    return;
                }
                if (!fresh) {
                    depthProbesLeft--;
                }
            }
            depthCopied = true;
        } finally {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prevTex2D);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, prevReadFbo);
        }
    }

    /**
     * Called at the end of {@code LevelRenderer.renderLevel}, right alongside {@link #onWorldDepth()} —
     * the last moment the composited image contains the WORLD ONLY, before vanilla draws the held item
     * and then the HUD on top. Copies it into {@link #pendingWorldColorTex} so the synthesiser can warp
     * the world without ever touching hand/HUD pixels; {@link #onPresent()} pairs it against the FULL
     * frame (captured after hand/HUD) so the shader can tell the two apart. See {@code preserveHud}.
     */
    public void onWorldColor() {
        InterframeConfig cfg = InterframeConfig.get();
        if (!cfg.enabled || !cfg.preserveHud || worldColorCopyBroken) {
            return;
        }
        if (cfg.inGameOnly && !inGame()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.getMainRenderTarget();
        if (target == null || target.width <= 0 || target.height <= 0) {
            return;
        }
        int w = target.width;
        int h = target.height;

        int prevReadFbo = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int prevTex2D = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        try {
            boolean fresh = ensureWorldColorTex(w, h);
            if (fresh) {
                while (GL11C.glGetError() != GL11C.GL_NO_ERROR) {
                    // drain unrelated pre-existing errors so the probe below is attributable to this copy
                }
            }
            int mainColorFbo = GpuInterop.mainColorFbo(target);
            if (mainColorFbo < 0) {
                worldColorCopyBroken = true;
                return;
            }
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, mainColorFbo);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, pendingWorldColorTex);
            GL11C.glCopyTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
            if (fresh) {
                int err = GL11C.glGetError();
                if (err != GL11C.GL_NO_ERROR) {
                    worldColorCopyBroken = true;
                    Interframe.LOGGER.warn("[Interframe] World-colour copy rejected by the driver (GL error "
                            + "0x{}); \"Preserve Hand & HUD\" disabled for this session.",
                            Integer.toHexString(err));
                    return;
                }
            }
            worldColorCopied = true;
        } finally {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prevTex2D);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, prevReadFbo);
        }
    }

    /** Called at the head of {@code Window.updateDisplay()}, before Minecraft's own buffer swap. */
    public void onPresent() {
        InterframeConfig cfg = InterframeConfig.get();
        if (!cfg.enabled) {
            prevValid = false;
            return;
        }
        if (cfg.inGameOnly && !inGame()) {
            prevValid = false;
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.getMainRenderTarget();
        if (target == null) {
            return;
        }
        int w = target.width;
        int h = target.height;
        if (w <= 0 || h <= 0) {
            return;
        }

        ensureSynthesizer(cfg);
        if (synth == null || !synth.isAvailable()) {
            return;
        }

        int mainColorFbo = GpuInterop.mainColorFbo(target);
        if (mainColorFbo < 0) {
            // Can't read the main render target this frame under whatever 1.21.x API shape is actually
            // running — skip generation entirely rather than presenting garbage. See GpuInterop /
            // PORTING_NOTES.md.
            prevValid = false;
            return;
        }

        guard.capture();
        try {
            ensureTextures(w, h);

            // Advance ping-pong and capture frame N into the new "current" slot: the FULL image (world +
            // hand + HUD) as before, plus — if this frame's pre-hand/GUI snapshot was captured — the
            // WORLD-only image into the matching ping-pong slot, so both line up under the same idx.
            idx = 1 - idx;
            blitColor(mainColorFbo, captureFbo[idx], w, h);
            boolean worldSlotValid = worldColorCopied && cfg.preserveHud;
            if (worldSlotValid) {
                blitColor(pendingWorldFbo, worldCaptureFbo[idx], w, h);
            }
            worldColorValid[idx] = worldSlotValid;
            cameras[idx] = pendingCamera;

            int prevIdx = 1 - idx;
            if (prevValid) {
                boolean realWritten = generate(cfg, w, h, prevIdx, idx);
                if (!realWritten) {
                    // Interpolation modes: restore the pristine real frame N for Minecraft's upcoming swap.
                    blitColor(captureFbo[idx], 0, w, h);
                }
            }
            prevValid = true;
        } catch (Throwable t) {
            Interframe.LOGGER.error("[Interframe] Frame generation error; pausing this frame.", t);
            prevValid = false;
        } finally {
            depthCopied = false;
            worldColorCopied = false;
            guard.restore();
        }
    }

    /**
     * Produce and present the synthetic frames for this real frame.
     *
     * @return true if this method already wrote the frame Minecraft will swap (TIMEWARP, which presents a
     *     latency-compensated forward-warp as the "real" frame); false if the caller should restore the
     *     pristine captured frame (the interpolation modes).
     */
    private boolean generate(InterframeConfig cfg, int w, int h, int prevIdx, int currIdx) {
        CameraSnapshot prev = cameras[prevIdx];
        CameraSnapshot curr = cameras[currIdx];
        boolean vsync = vsyncOn();

        // Real-frame interval, corrected by the presentation time we ourselves added last frame so the
        // estimate tracks the natural render cadence. A transient spike (world load, menu exit, chunk
        // hitch) makes the motion estimate meaningless, so we detect it and warm up rather than warping
        // across it.
        long now = System.nanoTime();
        long raw = lastPresentNanos == 0 ? 0 : now - lastPresentNanos;
        lastPresentNanos = now;
        long interval = raw - selfDelayLastFrame;
        if (interval < 0 || interval > raw) {
            interval = raw;
        }
        selfDelayLastFrame = 0;
        boolean hitch = interval > HITCH_NANOS && (avgPresentNanos == 0 || interval > 3 * avgPresentNanos);
        if (interval > 0 && !hitch) {
            avgPresentNanos = avgPresentNanos == 0 ? interval : (avgPresentNanos * 7 + interval) / 8;
        }

        boolean camOk = prev.valid && curr.valid;
        boolean cut = camOk
                && (curr.deltaAngleDeg(prev) > cfg.maxWarpDegrees()
                        || curr.distanceTo(prev) > TELEPORT_BLOCKS);

        // Velocity estimate (TIMEWARP). Time-based, reset on teleports/cuts and unusable cameras so a
        // single big jump can't smear the next second of frames; snapped to zero at rest so the warp is
        // perfectly still when the mouse is.
        if (!camOk || cut || hitch) {
            angVel.zero();
            posVel.zero();
            velInit = false;
        } else if (raw > 0) {
            float dtSec = Math.min(0.1f, Math.max(0.001f, raw / 1_000_000_000f));
            qWork.set(prev.rot).conjugate().mul(curr.rot);
            toAxisAngle(qWork, vWork).div(dtSec); // rad/s, camera-local
            if (vWork.length() < ANG_VEL_SNAP) {
                vWork.zero();
            }
            if (velInit) {
                angVel.lerp(vWork, 0.6f);
            } else {
                angVel.set(vWork);
            }
            vWork.set((float) (curr.x - prev.x), (float) (curr.y - prev.y), (float) (curr.z - prev.z))
                    .div(dtSec); // m/s, world
            if (vWork.length() < POS_VEL_SNAP) {
                vWork.zero();
            }
            if (velInit) {
                posVel.lerp(vWork, 0.6f);
            } else {
                posVel.set(vWork);
            }
            velInit = true;
        }

        // Warm-up: after a hitch or any unusable frame, pass real frames straight through for a few frames.
        // This keeps entering a world / leaving a menu clean — Interframe stays out of the way until the
        // frame timing and camera have settled.
        if (hitch || !camOk) {
            validStreak = 0;
        } else if (validStreak < WARMUP_FRAMES) {
            validStreak++;
        }
        if (validStreak < WARMUP_FRAMES) {
            return false; // pass through: caller restores the pristine real frame, no synth / no warp
        }

        boolean warpValid = cfg.mode() != InterframeConfig.Mode.BLEND && camOk && !cut;
        boolean translate = warpValid && cfg.translationWarp && depthCopied && depthTex != 0;

        // Warp the world-only captures when both slots of the pair have one (preserveHud on and neither
        // frame's snapshot failed/was skipped); otherwise fall back to warping the FULL frame exactly
        // like before, so a hitch in the HUD capture can never blank the screen — it just means the hand
        // /HUD ride along with the warp for that one frame instead of staying static.
        boolean worldPairValid = worldColorValid[prevIdx] && worldColorValid[currIdx];
        ctx.width = w;
        ctx.height = h;
        ctx.prevColorTex = worldPairValid ? worldColorTex[prevIdx] : colorTex[prevIdx];
        ctx.currColorTex = worldPairValid ? worldColorTex[currIdx] : colorTex[currIdx];
        ctx.depthTex = depthTex;
        ctx.hasDepth = translate;
        // Hand/HUD passthrough reference: only meaningful (and only needed) when we actually warped the
        // world-only capture above — if we fell back to the full frame there's nothing to punch through.
        ctx.hasGui = worldPairValid;
        ctx.guiTex = worldPairValid ? colorTex[currIdx] : 0;
        ctx.depthM22 = curr.projM22;
        ctx.depthM32 = curr.projM32;

        // How many frames to insert. With V-Sync each swap blocks until a vblank, so we only insert as
        // many frames as there are EMPTY vblank slots in the natural interval — inserting more would
        // push the real rate down instead of filling gaps.
        int g = cfg.generatedPerReal();
        long period = refreshPeriodNanos();
        if (vsync) {
            long natural = avgPresentNanos > 0 ? avgPresentNanos : period;
            int slots = Math.round(natural / (float) period);
            g = Math.max(0, Math.min(g, slots - 1));
        }

        float strength = cfg.reprojectStrength();
        long windowHandle = Minecraft.getInstance().getWindow().getWindow();
        long frameBase = avgPresentNanos > 0 ? avgPresentNanos : 16_666_667L;
        long slice = vsync ? 0 : Math.min((long) (frameBase * cfg.pacingStrength() / (g + 1)), MAX_WAIT_NANOS);
        long t0 = now;
        long waited = 0;

        if (cfg.mode() == InterframeConfig.Mode.TIMEWARP) {
            float leadFrames = cfg.lookAhead();
            float intervalSec = frameBase / 1_000_000_000f;
            // Present the freshest (least-extrapolated) view IMMEDIATELY — zero added latency — then
            // progressively further extrapolations on the later schedule slots. The frame left for
            // Minecraft's own swap is the most extrapolated one, so the pacing hold it sits behind is
            // already compensated by its own prediction: smooth AND leading the motion.
            for (int j = 0; j < g; j++) {
                float lead = leadFrames + j / (float) (g + 1);
                setupForward(curr, lead * intervalSec, strength, warpValid, translate);
                drawSynth(w, h);
                if (!vsync) {
                    waited += waitUntil(t0 + j * slice);
                }
                RenderSystem.flipFrame(windowHandle, null);
            }
            setupForward(curr, (leadFrames + g / (float) (g + 1)) * intervalSec, strength, warpValid, translate);
            drawSynth(w, h);
            if (!vsync && g > 0) {
                waited += waitUntil(t0 + g * slice);
            }
            selfDelayLastFrame = waited + (vsync ? (long) g * period : 0);
            logEngage(cfg, g, vsync, translate);
            return true;
        }

        // Interpolation modes: insert in-between frames on their schedule slots, then hold briefly so
        // the real frame lands on its own slot too (even spacing is what reads as smoothness).
        for (int i = 1; i <= g; i++) {
            setupInterpolated(prev, curr, i / (float) (g + 1), strength, warpValid, translate);
            drawSynth(w, h);
            if (!vsync) {
                waited += waitUntil(t0 + (i - 1) * slice);
            }
            RenderSystem.flipFrame(windowHandle, null);
        }
        if (!vsync && g > 0) {
            waited += waitUntil(t0 + g * slice);
        }
        selfDelayLastFrame = waited + (vsync ? (long) g * period : 0);
        if (g > 0) {
            logEngage(cfg, g, vsync, translate);
        }
        return false;
    }

    /**
     * Fill the synth context for an interpolated frame at fraction {@code t} between the two captures:
     * orientation slerp + position lerp define the synthesised viewpoint, and the relative pose to each
     * capture (eased by {@code strength}) is what the shader warps with. FOV tangents are interpolated
     * too, so sprint/zoom FOV transitions glide instead of popping.
     */
    private void setupInterpolated(CameraSnapshot prev, CameraSnapshot curr, float t,
                                   float strength, boolean warpValid, boolean translate) {
        ctx.forward = false;
        ctx.phase = t;
        if (!warpValid) {
            float tx = curr.valid ? curr.tanHalfFovX : 1f;
            float ty = curr.valid ? curr.tanHalfFovY : 1f;
            ctx.tanMidX = ctx.tanPrevX = ctx.tanCurrX = tx;
            ctx.tanMidY = ctx.tanPrevY = ctx.tanCurrY = ty;
            ctx.warpPrev.identity();
            ctx.warpCurr.identity();
            ctx.transPrev.zero();
            ctx.transCurr.zero();
            return;
        }

        qMid.set(prev.rot).slerp(curr.rot, t);
        float dx = (float) (curr.x - prev.x);
        float dy = (float) (curr.y - prev.y);
        float dz = (float) (curr.z - prev.z);

        // prev capture: rotation = ease(qPrev^-1 * qMid), translation = qPrev^-1 * (Cmid - Cprev).
        qWork.set(prev.rot).conjugate().mul(qMid);
        easeRotation(qWork, strength).get(ctx.warpPrev);
        vWork.set(dx * t, dy * t, dz * t).mul(strength);
        prev.rot.transformInverse(vWork);
        if (translate) {
            ctx.transPrev.set(vWork);
        } else {
            ctx.transPrev.zero();
        }

        // curr capture: rotation = ease(qCurr^-1 * qMid), translation = qCurr^-1 * (Cmid - Ccurr).
        qWork.set(curr.rot).conjugate().mul(qMid);
        easeRotation(qWork, strength).get(ctx.warpCurr);
        vWork.set(dx * (t - 1f), dy * (t - 1f), dz * (t - 1f)).mul(strength);
        curr.rot.transformInverse(vWork);
        if (translate) {
            ctx.transCurr.set(vWork);
        } else {
            ctx.transCurr.zero();
        }

        ctx.tanPrevX = prev.tanHalfFovX;
        ctx.tanPrevY = prev.tanHalfFovY;
        ctx.tanCurrX = curr.tanHalfFovX;
        ctx.tanCurrY = curr.tanHalfFovY;
        ctx.tanMidX = prev.tanHalfFovX + (curr.tanHalfFovX - prev.tanHalfFovX) * t;
        ctx.tanMidY = prev.tanHalfFovY + (curr.tanHalfFovY - prev.tanHalfFovY) * t;
    }

    /**
     * Fill the synth context for a TIMEWARP frame: extrapolate the newest capture {@code leadSec} seconds
     * along the smoothed angular/linear velocity, so the image leads the motion instead of lagging it.
     */
    private void setupForward(CameraSnapshot curr, float leadSec, float strength,
                              boolean warpValid, boolean translate) {
        ctx.forward = true;
        ctx.phase = 1f;
        float tx = curr.valid ? curr.tanHalfFovX : 1f;
        float ty = curr.valid ? curr.tanHalfFovY : 1f;
        ctx.tanMidX = ctx.tanPrevX = ctx.tanCurrX = tx;
        ctx.tanMidY = ctx.tanPrevY = ctx.tanCurrY = ty;
        ctx.warpPrev.identity();
        ctx.transPrev.zero();

        if (!warpValid || !velInit) {
            ctx.warpCurr.identity();
            ctx.transCurr.zero();
            return;
        }

        // Rotation: future-ray -> curr view is exactly the local delta rotation over leadSec.
        vWork.set(angVel).mul(leadSec);
        float angle = vWork.length();
        if (angle > 1e-6f) {
            vWork.div(angle);
            qWork.rotationAxis(angle * strength, vWork.x, vWork.y, vWork.z);
            qWork.get(ctx.warpCurr);
        } else {
            ctx.warpCurr.identity();
        }

        if (translate) {
            vWork.set(posVel).mul(leadSec * strength);
            curr.rot.transformInverse(vWork);
            ctx.transCurr.set(vWork);
        } else {
            ctx.transCurr.zero();
        }
    }

    /** Ease a relative rotation toward identity by {@code strength} (1 = full warp, 0 = none). */
    private Quaternionf easeRotation(Quaternionf q, float strength) {
        if (strength >= 0.999f) {
            return q;
        }
        qEase.identity().slerp(q, strength);
        return qEase;
    }

    /** Extract the axis-angle vector (axis * angle, radians) of a unit quaternion, shortest arc. */
    private static Vector3f toAxisAngle(Quaternionf q, Vector3f out) {
        float w = Math.min(1f, Math.abs(q.w));
        float angle = 2f * (float) Math.acos(w);
        float s = (float) Math.sqrt(Math.max(0f, 1f - w * w));
        if (s < 1e-6f || angle < 1e-6f) {
            return out.zero();
        }
        float sign = q.w < 0 ? -1f : 1f; // canonicalise so we always take the short way around
        return out.set(q.x, q.y, q.z).mul(sign * angle / s);
    }

    private void drawSynth(int w, int h) {
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
        GL11C.glViewport(0, 0, w, h);
        synth.synthesize(ctx);
    }

    /**
     * Spin until {@code targetNanos}, hard-capped so a bad estimate can never stall the render thread.
     * Returns the time actually waited. The spin overlaps in-flight GPU work, so when GPU-bound (the
     * scenario frame generation targets) it costs no real frame rate.
     */
    private static long waitUntil(long targetNanos) {
        long start = System.nanoTime();
        if (targetNanos - start > MAX_WAIT_NANOS) {
            targetNanos = start + MAX_WAIT_NANOS;
        }
        long t = start;
        while (t < targetNanos) {
            Thread.onSpinWait();
            t = System.nanoTime();
        }
        return t - start;
    }

    private void logEngage(InterframeConfig cfg, int g, boolean vsync, boolean translate) {
        if (loggedEngage) {
            return;
        }
        loggedEngage = true;
        Interframe.LOGGER.info("[Interframe] Frame generation engaged: {} — mode {}, {} inserted frame(s) "
                + "per real frame, paced by {}, translation warp {}.",
                synth.name(), cfg.mode(), g,
                vsync ? "V-Sync vblank slots" : "scheduler (" + cfg.pacingStrengthPercent() + "%)",
                translate ? "on (depth-aware)" : "off");
    }

    private static boolean vsyncOn() {
        try {
            return Minecraft.getInstance().options.enableVsync().get();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Monitor refresh period, re-queried every ~2s (fullscreen monitor if any, else the primary). */
    private long refreshPeriodNanos() {
        if (refreshRecheck-- <= 0) {
            refreshRecheck = 120;
            try {
                long window = Minecraft.getInstance().getWindow().getWindow();
                long monitor = GLFW.glfwGetWindowMonitor(window);
                if (monitor == 0) {
                    monitor = GLFW.glfwGetPrimaryMonitor();
                }
                GLFWVidMode mode = monitor != 0 ? GLFW.glfwGetVideoMode(monitor) : null;
                int hz = mode != null ? mode.refreshRate() : 60;
                refreshPeriodNanos = 1_000_000_000L / Math.max(30, hz);
            } catch (Throwable t) {
                refreshPeriodNanos = 16_666_667L;
            }
        }
        return refreshPeriodNanos;
    }

    private boolean irisShaderPackActive() {
        if (!irisLookupDone) {
            irisLookupDone = true;
            try {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisApi = api.getMethod("getInstance").invoke(null);
                irisShaderInUse = api.getMethod("isShaderPackInUse");
            } catch (Throwable t) {
                irisApi = null;
                irisShaderInUse = null;
            }
        }
        if (irisShaderInUse == null) {
            return false;
        }
        if (irisRecheck-- <= 0) {
            irisRecheck = 120;
            try {
                irisActive = (Boolean) irisShaderInUse.invoke(irisApi);
            } catch (Throwable t) {
                irisActive = false;
            }
        }
        return irisActive;
    }

    private void ensureSynthesizer(InterframeConfig cfg) {
        InterframeConfig.Mode mode = cfg.mode();
        if (synth != null && mode == synthMode) {
            if (!synth.isAvailable()) {
                synth.init();
            }
            return;
        }
        if (synth != null) {
            synth.dispose();
        }
        synth = SynthesizerFactory.create(mode);
        synthMode = mode;
        synth.init();
    }

    private void ensureTextures(int w, int h) {
        if (w == builtW && h == builtH && colorTex[0] != 0) {
            return;
        }
        for (int i = 0; i < 2; i++) {
            if (colorTex[i] != 0) GL11C.glDeleteTextures(colorTex[i]);
            if (captureFbo[i] != 0) GL30C.glDeleteFramebuffers(captureFbo[i]);
            colorTex[i] = newColorTex(w, h);
            captureFbo[i] = GL30C.glGenFramebuffers();
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, captureFbo[i]);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                    GL11C.GL_TEXTURE_2D, colorTex[i], 0);

            if (worldColorTex[i] != 0) GL11C.glDeleteTextures(worldColorTex[i]);
            if (worldCaptureFbo[i] != 0) GL30C.glDeleteFramebuffers(worldCaptureFbo[i]);
            worldColorTex[i] = newColorTex(w, h);
            worldCaptureFbo[i] = GL30C.glGenFramebuffers();
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, worldCaptureFbo[i]);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                    GL11C.GL_TEXTURE_2D, worldColorTex[i], 0);
            worldColorValid[i] = false;
        }
        builtW = w;
        builtH = h;
        prevValid = false;
        validStreak = 0;
        velInit = false;
        Interframe.LOGGER.info("[Interframe] Capture buffers sized to {}x{}.", w, h);
    }

    /**
     * (Re)create the single pending world-colour texture (the pre-hand/GUI snapshot) and its wrapper FBO,
     * used only as a blit source to hand the capture off into the ping-pong pair at present time.
     * @return true if it was (re)created.
     */
    private boolean ensureWorldColorTex(int w, int h) {
        if (w == worldColorW && h == worldColorH && pendingWorldColorTex != 0) {
            return false;
        }
        if (pendingWorldColorTex != 0) GL11C.glDeleteTextures(pendingWorldColorTex);
        if (pendingWorldFbo != 0) GL30C.glDeleteFramebuffers(pendingWorldFbo);
        pendingWorldColorTex = newColorTex(w, h);
        pendingWorldFbo = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, pendingWorldFbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11C.GL_TEXTURE_2D, pendingWorldColorTex, 0);
        worldColorW = w;
        worldColorH = h;
        worldColorCopied = false;
        return true;
    }

    /** (Re)create the depth copy texture if the size changed. @return true if it was (re)created. */
    private boolean ensureDepthTex(int w, int h) {
        if (w == depthW && h == depthH && depthTex != 0) {
            return false;
        }
        if (depthTex != 0) {
            GL11C.glDeleteTextures(depthTex);
        }
        depthTex = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, depthTex);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL14C.GL_DEPTH_COMPONENT24, w, h, 0,
                GL11C.GL_DEPTH_COMPONENT, GL11C.GL_UNSIGNED_INT, (java.nio.ByteBuffer) null);
        depthW = w;
        depthH = h;
        depthCopied = false;
        depthProbesLeft = 3; // glGetError-check the first few copies, then trust the path
        return true;
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

    /** GPU framebuffer blit (color only) from one FBO to another (0 = back buffer). */
    private static void blitColor(int readFbo, int drawFbo, int w, int h) {
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFbo);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFbo);
        GL30C.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST);
    }

    private static boolean inGame() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.player != null && !mc.isPaused();
    }
}
