package dev.fallingcloud.interframe.gl;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

import java.lang.reflect.Method;

/**
 * Bridges Interframe's raw-GL capture code to Mojang's post-1.21.5 {@code RenderTarget}, which no longer
 * exposes a plain {@code int frameBufferId} field. The color attachment is now wrapped in a
 * {@code GpuTexture} (GL backend: {@code com.mojang.blaze3d.opengl.GlTexture}, reached via
 * {@code RenderTarget#getColorTexture()} — likely returning a {@code GpuTextureView} whose
 * {@code .texture()} is the {@code GlTexture}). 1.21.11 additionally ships a
 * {@code GlTextureView#getFbo()} convenience that hands back a cached FBO wrapping the texture directly.
 *
 * <p><b>This class is a best-effort, NOT verified against a real 1.21.11 compile/run</b> (this port was
 * written without network access to the Minecraft/Fabric/Sodium toolchain — see PORTING_NOTES.md). The
 * exact method names below (`getColorTexture`, `texture`, `getFbo`, `glId`) are taken from Mojang's
 * official 1.21.x mapping files and NeoForge's migration primers, which are usually reliable, but none of
 * this has been exercised against the actual jar. Everything is reached reflectively and multiple
 * candidate call chains are tried, specifically so a small drift in an exact method name (rather than the
 * overall shape of the API) doesn't hard-crash the mod — it just falls back to "unavailable" and
 * Interframe disables the features that need it (see call sites in {@code FrameGenerator}).
 *
 * <p>If none of the reflective paths resolve, {@link #mainColorFbo(RenderTarget)} returns {@code -1} and
 * callers should treat that the same as any other capture failure they already handle (driver rejects the
 * copy, etc.) — never throw out of this class.
 */
public final class GpuInterop {
    private GpuInterop() {}

    private static volatile boolean warned = false;

    // Two independent caches (color / depth): we build our own single-attachment FBO wrapping the
    // resolved raw GL texture id whenever getFbo() isn't available on the object graph. Each is rebuilt
    // only when its texture id changes (e.g. on resize) — kept separate so reading one doesn't evict the
    // other's cached FBO every frame.
    private static int cachedColorTextureId = 0;
    private static int cachedColorFbo = 0;
    private static int cachedDepthTextureId = 0;
    private static int cachedDepthFbo = 0;

    /**
     * Returns a GL framebuffer that can be bound as {@code GL_READ_FRAMEBUFFER} to read the main render
     * target's current COLOR contents, or {@code -1} if it could not be resolved. See the resolution
     * order documented on {@link #resolve}.
     */
    public static int mainColorFbo(RenderTarget target) {
        return resolve(target, "getColorTexture", "colorTexture", "frameBufferId", "colorTextureId", true);
    }

    /**
     * Returns a GL framebuffer that can be bound as {@code GL_READ_FRAMEBUFFER} to read the main render
     * target's current DEPTH contents (for {@code glCopyTexSubImage2D} into a {@code GL_DEPTH_COMPONENT}
     * texture — per the GL spec this reads the framebuffer's depth image when the destination texture's
     * format is a depth format, independent of the read-buffer/color-attachment state), or {@code -1} if
     * it could not be resolved.
     */
    public static int mainDepthFbo(RenderTarget target) {
        return resolve(target, "getDepthTexture", "depthTexture", "frameBufferId", "depthBufferId", false);
    }

    /**
     * Shared resolution logic for both color and depth.
     * <ol>
     *   <li>{@code target.<getterName>()} (or {@code <fieldStyleName>} as a fallback accessor) → if the
     *       result has a no-arg {@code getFbo()} (the 1.21.11 {@code GlTextureView} convenience), use
     *       that FBO directly (Mojang-managed, no lifecycle for us to worry about).</li>
     *   <li>Otherwise unwrap view → texture via {@code .texture()} if present, pull a raw GL name off it
     *       via {@code glId()}/{@code getId()}/{@code getTextureId()}, and wrap that in a single-attachment
     *       FBO we own and cache ourselves.</li>
     *   <li>Legacy pre-1.21.5 fallback: a plain {@code int} field with the given legacy name(s).</li>
     * </ol>
     */
    private static int resolve(RenderTarget target, String getterName, String altAccessorName,
            String legacyFboField, String legacyTexField, boolean isColor) {
        try {
            Object texOrView = invokeNoArg(target, getterName);
            if (texOrView == null) {
                texOrView = invokeNoArg(target, altAccessorName);
            }
            if (texOrView != null) {
                Object fbo = invokeNoArg(texOrView, "getFbo");
                if (fbo instanceof Integer) {
                    return (Integer) fbo;
                }
                Object texture = texOrView;
                Object unwrapped = invokeNoArg(texOrView, "texture");
                if (unwrapped != null) {
                    texture = unwrapped;
                }
                Integer rawId = firstIntResult(texture, "glId", "getId", "getTextureId", "id");
                if (rawId != null) {
                    return isColor ? ownedColorFboFor(rawId) : ownedDepthFboFor(rawId);
                }
            }

            // Legacy pre-1.21.5 fallback (kept in case this bridge is ever reused on an older branch): the
            // old RenderTarget had one shared frameBufferId FBO with both color and depth attached, valid
            // as a read source for either.
            Integer legacyFbo = intField(target, legacyFboField);
            if (legacyFbo != null) {
                return legacyFbo;
            }
            Integer legacyTex = intField(target, legacyTexField);
            if (legacyTex != null) {
                return isColor ? ownedColorFboFor(legacyTex) : ownedDepthFboFor(legacyTex);
            }
        } catch (Throwable t) {
            warnOnce(t);
        }
        return -1;
    }

    private static int ownedColorFboFor(int rawTextureId) {
        if (rawTextureId == cachedColorTextureId && cachedColorFbo != 0) {
            return cachedColorFbo;
        }
        if (cachedColorFbo != 0) {
            GL30C.glDeleteFramebuffers(cachedColorFbo);
        }
        int fbo = buildSingleAttachmentFbo(rawTextureId, GL30C.GL_COLOR_ATTACHMENT0);
        cachedColorTextureId = rawTextureId;
        cachedColorFbo = fbo;
        return fbo;
    }

    private static int ownedDepthFboFor(int rawTextureId) {
        if (rawTextureId == cachedDepthTextureId && cachedDepthFbo != 0) {
            return cachedDepthFbo;
        }
        if (cachedDepthFbo != 0) {
            GL30C.glDeleteFramebuffers(cachedDepthFbo);
        }
        int fbo = buildSingleAttachmentFbo(rawTextureId, GL30C.GL_DEPTH_ATTACHMENT);
        cachedDepthTextureId = rawTextureId;
        cachedDepthFbo = fbo;
        return fbo;
    }

    private static int buildSingleAttachmentFbo(int rawTextureId, int attachmentPoint) {
        int fbo = GL30C.glGenFramebuffers();
        int prevDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, fbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, attachmentPoint,
                GL11C.GL_TEXTURE_2D, rawTextureId, 0);
        if (attachmentPoint == GL30C.GL_COLOR_ATTACHMENT0) {
            GL30C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0);
        } else {
            GL30C.glDrawBuffers(GL11C.GL_NONE);
        }
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, prevDraw);
        return fbo;
    }

    private static Object invokeNoArg(Object target, String name) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable ignored) {
            // try superclass/declared (public getMethod() only finds public methods across the hierarchy
            // that are themselves public; GlTexture's members may be package-private in the real game).
        }
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static Integer firstIntResult(Object target, String... candidateNames) {
        for (String name : candidateNames) {
            Object result = invokeNoArg(target, name);
            if (result instanceof Integer) {
                return (Integer) result;
            }
        }
        return null;
    }

    private static Integer intField(Object target, String name) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(target);
                if (v instanceof Integer) {
                    return (Integer) v;
                }
            } catch (Throwable ignored) {
                // fall through to superclass
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static void warnOnce(Throwable t) {
        if (!warned) {
            warned = true;
            dev.fallingcloud.interframe.Interframe.LOGGER.warn(
                    "[Interframe] Could not resolve the main render target's GL framebuffer via any known "
                    + "1.21.x API shape (see GpuInterop). Frame capture will be disabled until this is "
                    + "fixed against the real game jar — see PORTING_NOTES.md.", t);
        }
    }
}
