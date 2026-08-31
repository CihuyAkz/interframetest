package dev.fallingcloud.interframe;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * User-facing settings for Interframe, persisted to {@code config/interframe.json}.
 *
 * <p>Fields are plain so GSON (de)serialises them directly and the standalone Forge config screen (see
 * {@link dev.fallingcloud.interframe.gui.InterframeOptionsScreen}) can read/write them with simple
 * getters/setters.
 */
public class InterframeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static InterframeConfig instance;

    /** Which synthesiser produces the in-between frames. */
    public enum Mode {
        /** Cross-blend prev/next at the in-between time. Always correct; soft ghosting under fast motion. */
        BLEND,
        /**
         * Full pose reprojection between two real frames (the default). Rotation is warped exactly (any
         * yaw/pitch combination, quaternion math), and translation — walking, strafing, flying — is
         * parallax-corrected using the scene depth buffer, so movement stays sharp instead of ghosting.
         * Smoothest image; costs ~1 frame of latency plus the pacing hold.
         */
        REPROJECT,
        /**
         * Look-ahead reprojection ("timewarp"). Warps the latest frame FORWARD along the camera's
         * angular/linear velocity, so the image leads toward where you're moving instead of lagging —
         * lowest latency, at the cost of slight overshoot when you stop or reverse.
         */
        TIMEWARP,
        /** Neural RIFE via ONNX Runtime. Needs a model file and the runtime (see README). */
        NEURAL
    }

    /** Master switch. When false, presentation is exactly vanilla (no captures, no extra swaps). */
    public boolean enabled = true;

    /** Synthesiser selection. Stored as the enum name. */
    public Mode mode = Mode.REPROJECT;

    /**
     * Generated frames inserted per real frame. 1 ≈ "2x" (one synthetic frame between each pair of real
     * frames), 2 ≈ "3x", 3 ≈ "4x". Clamped to [1, 3]. Higher multiplies smoothness but each synthetic
     * frame still costs GPU time, so the real frame rate drops as this rises.
     */
    public int generatedPerReal = 1;

    /**
     * Strength of the rotational reprojection warp, 0..100 (read as 0.00 .. 1.00). 100 = fully reproject
     * the look-rotation between frames; lower values ease the warp toward a plain blend. Ignored in BLEND
     * mode. Clamped to [0, 100].
     */
    public int reprojectStrength = 100;

    /**
     * TIMEWARP only: how far ahead, in frame-intervals, the displayed (real) frame is warped along the
     * camera's angular velocity, 0..150 (read as 0.00 .. 1.50 frames). This is the latency compensation —
     * higher feels more responsive while turning but over-predicts (slight overshoot when you stop or
     * reverse). Clamped to [0, 150].
     */
    public int lookAhead = 60;

    /**
     * Above this per-frame look-rotation (degrees) the warp is treated as a cut/teleport and skipped for
     * that frame (we just blend), so respawns and large camera jumps don't smear. Clamped to [2, 90].
     */
    public int maxWarpDegrees = 25;

    /**
     * Compensate camera <em>translation</em> (walking, strafing, flying) using the scene depth buffer,
     * not just rotation. This is what keeps the world sharp while you move around instead of ghosting.
     * Costs one depth-buffer copy per frame; automatically off under Iris shaderpacks (their depth
     * isn't the vanilla buffer's) and wherever depth is unavailable.
     */
    public boolean translationWarp = true;

    /**
     * How fully the synthetic + real presents are spread across the frame interval, 0..100. Even spacing
     * is what your eyes read as smoothness — presented back-to-back, synthetic frames are pure overhead
     * the monitor never scans out. 100 = perfectly even (adds up to g/(g+1) of a frame of latency at
     * "g+1"x); 0 = present immediately (lowest latency, defeats the purpose with V-Sync off). Ignored
     * with V-Sync on, where the vblank does the pacing. Clamped to [0, 100].
     */
    public int pacingStrength = 90;

    /** Only generate while actually in a world (not in menus / loading screens). */
    public boolean inGameOnly = true;

    /**
     * Keep the held item, hotbar, crosshair and the rest of the HUD out of the warp/blend entirely, so
     * synthetic frames never smear or ghost them — only the 3D world behind them is interpolated. Costs
     * one extra frame capture (the pre-HUD world image) per real frame. See {@link FrameGenerator}.
     */
    public boolean preserveHud = true;

    public Mode mode()              { return mode == null ? Mode.REPROJECT : mode; }
    public int generatedPerReal()   { return clamp(generatedPerReal, 1, 3); }
    public float reprojectStrength(){ return clamp(reprojectStrength, 0, 100) / 100.0f; }
    public float lookAhead()        { return clamp(lookAhead, 0, 150) / 100.0f; }
    public int maxWarpDegrees()     { return clamp(maxWarpDegrees, 2, 90); }
    public float pacingStrength()   { return clamp(pacingStrength, 0, 100) / 100.0f; }
    public int pacingStrengthPercent() { return clamp(pacingStrength, 0, 100); }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // --- persistence (identical pattern to FoveateConfig) -------------------------------------------

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("interframe.json");
    }

    public static InterframeConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static InterframeConfig load() {
        Path path = file();
        try {
            if (Files.exists(path)) {
                InterframeConfig cfg = GSON.fromJson(Files.readString(path), InterframeConfig.class);
                if (cfg != null) {
                    return cfg;
                }
            }
        } catch (Exception e) {
            Interframe.LOGGER.warn("[Interframe] Failed to read config, using defaults", e);
        }
        InterframeConfig cfg = new InterframeConfig();
        cfg.save();
        return cfg;
    }

    public void save() {
        try {
            Files.writeString(file(), GSON.toJson(this));
        } catch (IOException e) {
            Interframe.LOGGER.warn("[Interframe] Failed to write config", e);
        }
    }
}
