package dumpanalyzer.processing;

import java.util.ArrayList;
import java.util.List;

import dumpanalyzer.model.TileInstance;

public final class TriangleStripTileClassifier {
    public static final int TRIANGLE_STRIP_VERTEX_COUNT = 20;
    public static final int DEDUPLICATED_QUAD_VERTEX_COUNT = 9;
    public static final int DEDUPLICATED_NORTH_POLE_TRIANGLE_VERTEX_COUNT = 7;

    public TriangleStripTileTopology classify(TileInstance.TriangleStripGeometry geometry) {
        if (geometry == null || geometry.vertexCount() != TRIANGLE_STRIP_VERTEX_COUNT) {
            return TriangleStripTileTopology.UNKNOWN;
        }
        double epsilon = TriangleMeshVertexComparator.vertexEpsilon(geometry);
        List<TileInstance.TriangleStripVertex> unique = deduplicateVertices(
            geometry.vertices(),
            epsilon
        );
        return classifyDeduplicatedVertices(unique);
    }

    public TriangleStripTileTopology classifyDeduplicatedVertices(List<TileInstance.TriangleStripVertex> unique) {
        if (unique == null) {
            return TriangleStripTileTopology.UNKNOWN;
        }
        if (unique.size() == DEDUPLICATED_QUAD_VERTEX_COUNT) {
            return TriangleStripTileTopology.DEDUPLICATED_9_VERTICES_QUAD;
        }
        if (unique.size() == DEDUPLICATED_NORTH_POLE_TRIANGLE_VERTEX_COUNT) {
            return TriangleStripTileTopology.DEDUPLICATED_7_VERTICES_NORTH_POLE_TRIANGLE;
        }
        return TriangleStripTileTopology.UNKNOWN;
    }

    public List<TileInstance.TriangleStripVertex> deduplicateVertices(
        List<TileInstance.TriangleStripVertex> vertices,
        double epsilon
    ) {
        List<TileInstance.TriangleStripVertex> out = new ArrayList<>();
        if (vertices == null || vertices.isEmpty()) {
            return out;
        }
        for (TileInstance.TriangleStripVertex v : vertices) {
            boolean exists = false;
            for (TileInstance.TriangleStripVertex e : out) {
                if (Math.abs(v.x() - e.x()) <= epsilon
                    && Math.abs(v.y() - e.y()) <= epsilon
                    && Math.abs(v.z() - e.z()) <= epsilon) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                out.add(v);
            }
        }
        return out;
    }
}
