package dev.fallingcloud.interframe.gl;

import dev.fallingcloud.interframe.Interframe;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

import java.util.HashMap;
import java.util.Map;

/**
 * A minimal linked GLSL program plus its own (empty) VAO, used to draw a single fullscreen triangle. The
 * triangle is generated entirely in the vertex shader from {@code gl_VertexID}, so there are no vertex
 * buffers or attribute arrays to manage — the core-profile context only needs <em>a</em> VAO bound for
 * the draw to be legal.
 */
public final class ShaderProgram {
    private int program;
    private int vao;
    private final Map<String, Integer> uniforms = new HashMap<>();

    /** @return true on success; on failure logs the compile/link error and leaves this unusable. */
    public boolean compile(String vertexSrc, String fragmentSrc) {
        int vs = compileStage(GL20C.GL_VERTEX_SHADER, vertexSrc, "vertex");
        int fs = compileStage(GL20C.GL_FRAGMENT_SHADER, fragmentSrc, "fragment");
        if (vs == 0 || fs == 0) {
            if (vs != 0) GL20C.glDeleteShader(vs);
            if (fs != 0) GL20C.glDeleteShader(fs);
            return false;
        }
        program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vs);
        GL20C.glAttachShader(program, fs);
        GL20C.glLinkProgram(program);
        GL20C.glDeleteShader(vs);
        GL20C.glDeleteShader(fs);
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0) {
            Interframe.LOGGER.error("[Interframe] Shader link failed: {}", GL20C.glGetProgramInfoLog(program));
            GL20C.glDeleteProgram(program);
            program = 0;
            return false;
        }
        vao = GL30C.glGenVertexArrays();
        return true;
    }

    private static int compileStage(int type, String src, String label) {
        int id = GL20C.glCreateShader(type);
        GL20C.glShaderSource(id, src);
        GL20C.glCompileShader(id);
        if (GL20C.glGetShaderi(id, GL20C.GL_COMPILE_STATUS) == 0) {
            Interframe.LOGGER.error("[Interframe] {} shader compile failed: {}", label, GL20C.glGetShaderInfoLog(id));
            GL20C.glDeleteShader(id);
            return 0;
        }
        return id;
    }

    public boolean ok() {
        return program != 0;
    }

    public void bind() {
        GL20C.glUseProgram(program);
        GL30C.glBindVertexArray(vao);
    }

    public void setInt(String name, int value) {
        GL20C.glUniform1i(loc(name), value);
    }

    public void setFloat(String name, float value) {
        GL20C.glUniform1f(loc(name), value);
    }

    public void setVec2(String name, float x, float y) {
        GL20C.glUniform2f(loc(name), x, y);
    }

    public void setVec3(String name, org.joml.Vector3f v) {
        GL20C.glUniform3f(loc(name), v.x, v.y, v.z);
    }

    private final float[] mat3Scratch = new float[9];

    public void setMat3(String name, org.joml.Matrix3f m) {
        m.get(mat3Scratch); // column-major, matching GL's expectation
        GL20C.glUniformMatrix3fv(loc(name), false, mat3Scratch);
    }

    private int loc(String name) {
        Integer cached = uniforms.get(name);
        if (cached != null) {
            return cached;
        }
        int l = GL20C.glGetUniformLocation(program, name);
        uniforms.put(name, l);
        return l;
    }

    /** Draws the fullscreen triangle. Assumes {@link #bind()} was called and the target FBO/viewport set. */
    public void drawFullscreen() {
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
    }

    public void dispose() {
        if (program != 0) {
            GL20C.glDeleteProgram(program);
            program = 0;
        }
        if (vao != 0) {
            GL30C.glDeleteVertexArrays(vao);
            vao = 0;
        }
        uniforms.clear();
    }
}
