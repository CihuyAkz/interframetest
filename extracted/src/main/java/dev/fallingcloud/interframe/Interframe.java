package dev.fallingcloud.interframe;

import dev.fallingcloud.interframe.compat.OptiFineBridge;
import dev.fallingcloud.interframe.gui.InterframeOptionsScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Interframe — AI frame generation for Minecraft. Forge port (from the Fabric/NeoForge 1.21.1 builds,
 * retargeted to Forge 1.20.1).
 *
 * <p>Captures each finished frame and synthesises one or more <em>in-between</em> frames, presenting
 * them between the real ones to raise the perceived frame rate and smooth motion. The synthesiser is
 * layered and degrades gracefully (see {@link dev.fallingcloud.interframe.synth.FrameSynthesizer}):
 * an always-correct cross-blend, a rotational motion-reprojection ("timewarp") that sharpens the common
 * mouse-look case, and an optional neural RIFE backend (ONNX Runtime) for true learned interpolation.
 * The held item, hotbar, crosshair and rest of the HUD are captured separately from the 3D scene and are
 * never warped/blended — see {@link FrameGenerator} and {@code preserveHud}.
 *
 * <p><b>What changed from the Fabric build:</b> the {@code ClientModInitializer} entrypoint is now a
 * Forge {@code @Mod} constructor; {@code FabricLoader.getConfigDir()} calls became
 * {@code FMLPaths.CONFIGDIR}; and the Sodium-specific settings-menu integration
 * ({@code sodium:config_api_user}) was replaced with a standalone Forge options screen (see
 * {@link InterframeOptionsScreen}) registered through Forge's {@code ConfigScreenHandler} extension
 * point, reachable from the mod's entry in the Mods list. Everything else — the mixins, the GL work in
 * {@link FrameGenerator}, and the synthesiser stack — is unchanged, since none of it touched Fabric APIs
 * to begin with. See the README for the OptiFine compatibility notes.
 */
@Mod(Interframe.MOD_ID)
public final class Interframe {
    public static final String MOD_ID = "interframe";
    public static final String VERSION = "1.2.0-forge";
    public static final Logger LOGGER = LoggerFactory.getLogger("Interframe");

    public Interframe() {
        // This mod is client-only (rendering/presentation hooks); mods.toml marks every dependency
        // side = "CLIENT" and there is no server/common logic here, so we don't gate on Dist ourselves
        // beyond this belt-and-suspenders check.
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }

        InterframeConfig.get(); // load (or create defaults) eagerly so the file exists on first launch

        if (OptiFineBridge.isInstalled()) {
            LOGGER.info("[Interframe] OptiFine detected — using require=0 fail-soft mixin injection "
                    + "and OptiFine shaderpack detection for the depth-based reprojection gate. "
                    + "See README > OptiFine support for details/known limitations.");
        }

        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onClientSetup);

        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) ->
                        new InterframeOptionsScreen(parent)));
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        LOGGER.info("[Interframe] Initialised (Forge, MC 1.20.1).");
        // Registers nothing on the Forge event bus at the moment — frame generation is driven entirely
        // by the mixins, not by Forge render events (a Forge RenderLevelStageEvent-based rewrite is
        // possible but was not necessary: the mixin injection points needed are stable, low-level Mojang
        // methods identical in shape across both loaders).
        MinecraftForge.EVENT_BUS.register(this);
    }
}
