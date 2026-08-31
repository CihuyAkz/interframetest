package dev.fallingcloud.interframe.gl;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

/**
 * Saves and restores the slice of GL state our fullscreen passes touch, so Minecraft / Sodium / Iris see
 * the context exactly as they left it. Reused each frame (no per-frame allocation). Capture before the
 * passes, restore after.
 *
 * <p>This is the same discipline {@code Foveate.VrsManager} uses around its pixel-store state: leaving
 * stale GL state behind is how render mods silently corrupt each other's draws.
 */
public final class GlGuard {
    private int program;
    private int activeTexture;
    private int texUnit0;
    private int texUnit1;
    private int texUnit2;
    private int texUnit3;
    private int drawFbo;
    private int readFbo;
    private int vao;
    private final int[] viewport = new int[4];
    private boolean depthTest;
    private boolean blend;
    private boolean cull;
    private boolean scissor;

    public void capture() {
        program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        activeTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);

        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        texUnit0 = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
        texUnit1 = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE2);
        texUnit2 = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE3);
        texUnit3 = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);

        drawFbo = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        readFbo = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        vao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);

        depthTest = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
        blend = GL11C.glIsEnabled(GL11C.GL_BLEND);
        cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
        scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
    }

    public void restore() {
        GL30C.glBindVertexArray(vao);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFbo);
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFbo);

        GL13C.glActiveTexture(GL13C.GL_TEXTURE3);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texUnit3);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE2);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texUnit2);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texUnit1);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texUnit0);
        GL13C.glActiveTexture(activeTexture);

        GL20C.glUseProgram(program);
        GL11C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);

        setEnabled(GL11C.GL_DEPTH_TEST, depthTest);
        setEnabled(GL11C.GL_BLEND, blend);
        setEnabled(GL11C.GL_CULL_FACE, cull);
        setEnabled(GL11C.GL_SCISSOR_TEST, scissor);
    }

    private static void setEnabled(int cap, boolean on) {
        if (on) {
            GL11C.glEnable(cap);
        } else {
            GL11C.glDisable(cap);
        }
    }
}
