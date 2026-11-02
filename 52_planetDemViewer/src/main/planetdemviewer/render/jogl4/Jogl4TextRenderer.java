package planetdemviewer.render.jogl4;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;
import java.nio.FloatBuffer;
import java.util.List;
import planetdemviewer.render.DirectBuffers;
import planetdemviewer.render.RasterizedText;
import planetdemviewer.render.TextLabel;
import planetdemviewer.render.TextRasterizer;
import vsdk.toolkit.render.jogl.Jogl4ShaderProgramUtil;

/** Shows rasterized text as a textured screen-space quad on a GL4 core context. */
final class Jogl4TextRenderer {
    private static final String VERTEX_SHADER = """
        #version 410 core
        layout(location = 0) in vec2 position;
        layout(location = 1) in vec2 texCoord;
        out vec2 uv;
        void main() {
            gl_Position = vec4(position, 0.0, 1.0);
            uv = texCoord;
        }
        """;
    private static final String FRAGMENT_SHADER = """
        #version 410 core
        in vec2 uv;
        uniform sampler2D glyphs;
        out vec4 fragColor;
        void main() {
            fragColor = texture(glyphs, uv);
        }
        """;

    private final TextRasterizer rasterizer;
    private int program;
    private int vao;
    private int vbo;
    private int texture;

    Jogl4TextRenderer(TextRasterizer rasterizer) {
        this.rasterizer = rasterizer;
    }

    void draw(GL4 gl, List<TextLabel> labels, int canvasWidth, int canvasHeight) {
        for (TextLabel label : labels) {
            draw(gl, label, canvasWidth, canvasHeight);
        }
    }

    void draw(GL4 gl, TextLabel label, int canvasWidth, int canvasHeight) {
        RasterizedText text = rasterizer.rasterize(label, canvasWidth, canvasHeight);
        if (text == null) {
            return;
        }
        ensureInitialized(gl);
        FloatBuffer vertices = DirectBuffers.floats(text.quadVertices());

        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        gl.glUseProgram(program);
        gl.glActiveTexture(GL.GL_TEXTURE0);
        gl.glBindTexture(GL.GL_TEXTURE_2D, texture);
        gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 1);
        gl.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL4.GL_RGBA8, text.image().getXSize(), text.image().getYSize(), 0,
            GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, text.image().getRawImageDirectBuffer());
        gl.glBindVertexArray(vao);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo);
        gl.glBufferSubData(GL.GL_ARRAY_BUFFER, 0, 16L * Float.BYTES, vertices);
        gl.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4);
        gl.glBindVertexArray(0);
        gl.glBindTexture(GL.GL_TEXTURE_2D, 0);
        gl.glUseProgram(0);
    }

    private void ensureInitialized(GL4 gl) {
        if (program != 0) {
            return;
        }
        program = Jogl4ShaderProgramUtil.createProgramFromSources(gl, VERTEX_SHADER, FRAGMENT_SHADER);
        gl.glUseProgram(program);
        gl.glUniform1i(gl.glGetUniformLocation(program, "glyphs"), 0);
        gl.glUseProgram(0);
        int[] ids = new int[1];
        gl.glGenVertexArrays(1, ids, 0);
        vao = ids[0];
        gl.glGenBuffers(1, ids, 0);
        vbo = ids[0];
        gl.glGenTextures(1, ids, 0);
        texture = ids[0];
        gl.glBindTexture(GL.GL_TEXTURE_2D, texture);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE);
        gl.glBindTexture(GL.GL_TEXTURE_2D, 0);
        gl.glBindVertexArray(vao);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo);
        gl.glBufferData(GL.GL_ARRAY_BUFFER, 16L * Float.BYTES, null, GL4.GL_STREAM_DRAW);
        gl.glEnableVertexAttribArray(0);
        gl.glVertexAttribPointer(0, 2, GL.GL_FLOAT, false, 4 * Float.BYTES, 0L);
        gl.glEnableVertexAttribArray(1);
        gl.glVertexAttribPointer(1, 2, GL.GL_FLOAT, false, 4 * Float.BYTES, 2L * Float.BYTES);
        gl.glBindVertexArray(0);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
    }

    void dispose(GL4 gl) {
        if (texture != 0) {
            gl.glDeleteTextures(1, new int[] {texture}, 0);
            texture = 0;
        }
        if (vbo != 0) {
            gl.glDeleteBuffers(1, new int[] {vbo}, 0);
            vbo = 0;
        }
        if (vao != 0) {
            gl.glDeleteVertexArrays(1, new int[] {vao}, 0);
            vao = 0;
        }
        if (program != 0) {
            gl.glDeleteProgram(program);
            program = 0;
        }
    }
}
