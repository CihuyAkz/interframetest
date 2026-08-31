package dev.fallingcloud.interframe;

import dev.fallingcloud.interframe.client.InterframeConfigScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Interframe — AI frame generation for Minecraft. (Minecraft) Forge port.
 *
 * <p>Captures each finished frame and synthesises one or more <em>in-between</em> frames, presenting
 * them between the real ones to raise the perceived frame rate and smooth motion. The synthesiser is
 * layered and degrades gracefully (see {@link dev.fallingcloud.interframe.synth.FrameSynthesizer}):
 * an always-correct cross-blend, a rotational motion-reprojection ("timewarp") that sharpens the common
 * mouse-look case, and an optional neural RIFE backend (ONNX Runtime) for true learned interpolation.
 * The held item, hotbar, crosshair and rest of the HUD are captured separately from the 3D scene and
 * are never warped/blended — see {@link FrameGenerator} and {@code preserveHud}.
 *
 * <p>Built for MC 1.21.1 on (Lex's) Forge — as opposed to the NeoForge or Fabric builds, which register
 * their settings page through Sodium's config API. Sodium does not officially ship a build for this
 * loader, so here Interframe ships its own settings screen instead, reachable from the mod list's
 * "Config" button (see {@link InterframeConfigScreen}). Configuration lives in {@link InterframeConfig};
 * the GL work is in {@link FrameGenerator}.
 */
@Mod(Interframe.MOD_ID)
public final class Interframe {
    public static final String MOD_ID = "interframe";
    public static final String VERSION = "1.2.0-forge";
    public static final Logger LOGGER = LoggerFactory.getLogger("Interframe");

    public Interframe() {
        // Client-only mod: everything it does (frame capture/present, GL work) only makes sense on the
        // physical client, so bail out immediately on a dedicated server rather than declaring a
        // clientSideOnly mod in mods.toml.
        if (!FMLEnvironment.dist.isClient()) {
            return;
        }

        InterframeConfig.get(); // load (or create defaults) eagerly so the file exists on first launch
        LOGGER.info("[Interframe] Initialised (Forge).");

        // Registers the settings screen behind the "Config" button on this mod's entry in the mod list.
        net.minecraftforge.fml.ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (mc, parent) -> new InterframeConfigScreen(parent)));
    }
}
