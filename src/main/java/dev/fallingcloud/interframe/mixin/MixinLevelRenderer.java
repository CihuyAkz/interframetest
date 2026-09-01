package dev.fallingcloud.interframe.mixin;

import dev.fallingcloud.interframe.CameraSnapshot;
import dev.fallingcloud.interframe.FrameGenerator;
import dev.fallingcloud.interframe.compat.FoveaBridge;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the world camera for the frame being drawn, so the reprojection warp knows how the view moved
 * between the two real frames it interpolates. The full orientation quaternion is copied straight from
 * the active {@link Camera} (no Euler-angle reconstruction, so the warp is exact at any yaw/pitch
 * combination), and the half-FOV tangents + depth-linearisation terms come from a rebuilt projection
 * matrix (see the 1.21.11 note below) — most FOV effects (sprint, zoom, shaders) are still reflected,
 * modulo the small per-frame lag noted there. The
 * snapshot is paired with the captured colour image at present time in {@link FrameGenerator}.
 *
 * <p>At the <em>end</em> of the level render we also let the generator copy the scene depth buffer and a
 * world-only colour snapshot (no hand/GUI yet): vanilla clears the depth buffer and draws the hand + GUI
 * right after this, so this is the last moment the true world depth exists and the composited image is
 * still world-only — the depth is what the translational (parallax) reprojection needs, and the
 * world-only colour is what lets the synthesiser warp the world without ever touching the hand or HUD
 * (see {@link FrameGenerator#onWorldColor()}).
 *
 * <p><b>1.21.11 verified against Mojang's official mappings / Fabric's yarn docs for 1.21.11, not guessed:</b>
 * <ul>
 *   <li>{@code Camera#getPosition()} does not exist under official mappings in 1.21.11 — replaced by
 *       {@code Camera#position()} (confirmed: {@code Camera#rotation()} kept its no-"get" name across the
 *       same refactor and compiled without error, so {@code position()} follows the same pattern).</li>
 *   <li>{@code RenderSystem.getProjectionMatrix()} is genuinely gone, not renamed — Mojang moved the live
 *       world projection matrix off the Java heap and into a GPU-side uniform buffer
 *       ({@code GameRenderer#worldProjectionMatrix}, a private {@code RawProjectionMatrix} whose only
 *       public member is a write-only {@code set(Matrix4f)} — there is no public CPU-side read-back of
 *       the exact live matrix). Instead this rebuilds an equivalent CPU-side matrix with the public
 *       {@code GameRenderer#getBasicProjectionMatrix(float fovDegrees)}, which exists for exactly this
 *       kind of use. <b>Trade-off:</b> the FOV passed in comes from the base {@code Options#fov()}
 *       setting rather than the exact per-frame value {@code GameRenderer} computes internally (that
 *       computation, {@code GameRenderer#getFov}, is private), so this snapshot's FOV tangents can lag by
 *       up to a tick during transient effects that adjust FOV live (sprinting, zoom mods, nausea) — a
 *       minor accuracy loss in the reprojection warp during those effects only, not a correctness bug.</li>
 * </ul>
 * {@code LevelRenderer.renderLevel}'s parameter list changed at least once in the same window
 * (extraction/drawing split); both {@code @Inject}s below still target it by bare name so they should
 * still bind, but this specific point was not independently re-verified.
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void interframe$captureCamera(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        Camera cam = mc.gameRenderer != null ? mc.gameRenderer.getMainCamera() : null;
        if (cam == null) {
            FrameGenerator.INSTANCE.onWorldCamera(CameraSnapshot.INVALID);
            return;
        }
        // RenderSystem.getProjectionMatrix() no longer exists (see class javadoc). Rebuild an equivalent
        // matrix on the CPU side via the public GameRenderer#getBasicProjectionMatrix(fovDegrees) instead
        // of trying to read back the GPU-side uniform buffer.
        float fovDegrees = mc.options.fov().get();
        Matrix4f proj = mc.gameRenderer.getBasicProjectionMatrix(fovDegrees);
        float m00 = proj.m00();
        float m11 = proj.m11();
        if (m00 == 0f || m11 == 0f) {
            FrameGenerator.INSTANCE.onWorldCamera(CameraSnapshot.INVALID);
            return;
        }
        Vec3 p = cam.position();
        // Under a center-priority Fovea frame the live projection is horizontally trimmed, but the image
        // this snapshot is paired with is the PRESENTED one, whose 1:1 center behaves like vanilla's FOV —
        // undo the trim so the rotation warp is exact where the eyes are (see FoveaBridge).
        float tanScaleX = FoveaBridge.fovTanScale();
        FrameGenerator.INSTANCE.onWorldCamera(new CameraSnapshot(
                new Quaternionf(cam.rotation()),
                Math.abs(1f / m00) / tanScaleX, Math.abs(1f / m11),
                p.x, p.y, p.z,
                proj.m22(), proj.m32()));
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void interframe$captureDepth(CallbackInfo ci) {
        FrameGenerator.INSTANCE.onWorldDepth();
        FrameGenerator.INSTANCE.onWorldColor();
    }
}
