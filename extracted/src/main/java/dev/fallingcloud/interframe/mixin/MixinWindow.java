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
 * -> {@code GLFW.glfwSwapBuffers}.
 */
@Mixin(Window.class)
public class MixinWindow {

    @Inject(method = "updateDisplay", at = @At("HEAD"))
    private void interframe$present(CallbackInfo ci) {
        FrameGenerator.INSTANCE.onPresent();
    }
}
