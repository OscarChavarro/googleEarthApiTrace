package planetdemviewer.render;

import java.util.Arrays;
import java.util.List;
import planetdemviewer.processing.DrawCommand;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;

/** Vertex data of flat tiles: textured quads and their outlines. */
public final class TileQuadGeometry {
    public static final int VERTEX_FLOATS = 5;
    public static final int QUAD_VERTICES = 4;

    private TileQuadGeometry() {
    }

    /**
     * Triangle strip of xyz+uv vertices, in order south-west, south-east, north-west, north-east.
     * The texture coordinates are the sub-rectangle {left, bottom, right, top} of the image, whose
     * first row in memory is its bottom row.
     */
    public static float[] texturedStrip(Vector3Dd[] corners, double[] subRect) {
        float left = (float) subRect[0];
        float right = (float) subRect[2];
        float bottom = (float) subRect[1];
        float top = (float) subRect[3];
        float[] vertices = new float[QUAD_VERTICES * VERTEX_FLOATS];
        putVertex(vertices, 0, corners[0], left, bottom);
        putVertex(vertices, VERTEX_FLOATS, corners[1], right, bottom);
        putVertex(vertices, 2 * VERTEX_FLOATS, corners[3], left, top);
        putVertex(vertices, 3 * VERTEX_FLOATS, corners[2], right, top);
        return vertices;
    }

    /** Line segments, as xyz pairs, around each tile. */
    public static float[] outlinePositions(List<DrawCommand> commands) {
        float[] positions = new float[commands.size() * 8 * 3];
        int offset = 0;
        for (DrawCommand command : commands) {
            Vector3Dd[] corners = command.corners();
            for (int i = 0; i < 4; i++) {
                offset = putPoint(positions, offset, corners[i]);
                offset = putPoint(positions, offset, corners[(i + 1) % 4]);
            }
        }
        return positions;
    }

    public static float[] whiteColors(int componentCount) {
        float[] colors = new float[componentCount];
        Arrays.fill(colors, 1.0f);
        return colors;
    }

    private static void putVertex(float[] vertices, int offset, Vector3Dd point, float u, float v) {
        putPoint(vertices, offset, point);
        vertices[offset + 3] = u;
        vertices[offset + 4] = v;
    }

    private static int putPoint(float[] target, int offset, Vector3Dd point) {
        target[offset] = (float) point.x();
        target[offset + 1] = (float) point.y();
        target[offset + 2] = (float) point.z();
        return offset + 3;
    }
}
