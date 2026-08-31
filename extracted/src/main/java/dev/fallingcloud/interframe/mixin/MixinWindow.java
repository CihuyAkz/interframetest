package dev.fallingcloud.interframe.mixin;

import dev.fallingcloud.interframe.FrameGenerator;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge port, unchanged in behaviour from the Fabric build. Drives frame generation.
 * {@code Window.updateDisplay()} is called once per frame from {@code Minecraft.runTick()} and is the
 * last thing before Minecraft swaps buffers (its body calls {@code RenderSystem.flipFrame}). Injecting
 * at HEAD lets {@link FrameGenerator} capture the just-finished frame and present its synthetic
 * in-between frames (each with its own swap) right before the real frame is shown.
 *
 * <p>{@code com.mojang.blaze3d.platform.Window} is Mojang's own class (not touched by Forge's patches),
 * so its shape here matches upstream 1.20.1 — verify against your local MC 1.20.1 source if you bump the
 * Minecraft version further. See {@link MixinLevelRenderer} for the OptiFine {@code require = 0} note,
 * which applies here too.
 */
@Mixin(Window.class)
public class MixinWindow {

    @Inject(method = "updateDisplay", at = @At("HEAD"), require = 0)
    private void interframe$present(CallbackInfo ci) {
        FrameGenerator.INSTANCE.onPresent();
    }
}
