package planetdemviewer.render;

import planetdemviewer.model.QuadtreeNode;
import vsdk.toolkit.environment.geometry.surface.TriangleMesh;

/** A terrain mesh as the single-precision arrays a graphics backend uploads. */
public record TerrainMeshBuffers(float[] positions, float[] normals, float[] textureCoordinates,
                                 int[] indices, float[] boundsLines, int vertexCount) {
    public static final int BOUNDS_LINE_VERTICES = 24;

    /** What these arrays occupy once uploaded. */
    public long byteSize() {
        return (long) (positions.length + normals.length + textureCoordinates.length + boundsLines.length) * Float.BYTES
            + (long) indices.length * Integer.BYTES;
    }

    public static TerrainMeshBuffers of(TriangleMesh mesh, QuadtreeNode node) {
        double[] positions = mesh.getVertexPositions();
        return new TerrainMeshBuffers(toFloatArray(positions), toFloatArray(mesh.getVertexNormals()),
            textureCoordinatesFor(positions, node), mesh.getTriangleIndexes(),
            boundsLinesOf(positions), positions.length / 3);
    }

    /**
     * Maps the exact square terrain vertex grid to the corresponding PNG.
     * Edge vertices have s/t values 0 and 1 exactly.  This must not be
     * reconstructed from global positions: LOD edge stitching can move those
     * positions beyond the logical tile.  PNG row zero is its top edge, and
     * a Vitral image stores its bottom row first, at v = 0.
     */
    static float[] textureCoordinatesFor(double[] positions, QuadtreeNode node) {
        if (positions == null || positions.length % 3 != 0 || node == null) {
            throw new IllegalArgumentException("Mesh positions and tile node are required");
        }
        int vertexCount = positions.length / 3;
        int gridSize = (int) Math.round(Math.sqrt(vertexCount));
        if (gridSize < 2 || gridSize * gridSize != vertexCount) {
            throw new IllegalArgumentException("Terrain imagery requires a square vertex grid");
        }
        float[] uvs = new float[vertexCount * 2];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int row = vertex / gridSize;
            int column = vertex % gridSize;
            int uvOffset = vertex * 2;
            uvs[uvOffset] = (float) column / (gridSize - 1);
            uvs[uvOffset + 1] = (float) (gridSize - 1 - row) / (gridSize - 1);
        }
        return uvs;
    }

    /** Edges of the min/max box of every vertex, independently of the triangle topology. */
    static float[] boundsLinesOf(double[] positions) {
        if (positions.length == 0 || positions.length % 3 != 0) {
            throw new IllegalArgumentException("Expected complete xyz vertex positions");
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int offset = 0; offset < positions.length; offset += 3) {
            minX = Math.min(minX, positions[offset]);
            minY = Math.min(minY, positions[offset + 1]);
            minZ = Math.min(minZ, positions[offset + 2]);
            maxX = Math.max(maxX, positions[offset]);
            maxY = Math.max(maxY, positions[offset + 1]);
            maxZ = Math.max(maxZ, positions[offset + 2]);
        }
        float[] corners = {
            (float) minX, (float) minY, (float) minZ,
            (float) maxX, (float) minY, (float) minZ,
            (float) maxX, (float) maxY, (float) minZ,
            (float) minX, (float) maxY, (float) minZ,
            (float) minX, (float) minY, (float) maxZ,
            (float) maxX, (float) minY, (float) maxZ,
            (float) maxX, (float) maxY, (float) maxZ,
            (float) minX, (float) maxY, (float) maxZ
        };
        int[] edges = {0, 1, 1, 2, 2, 3, 3, 0, 4, 5, 5, 6, 6, 7, 7, 4, 0, 4, 1, 5, 2, 6, 3, 7};
        float[] lines = new float[edges.length * 3];
        for (int vertex = 0; vertex < edges.length; vertex++) {
            System.arraycopy(corners, edges[vertex] * 3, lines, vertex * 3, 3);
        }
        return lines;
    }

    private static float[] toFloatArray(double[] source) {
        float[] result = new float[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = (float) source[i];
        }
        return result;
    }
}
