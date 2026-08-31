package dev.fallingcloud.interframe.synth;

import dev.fallingcloud.interframe.Interframe;
import dev.fallingcloud.interframe.gl.ShaderProgram;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;

/**
 * Built-in GPU synthesiser: a single fullscreen pass that reprojects the previous and current frame to
 * the synthesised viewpoint and blends them at the in-between time.
 *
 * <p>The reprojection is a full 6-DOF "timewarp": the CPU hands us, per synthetic frame, the exact
 * relative pose (rotation matrix + view-space translation) between the synthesised viewpoint and each
 * captured frame — computed from the cameras' orientation quaternions, so it is correct at any yaw/pitch
 * combination, unlike a per-axis Euler warp which visibly skews while turning with the camera pitched.
 *
 * <ul>
 *   <li><b>Rotation</b> needs no depth: each output pixel's view ray is rotated into each capture and
 *       re-projected. This kills mouse-look judder, the dominant first-person artefact.</li>
 *   <li><b>Translation</b> (walking, strafing, flying, elytra) uses the scene depth captured at the end
 *       of the level render: the surface distance along the output ray is read from the current frame's
 *       depth buffer, the surface point is transformed by the relative pose, and re-projected — true
 *       parallax instead of a double-image cross-blend. Where depth is unavailable the distance falls
 *       back to "far", which smoothly degrades to rotation-only; the parallax term also eases out for
 *       geometry closer than ~0.85 blocks, where a single depth tap under-samples the huge per-frame
 *       parallax and a soft blend looks better than a stretched warp.</li>
 * </ul>
 *
 * <p>Rays that leave a capture (screen edges revealed by the warp) fall back to whichever frame still
 * covers them, then to a plain blend. Needs no model and runs on any GL 3.2 core context, so it is
 * always the available fallback.
 */
public final class BlendReprojectSynthesizer implements FrameSynthesizer {

    private static final String VERTEX = """
            #version 150 core
            out vec2 vUv;
            void main() {
                // Single fullscreen triangle generated from gl_VertexID — no vertex buffers needed.
                vec2 p = vec2((gl_VertexID == 1) ? 3.0 : -1.0, (gl_VertexID == 2) ? 3.0 : -1.0);
                vUv = p * 0.5 + 0.5;
                gl_Position = vec4(p, 0.0, 1.0);
            }
            """;

    private static final String FRAGMENT = """
            #version 150 core
            in vec2 vUv;
            out vec4 fragColor;

            uniform sampler2D prevTex;
            uniform sampler2D currTex;
            uniform sampler2D depthTex;
            uniform sampler2D guiTex; // curr frame's FULL image (world+hand+HUD), unwarped reference

            uniform int   forward;   // 1 = timewarp: extrapolate the current frame only
            uniform int   hasDepth;  // 1 = depthTex holds this frame's scene depth (enables parallax)
            uniform int   hasGui;    // 1 = guiTex is valid: pass hand/HUD pixels through unwarped
            uniform float phase;     // interpolation blend: 0 = prev, 1 = curr
            uniform mat3  warpPrev;  // output-view ray/position -> prev capture's view space
            uniform mat3  warpCurr;  // output-view ray/position -> curr capture's view space
            uniform vec3  transPrev; // translation part of those transforms (already strength-scaled)
            uniform vec3  transCurr;
            uniform vec2  tanMid;    // tan(half-fov) of the synthesised view (x includes aspect)
            uniform vec2  tanPrev;   // of the prev capture (differs under sprint-FOV changes)
            uniform vec2  tanCurr;   // of the curr capture
            uniform vec2  depthLin;  // (m22, m32) of the projection: viewZ = -m32 / (m22 + ndcZ)

            const float FAR_DIST  = 4096.0; // parallax-free distance used where depth is absent (sky)
            const float NEAR_LOCK = 0.45;   // no parallax below this distance (see below)
            const float NEAR_FREE = 0.85;   // full parallax beyond it

            // Perspective-project a view-space position (z < 0 in front) to that capture's uv.
            vec2 projectTo(vec3 p, vec2 tanHalf, out bool ok) {
                ok = p.z < -1e-4;
                if (!ok) return vec2(0.5);
                return (p.xy / (-p.z * tanHalf)) * 0.5 + 0.5;
            }

            bool inBounds(vec2 uv) {
                return all(greaterThanEqual(uv, vec2(0.0))) && all(lessThanEqual(uv, vec2(1.0)));
            }

            // Scene distance along a normalized (z < 0) view ray of the CURRENT capture.
            float sceneDist(vec2 uv, float rayZ) {
                float d = texture(depthTex, clamp(uv, 0.0, 1.0)).r;
                if (d >= 0.9999) return FAR_DIST; // sky / cleared depth
                float viewZ = -depthLin.y / (depthLin.x + d * 2.0 - 1.0); // negative in front
                return clamp(viewZ / rayZ, 0.05, FAR_DIST);
            }

            void main() {
                vec2 ndc = vUv * 2.0 - 1.0;
                vec3 dirMid = normalize(vec3(ndc * tanMid, -1.0));

                // Distance to the surface this output ray sees: tap the current frame's depth along the
                // rotation-warped ray. Without depth everything sits at "far": rotation still applies,
                // parallax smoothly vanishes.
                float dist = FAR_DIST;
                if (hasDepth == 1) {
                    vec3 rc = normalize(warpCurr * dirMid);
                    bool okTap;
                    vec2 uvTap = projectTo(rc, tanCurr, okTap);
                    if (okTap) dist = sceneDist(uvTap, rc.z);
                }

                // Rotation is depth-independent and exact at any distance, so it always applies in full.
                // Parallax (the translation term) eases out very close to the camera: a single depth tap
                // can't handle the huge per-frame parallax of in-your-face geometry, and a soft blend
                // looks better there than a stretched warp.
                float parallaxW = smoothstep(NEAR_LOCK, NEAR_FREE, dist);
                vec3 pMid = dirMid * dist;

                bool okC;
                vec2 uvCurr = projectTo(warpCurr * pMid + transCurr * parallaxW, tanCurr, okC);
                if (!okC) uvCurr = vUv;
                bool inC = okC && inBounds(uvCurr);

                vec3 outc;
                if (forward == 1) {
                    // Timewarp: predicted view of the newest frame; clamp fills the trailing edge.
                    outc = texture(currTex, clamp(uvCurr, 0.0, 1.0)).rgb;
                } else {
                    bool okP;
                    vec2 uvPrev = projectTo(warpPrev * pMid + transPrev * parallaxW, tanPrev, okP);
                    if (!okP) uvPrev = vUv;
                    bool inP = okP && inBounds(uvPrev);

                    vec3 cp = texture(prevTex, clamp(uvPrev, 0.0, 1.0)).rgb;
                    vec3 cc = texture(currTex, clamp(uvCurr, 0.0, 1.0)).rgb;

                    if (inP && inC)      outc = mix(cp, cc, phase);
                    else if (inP)        outc = cp;
                    else if (inC)        outc = cc;
                    else                 outc = mix(texture(prevTex, vUv).rgb, texture(currTex, vUv).rgb, phase);
                }

                // Hand/HUD passthrough: guiTex and currTex are the SAME real frame sampled at the SAME
                // (unwarped) screen position vUv — one before the hand/HUD were drawn, one after. Any
                // difference between them at that position can only be hand/HUD pixels (screen-locked,
                // so vUv is always their correct, un-warped location). Copying guiTex straight through
                // there means the held item, hotbar and crosshair never warp, blend or ghost across
                // synthetic frames — only the world behind them moves.
                if (hasGui == 1) {
                    vec3 guiColor = texture(guiTex, vUv).rgb;
                    vec3 worldRef = texture(currTex, vUv).rgb;
                    vec3 d = abs(guiColor - worldRef);
                    // guiTex and currTex are exact GPU copies of the SAME real frame at the SAME
                    // screen position, differing only where the hand/HUD was drawn in between the two
                    // captures — so any non-hand pixel is bit-identical here (0.02 ≈ 5/255 was too
                    // loose and let low-contrast held items, e.g. wood on wood, skin tone at night,
                    // slip under the threshold and get warped like the world). ~1/255 leaves headroom
                    // only for filtering noise, not for missing real hand/HUD pixels.
                    if (max(d.r, max(d.g, d.b)) > 0.004) {
                        outc = guiColor;
                    }
                }

                fragColor = vec4(outc, 1.0);
            }
            """;

    private final ShaderProgram program = new ShaderProgram();
    private boolean ready;

    @Override
    public String name() {
        return "Blend + Reprojection (built-in)";
    }

    @Override
    public boolean init() {
        if (ready) {
            return true;
        }
        ready = program.compile(VERTEX, FRAGMENT);
        if (ready) {
            Interframe.LOGGER.info("[Interframe] Built-in reprojection synthesiser compiled.");
        } else {
            Interframe.LOGGER.error("[Interframe] Failed to compile the built-in synthesiser; frame generation disabled.");
        }
        return ready;
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
        program.bind();

        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, ctx.prevColorTex);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, ctx.currColorTex);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE2);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, ctx.hasDepth ? ctx.depthTex : 0);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE3);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, ctx.hasGui ? ctx.guiTex : 0);

        program.setInt("prevTex", 0);
        program.setInt("currTex", 1);
        program.setInt("depthTex", 2);
        program.setInt("guiTex", 3);
        program.setInt("forward", ctx.forward ? 1 : 0);
        program.setInt("hasDepth", ctx.hasDepth ? 1 : 0);
        program.setInt("hasGui", ctx.hasGui ? 1 : 0);
        program.setFloat("phase", ctx.phase);
        program.setMat3("warpPrev", ctx.warpPrev);
        program.setMat3("warpCurr", ctx.warpCurr);
        program.setVec3("transPrev", ctx.transPrev);
        program.setVec3("transCurr", ctx.transCurr);
        program.setVec2("tanMid", ctx.tanMidX, ctx.tanMidY);
        program.setVec2("tanPrev", ctx.tanPrevX, ctx.tanPrevY);
        program.setVec2("tanCurr", ctx.tanCurrX, ctx.tanCurrY);
        program.setVec2("depthLin", ctx.depthM22, ctx.depthM32);

        // Opaque, full-screen, no depth: the synthetic frame is a flat image.
        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDisable(GL11C.GL_BLEND);
        GL11C.glDisable(GL11C.GL_CULL_FACE);
        GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        GL11C.glViewport(0, 0, ctx.width, ctx.height);

        program.drawFullscreen();
    }

    @Override
    public void dispose() {
        program.dispose();
        ready = false;
    }
}
