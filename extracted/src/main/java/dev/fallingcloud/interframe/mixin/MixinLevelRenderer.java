package dev.fallingcloud.interframe.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
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
 * combination), and the half-FOV tangents + depth-linearisation terms come straight from the live
 * projection matrix — any FOV effects (sprint, zoom, shaders) are accounted for automatically. The
 * snapshot is paired with the captured colour image at present time in {@link FrameGenerator}.
 *
 * <p>At the <em>end</em> of the level render we also let the generator copy the scene depth buffer and a
 * world-only colour snapshot (no hand/GUI yet): vanilla clears the depth buffer and draws the hand + GUI
 * right after this, so this is the last moment the true world depth exists and the composited image is
 * still world-only — the depth is what the translational (parallax) reprojection needs, and the
 * world-only colour is what lets the synthesiser warp the world without ever touching the hand or HUD
 * (see {@link FrameGenerator#onWorldColor()}).
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
        Matrix4f proj = RenderSystem.getProjectionMatrix();
        float m00 = proj.m00();
        float m11 = proj.m11();
        if (m00 == 0f || m11 == 0f) {
            FrameGenerator.INSTANCE.onWorldCamera(CameraSnapshot.INVALID);
            return;
        }
        Vec3 p = cam.getPosition();
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
