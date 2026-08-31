package dev.fallingcloud.interframe.mixin;

import dev.fallingcloud.interframe.FrameGenerator;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives frame generation. {@code Window.updateDisplay()} is called once per frame from
 * {@code Minecraft.runTick()} and is the last thing before Minecraft swaps buffers (its body calls
 * {@code RenderSystem.flipFrame}). Injecting at HEAD lets {@link FrameGenerator} capture the just-finished
 * frame and present its synthetic in-between frames (each with its own swap) right before the real frame
 * is shown.
 *
 * <p>Verified against MC 1.21.1: {@code Window.updateDisplay()} -> {@code RenderSystem.flipFrame(window)}
 * -> {@code GLFW.glfwSwapBuffers}. Ported to 1.21.11 without a live compile (see PORTING_NOTES.md) —
 * {@code updateDisplay} is assumed unchanged (no evidence found that it was renamed/removed through
 * 1.21.11), but {@code RenderSystem.flipFrame} itself gained a second, nullable
 * {@code TracyFrameCapturer} parameter somewhere around 1.21.2 (see {@code FrameGenerator}'s call sites,
 * updated to pass {@code null}). Because this {@code @Inject} targets {@code updateDisplay} by name only
 * (no descriptor), it will still bind even if Mixin sees a different erased signature than in 1.21.1 —
 * the risk here is the method being renamed outright, not a parameter/return-type change.
 */
@Mixin(Window.class)
public class MixinWindow {

    @Inject(method = "updateDisplay", at = @At("HEAD"))
    private void interframe$present(CallbackInfo ci) {
        FrameGenerator.INSTANCE.onPresent();
    }
}
