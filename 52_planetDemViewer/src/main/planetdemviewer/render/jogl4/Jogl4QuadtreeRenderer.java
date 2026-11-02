package planetdemviewer.render.jogl4;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import planetdemviewer.model.PyramidalImageInstance;
import planetdemviewer.processing.DrawCommand;
import planetdemviewer.render.PaletteFramePlanner;
import planetdemviewer.render.PaletteTile;
import planetdemviewer.render.TileQuadGeometry;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.render.jogl.Jogl4LineRenderer;
import vsdk.toolkit.render.jogl.Jogl4MatrixRenderer;
import vsdk.toolkit.render.jogl.Jogl4ShaderProgramUtil;

/** Draws DEM palette tiles with a GL4 core shader and streamed quad vertices. */
final class Jogl4QuadtreeRenderer {
    private static final String VERTEX_SHADER = """
        #version 410 core
        layout(location = 0) in vec3 position;
        layout(location = 1) in vec2 texCoord;
        uniform mat4 mvp;
        out vec2 uv;
        void main() {
            gl_Position = mvp * vec4(position, 1.0);
            uv = texCoord;
        }
        """;
    private static final String FRAGMENT_SHADER = """
        #version 410 core
        in vec2 uv;
        uniform sampler2D tileTexture;
        uniform float opacity;
        out vec4 fragColor;
        void main() {
            fragColor = texture(tileTexture, uv) * vec4(1.0, 1.0, 1.0, opacity);
        }
        """;

    private final PaletteFramePlanner<Integer> planner;
    private int program;
    private int vao;
    private int vbo;
    private int mvpLocation;
    private int opacityLocation;

    Jogl4QuadtreeRenderer(PaletteFramePlanner<Integer> planner) {
        this.planner = planner;
    }

    List<DrawCommand> draw(GL4 gl, PyramidalImageInstance instance, Camera camera, double relativeScale,
                           boolean wires, boolean tileBoundsVisible, boolean uniformLod) {
        return drawCommands(gl, instance, planner.selectVisible(instance, camera, relativeScale, uniformLod),
            camera.calculateProjectionMatrix(), wires, tileBoundsVisible, false);
    }

    List<DrawCommand> drawAll(GL4 gl, PyramidalImageInstance instance, double relativeScale,
                              Matrix4x4d projection, boolean wires) {
        return drawCommands(gl, instance, planner.selectAll(instance, relativeScale),
            projection, wires, false, true);
    }

    private List<DrawCommand> drawCommands(GL4 gl, PyramidalImageInstance instance, List<DrawCommand> commands,
                                           Matrix4x4d projection, boolean wires, boolean tileBoundsVisible,
                                           boolean synchronous) {
        if (commands.isEmpty()) {
            return List.of();
        }
        ensureInitialized(gl);
        gl.glUseProgram(program);
        gl.glUniformMatrix4fv(mvpLocation, 1, false,
            Jogl4MatrixRenderer.toColumnMajorFloatArray(projection), 0);
        gl.glUniform1f(opacityLocation, (float) instance.getOpacity());
        gl.glActiveTexture(GL.GL_TEXTURE0);
        gl.glBindVertexArray(vao);
        Jogl4TextureCacheHelper gpu = new Jogl4TextureCacheHelper(gl);
        List<DrawCommand> drawnCommands = new ArrayList<>();
        for (DrawCommand command : commands) {
            PaletteTile<Integer> tile = planner.resolve(command, synchronous, gpu);
            if (tile == null) {
                continue;
            }
            drawTile(gl, command, tile);
            drawnCommands.add(command);
            if (!synchronous) {
                planner.tileDisplayed(instance, command);
            }
        }
        gl.glBindVertexArray(0);
        gl.glBindTexture(GL.GL_TEXTURE_2D, 0);
        gl.glUseProgram(0);
        if (wires) {
            drawWires(gl, commands, projection);
        }
        if (tileBoundsVisible) {
            // Palette tiles have no elevation geometry: their min/max volume collapses to this plane.
            drawWires(gl, drawnCommands, projection);
        }
        return List.copyOf(drawnCommands);
    }

    private void drawTile(GL4 gl, DrawCommand command, PaletteTile<Integer> tile) {
        gl.glBindTexture(GL.GL_TEXTURE_2D, tile.texture());
        float[] vertices = TileQuadGeometry.texturedStrip(command.corners(), tile.subRect());
        FloatBuffer buffer = Buffers.newDirectFloatBuffer(vertices.length);
        buffer.put(vertices).flip();
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo);
        gl.glBufferSubData(GL.GL_ARRAY_BUFFER, 0, (long) vertices.length * Float.BYTES, buffer);
        gl.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, TileQuadGeometry.QUAD_VERTICES);
    }

    private static void drawWires(GL4 gl, List<DrawCommand> commands, Matrix4x4d projection) {
        float[] positions = TileQuadGeometry.outlinePositions(commands);
        Jogl4LineRenderer.drawLines(gl, projection, positions,
            TileQuadGeometry.whiteColors(positions.length), 1.0f, -0.0005f);
    }

    private void ensureInitialized(GL4 gl) {
        if (program != 0) {
            return;
        }
        program = Jogl4ShaderProgramUtil.createProgramFromSources(gl, VERTEX_SHADER, FRAGMENT_SHADER);
        mvpLocation = gl.glGetUniformLocation(program, "mvp");
        opacityLocation = gl.glGetUniformLocation(program, "opacity");
        gl.glUseProgram(program);
        gl.glUniform1i(gl.glGetUniformLocation(program, "tileTexture"), 0);
        gl.glUseProgram(0);
        int[] ids = new int[1];
        gl.glGenVertexArrays(1, ids, 0);
        vao = ids[0];
        gl.glGenBuffers(1, ids, 0);
        vbo = ids[0];
        gl.glBindVertexArray(vao);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo);
        gl.glBufferData(GL.GL_ARRAY_BUFFER, 20L * Float.BYTES, null, GL4.GL_STREAM_DRAW);
        gl.glEnableVertexAttribArray(0);
        gl.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, 5 * Float.BYTES, 0L);
        gl.glEnableVertexAttribArray(1);
        gl.glVertexAttribPointer(1, 2, GL.GL_FLOAT, false, 5 * Float.BYTES, 3L * Float.BYTES);
        gl.glBindVertexArray(0);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
    }

    void invalidateTextures(GL4 gl) {
        planner.clearTextures(new Jogl4TextureCacheHelper(gl));
    }

    void dispose(GL4 gl) {
        invalidateTextures(gl);
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
