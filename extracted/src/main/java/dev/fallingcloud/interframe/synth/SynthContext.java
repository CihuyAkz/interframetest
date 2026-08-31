package dev.fallingcloud.interframe.synth;

import org.joml.Matrix3f;
import org.joml.Vector3f;

/**
 * Everything a synthesiser needs to produce one in-between frame. Reused each frame (mutable, no
 * per-frame allocation); {@link dev.fallingcloud.interframe.FrameGenerator} fills it in and binds the
 * target framebuffer + viewport before calling {@link FrameSynthesizer#synthesize}.
 *
 * <p>All the camera math is done on the CPU with exact quaternions: for each synthetic frame the
 * generator computes the relative pose between the synthesised viewpoint and each captured frame, and
 * hands the shader plain rotation matrices + view-space translation vectors. The shader never touches
 * Euler angles, so the warp is correct at any combination of yaw/pitch (the old per-axis approximation
 * visibly skewed while turning with the camera pitched up/down).
 */
public final class SynthContext {
    /**
     * GL colour texture of the previous real frame's WORLD ONLY (captured at the end of the level
     * render, before the hand/GUI are drawn) — the in-between's start, phase 0. This is what gets
     * warped; it never contains the hand or HUD.
     */
    public int prevColorTex;
    /** Same as {@link #prevColorTex} but for the current real frame (the in-between's end, phase 1). */
    public int currColorTex;
    /** GL depth texture of the current frame's level render, or 0. Enables the parallax warp. */
    public int depthTex;
    /** True when {@link #depthTex} holds this frame's scene depth (translational warp usable). */
    public boolean hasDepth;

    /**
     * GL colour texture of the current real frame's FULL composited image (world + hand + HUD),
     * captured at present time. Used only as an unwarped reference so the hand/hotbar/crosshair can be
     * copied straight through onto synthetic frames instead of being warped or blended — see
     * {@link #hasGui}.
     */
    public int guiTex;
    /**
     * True when {@link #guiTex} should be sampled and any pixel that differs from the current frame's
     * world-only capture at the same (unwarped) screen position is passed straight through from
     * {@link #guiTex} instead of the warped/blended world colour. This is what keeps the held item and
     * HUD crisp and un-ghosted on every synthetic frame. Off when the user disables "Preserve Hand &
     * HUD" or {@link #guiTex} is unavailable.
     */
    public boolean hasGui;

    public int width;
    public int height;

    /** Temporal position of this synthetic frame: 0 = prev, 1 = curr (interpolation modes only). */
    public float phase;

    /** TIMEWARP: sample only the current frame, warped by warpCurr/transCurr (extrapolation). */
    public boolean forward;

    /** Rotation part of "synthesised-view ray/position → prev capture's view space". */
    public final Matrix3f warpPrev = new Matrix3f();
    /** Rotation part of "synthesised-view ray/position → curr capture's view space". */
    public final Matrix3f warpCurr = new Matrix3f();
    /** Translation parts of those transforms (view space of each capture), pre-scaled by strength. */
    public final Vector3f transPrev = new Vector3f();
    public final Vector3f transCurr = new Vector3f();

    /** Half-FOV tangents of the synthesised view and of each capture (x includes aspect). */
    public float tanMidX, tanMidY;
    public float tanPrevX, tanPrevY;
    public float tanCurrX, tanCurrY;

    /** Current frame's projection terms for depth linearisation: viewZ = -m32 / (m22 + ndcZ). */
    public float depthM22 = -1f;
    public float depthM32 = -0.1f;
}
