package planetdemviewer.render.jogl4;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import vsdk.toolkit.render.jogl.Jogl4ShaderProgramUtil;

/** Renders Java2D glyphs as a textured screen-space quad on a GL4 core context. */
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

    private final Font font;
    private int program;
    private int vao;
    private int vbo;
    private int texture;

    Jogl4TextRenderer(Font font) {
        this.font = font;
    }

    /** x and baselineY are window pixel coordinates with a bottom-left origin. */
    void draw(GL4 gl, String text, int x, int baselineY, int canvasWidth, int canvasHeight) {
        draw(gl, text, x, baselineY, canvasWidth, canvasHeight, Color.WHITE);
    }

    void drawCentered(GL4 gl, String text, int centerX, int baselineY, int canvasWidth, int canvasHeight,
                      Color color) {
        draw(gl, text, centerX - textWidth(text) / 2, baselineY, canvasWidth, canvasHeight, color);
    }

    void drawRightAligned(GL4 gl, String text, int rightX, int baselineY,
                          int canvasWidth, int canvasHeight, Color color) {
        draw(gl, text, rightX - textWidth(text), baselineY, canvasWidth, canvasHeight, color);
    }

    /** x and baselineY are window pixel coordinates with a bottom-left origin. */
    void draw(GL4 gl, String text, int x, int baselineY, int canvasWidth, int canvasHeight, Color color) {
        if (text == null || text.isEmpty()) {
            return;
        }
        ensureInitialized(gl);
        BufferedImage measure = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D measureGraphics = measure.createGraphics();
        measureGraphics.setFont(font);
        FontMetrics metrics = measureGraphics.getFontMetrics();
        int width = Math.max(1, metrics.stringWidth(text) + 2);
        int height = Math.max(1, metrics.getHeight() + 2);
        int descent = metrics.getDescent();
        measureGraphics.dispose();

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setFont(font);
        graphics.setColor(color == null ? Color.WHITE : color);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.drawString(text, 1, metrics.getAscent());
        graphics.dispose();

        ByteBuffer pixels = Buffers.newDirectByteBuffer(width * height * 4);
        for (int row = height - 1; row >= 0; row--) {
            for (int col = 0; col < width; col++) {
                int argb = image.getRGB(col, row);
                pixels.put((byte) (argb >>> 16));
                pixels.put((byte) (argb >>> 8));
                pixels.put((byte) argb);
                pixels.put((byte) (argb >>> 24));
            }
        }
        pixels.flip();

        float left = 2.0f * x / canvasWidth - 1.0f;
        float right = 2.0f * (x + width) / canvasWidth - 1.0f;
        float bottom = 2.0f * (baselineY - descent - 2) / canvasHeight - 1.0f;
        float top = 2.0f * (baselineY - descent - 2 + height) / canvasHeight - 1.0f;
        FloatBuffer vertices = Buffers.newDirectFloatBuffer(new float[] {
            left, bottom, 0, 0,
            right, bottom, 1, 0,
            left, top, 0, 1,
            right, top, 1, 1
        });

        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        gl.glUseProgram(program);
        gl.glActiveTexture(GL.GL_TEXTURE0);
        gl.glBindTexture(GL.GL_TEXTURE_2D, texture);
        gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 1);
        gl.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL4.GL_RGBA8, width, height, 0,
            GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, pixels);
        gl.glBindVertexArray(vao);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo);
        gl.glBufferSubData(GL.GL_ARRAY_BUFFER, 0, 16L * Float.BYTES, vertices);
        gl.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4);
        gl.glBindVertexArray(0);
        gl.glBindTexture(GL.GL_TEXTURE_2D, 0);
        gl.glUseProgram(0);
    }

    private int textWidth(String text) {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setFont(font);
        int width = graphics.getFontMetrics().stringWidth(text) + 2;
        graphics.dispose();
        return width;
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
