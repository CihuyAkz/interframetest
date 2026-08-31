package dev.fallingcloud.interframe.compat;

import java.lang.reflect.Method;

/**
 * Reflective bridge to OptiFine, added for the Forge port (OptiFine isn't a thing on Fabric).
 *
 * <p>OptiFine is closed-source and not published to a public Maven repository, so — same policy as
 * {@code FoveaBridge}/Iris detection in {@link dev.fallingcloud.interframe.FrameGenerator} — we never
 * compile against it directly. Everything here is {@code Class.forName} + reflection, wrapped so a
 * missing class, a renamed method between OptiFine builds, or an unexpected return type degrades to
 * "OptiFine feature not detected" instead of throwing.
 *
 * <h2>Why this exists</h2>
 * OptiFine ships its own shader pipeline (like Iris) and, on some builds, its own dynamic-FOV / GUI
 * scaling tweaks. Both can invalidate the same assumptions Interframe already guards against for Iris:
 * <ul>
 *   <li>Under an active OptiFine shaderpack the vanilla depth buffer this mod copies is not the
 *       shaderpack's depth, so translational (parallax) reprojection must fall back to rotation-only —
 *       see the call site in {@code FrameGenerator.onWorldDepth()}.</li>
 * </ul>
 *
 * <h2>Known compatibility caveat (read before shipping)</h2>
 * OptiFine patches Minecraft's own class files (it is not a normal mod), and historically conflicts with
 * heavy client-rendering mods — especially anything that also rewrites {@code LevelRenderer} internals
 * the way Sodium/Embeddium do. Interframe's two mixins ({@code MixinLevelRenderer},
 * {@code MixinWindow}) are deliberately shallow — they inject at the very start/end of
 * {@code renderLevel} and the start of {@code Window.updateDisplay()}, not inside chunk building or the
 * shader pipeline — which is the kind of injection point OptiFine setups most often tolerate. It is
 * still not guaranteed for every OptiFine build. Both {@code @Inject}s in those mixins are declared with
 * {@code require = 0}: if OptiFine's bytecode patching ever shifts those methods enough that Mixin can't
 * find the injection point, Forge logs a warning instead of a hard crash, and Interframe simply never
 * engages (no frame generation) rather than taking the game down. If you hit that, please file the
 * OptiFine build/version in an issue so the mixin target can be adjusted.
 */
public final class OptiFineBridge {

    private static boolean lookupDone;
    private static boolean installed;
    private static Method shaderPackLoaded;
    private static Object shadersClassHolder; // unused, kept null: the method we call is static

    private static int recheck;
    private static boolean activeCached;

    private OptiFineBridge() {
    }

    /** Whether OptiFine appears to be installed at all (independent of whether a shaderpack is active). */
    public static boolean isInstalled() {
        ensureLookup();
        return installed;
    }

    /**
     * Mirrors {@code FrameGenerator}'s Iris check: true if an OptiFine shaderpack is currently loaded.
     * Re-checked periodically (not every call) since shaderpacks can be toggled from the video settings
     * menu mid-session, same cadence as the Iris check this complements.
     */
    public static boolean shaderPackActive() {
        ensureLookup();
        if (shaderPackLoaded == null) {
            return false;
        }
        if (recheck-- <= 0) {
            recheck = 120;
            try {
                activeCached = (Boolean) shaderPackLoaded.invoke(null);
            } catch (Throwable t) {
                activeCached = false;
            }
        }
        return activeCached;
    }

    private static void ensureLookup() {
        if (lookupDone) {
            return;
        }
        lookupDone = true;
        try {
            // Presence check: this class exists on every OptiFine build we've seen, vanilla/Forge-only
            // installs never have it.
            Class.forName("net.optifine.Config");
            installed = true;
        } catch (Throwable t) {
            installed = false;
            return; // no OptiFine at all — nothing further to look up
        }
        // Shader-pack-active accessor. Method name has been stable as `Shaders.isShaderPackLoaded()`
        // across recent OptiFine releases, but we try a couple of historical fallbacks defensively
        // rather than assume — any failure here just leaves shaderPackLoaded == null (feature off).
        String[] candidateMethods = {"isShaderPackLoaded", "shaderPackLoaded"};
        for (String methodName : candidateMethods) {
            try {
                Class<?> shaders = Class.forName("net.optifine.shaders.Shaders");
                Method m = shaders.getMethod(methodName);
                if (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class) {
                    shaderPackLoaded = m;
                    break;
                }
            } catch (Throwable t) {
                // try next candidate
            }
        }
    }
}
