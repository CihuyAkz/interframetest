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
 * Forge port, unchanged in behaviour from the Fabric build — see the original class doc for the full
 * rationale. Captures the world camera for the frame being drawn, so the reprojection warp knows how the
 * view moved between the two real frames it interpolates, and — at the end of the level render — lets
 * {@link FrameGenerator} copy the scene depth buffer and a world-only colour snapshot before vanilla
 * clears the depth buffer and draws the hand + GUI.
 *
 * <p><b>OptiFine note:</b> both injections below use {@code require = 0} instead of the library default
 * of 1. OptiFine patches Minecraft's class files directly and, on some builds, can shift method bodies
 * enough that Mixin's injection point search fails. With {@code require = 0} that failure is logged as a
 * warning and Interframe simply doesn't engage (no crash) instead of taking the whole game down — see
 * {@link dev.fallingcloud.interframe.compat.OptiFineBridge} for the fuller compatibility note.
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {

    @Inject(method = "renderLevel", at = @At("HEAD"), require = 0)
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

    @Inject(method = "renderLevel", at = @At("RETURN"), require = 0)
    private void interframe$captureDepth(CallbackInfo ci) {
        FrameGenerator.INSTANCE.onWorldDepth();
        FrameGenerator.INSTANCE.onWorldColor();
    }
}
