package planetdemviewer.render;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.util.texture.Texture;
import com.jogamp.opengl.util.texture.TextureCoords;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import planetdemviewer.io.TileImageLoader;
import planetdemviewer.io.TileTreeDiscoveryService;
import planetdemviewer.model.PyramidalImageInstance;
import planetdemviewer.model.QuadtreeNode;
import planetdemviewer.processing.DrawCommand;
import planetdemviewer.processing.QuadtreeDrawPlanner;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.render.jogl.Jogl4LineRenderer;
import vsdk.toolkit.render.jogl.Jogl4MatrixRenderer;
import vsdk.toolkit.render.jogl.Jogl4ShaderProgramUtil;

/** Draws DEM palette tiles with a GL4 core shader and streamed quad vertices. */
public final class Jogl4QuadtreeRenderer {
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

    private final TextureCache textureCache = new TextureCache();
    private int program;
    private int vao;
    private int vbo;
    private int mvpLocation;
    private int opacityLocation;
    private final QuadtreeDrawPlanner.LodState lodState = new QuadtreeDrawPlanner.LodState();

    public List<DrawCommand> draw(GL4 gl, PyramidalImageInstance instance, Camera camera, double relativeScale,
                     boolean wires, boolean tileBoundsVisible, boolean uniformLod, TileImageLoader loader,
                     TileTreeDiscoveryService discoveryService) {
        List<DrawCommand> commands = uniformLod
            ? QuadtreeDrawPlanner.selectUniformLod(instance, camera, relativeScale)
            : QuadtreeDrawPlanner.select(instance, camera, relativeScale, lodState);
        return drawCommands(gl, instance, commands,
            camera.calculateProjectionMatrix(), wires, tileBoundsVisible, loader, false, discoveryService);
    }

    public List<DrawCommand> drawAll(GL4 gl, PyramidalImageInstance instance, double relativeScale,
                        Matrix4x4d projection, boolean wires, TileImageLoader loader) {
        return drawCommands(gl, instance, QuadtreeDrawPlanner.selectAllLeaves(instance, relativeScale),
            projection, wires, false, loader, true, null);
    }

    private List<DrawCommand> drawCommands(GL4 gl, PyramidalImageInstance instance, List<DrawCommand> commands,
                              Matrix4x4d projection, boolean wires, boolean tileBoundsVisible,
                              TileImageLoader loader,
                              boolean synchronous, TileTreeDiscoveryService discoveryService) {
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
        List<DrawCommand> drawnCommands = new ArrayList<>();
        for (DrawCommand command : commands) {
            boolean drawable = drawNode(gl, command, loader, synchronous);
            if (drawable) {
                drawnCommands.add(command);
            }
            if (drawable && discoveryService != null) {
                discoveryService.requestVisible(instance.getImage(),
                    command.node().nearestSelfOrAncestorWithTile());
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

    private boolean drawNode(GL4 gl, DrawCommand command, TileImageLoader loader, boolean synchronous) {
        QuadtreeNode node = command.node();
        Texture texture = null;
        QuadtreeNode textureOwner = null;
        if (node.getTileFile() != null) {
            texture = textureCache.acquire(gl, node.getTileFile(), loader, synchronous);
            textureOwner = node;
        }
        if (texture == null) {
            for (QuadtreeNode ancestor = node.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
                if (ancestor.getTileFile() == null) {
                    continue;
                }
                texture = textureCache.peekResident(ancestor.getTileFile());
                if (texture != null) {
                    textureOwner = ancestor;
                    // The fallback ancestor is the page actually displayed.
                    // Refresh its source DEM page as well as its GPU texture.
                    if (loader != null) {
                        loader.peekElevation(ancestor.getTileFile());
                    }
                    break;
                }
            }
        }
        if (texture == null) {
            return false;
        }
        double[] subRect = textureOwner == node
            ? new double[] {0.0, 0.0, 1.0, 1.0}
            : node.subRectRelativeTo(textureOwner);
        texture.bind(gl);
        TextureCoords tc = texture.getImageTexCoords();
        float left = interpolate(tc.left(), tc.right(), subRect[0]);
        float right = interpolate(tc.left(), tc.right(), subRect[2]);
        float bottom = interpolate(tc.bottom(), tc.top(), subRect[1]);
        float top = interpolate(tc.bottom(), tc.top(), subRect[3]);
        Vector3Dd[] c = command.corners();
        // Triangle strip order: south-west, south-east, north-west, north-east.
        float[] vertices = new float[20];
        putVertex(vertices, 0, c[0], left, bottom);
        putVertex(vertices, 5, c[1], right, bottom);
        putVertex(vertices, 10, c[3], left, top);
        putVertex(vertices, 15, c[2], right, top);
        FloatBuffer buffer = Buffers.newDirectFloatBuffer(vertices.length);
        buffer.put(vertices).flip();
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo);
        gl.glBufferSubData(GL.GL_ARRAY_BUFFER, 0, (long) vertices.length * Float.BYTES, buffer);
        gl.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4);
        return true;
    }

    private static void putVertex(float[] vertices, int offset, Vector3Dd point, float u, float v) {
        vertices[offset] = (float) point.x();
        vertices[offset + 1] = (float) point.y();
        vertices[offset + 2] = (float) point.z();
        vertices[offset + 3] = u;
        vertices[offset + 4] = v;
    }

    private static void drawWires(GL4 gl, List<DrawCommand> commands, Matrix4x4d projection) {
        float[] positions = new float[commands.size() * 8 * 3];
        float[] colors = new float[positions.length];
        int offset = 0;
        for (DrawCommand command : commands) {
            Vector3Dd[] corners = command.corners();
            for (int i = 0; i < 4; i++) {
                offset = putLinePoint(positions, colors, offset, corners[i]);
                offset = putLinePoint(positions, colors, offset, corners[(i + 1) % 4]);
            }
        }
        Jogl4LineRenderer.drawLines(gl, projection, positions, colors, 1.0f, -0.0005f);
    }

    private static int putLinePoint(float[] positions, float[] colors, int offset, Vector3Dd point) {
        positions[offset] = (float) point.x();
        positions[offset + 1] = (float) point.y();
        positions[offset + 2] = (float) point.z();
        colors[offset] = colors[offset + 1] = colors[offset + 2] = 1.0f;
        return offset + 3;
    }

    private static float interpolate(float from, float to, double fraction) {
        return (float) (from + fraction * (to - from));
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

    public long getGpuBytesAssigned() {
        return textureCache.getBytesAssigned();
    }

    public int getResidentTextureCount() {
        return textureCache.getResidentCount();
    }

    public void invalidateTextures(GL4 gl) {
        textureCache.dispose(gl);
    }

    public void dispose(GL4 gl) {
        textureCache.dispose(gl);
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
