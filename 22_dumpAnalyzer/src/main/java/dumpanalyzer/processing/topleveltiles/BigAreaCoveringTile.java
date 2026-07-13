package dumpanalyzer.processing.topleveltiles;

import dumpanalyzer.model.TopLevelTileSet;
import dumpanalyzer.model.TileInstance;
import dumpanalyzer.processing.TriangleMeshVertexComparator;
import dumpanalyzer.processing.TriangleStripTileClassifier;
import dumpanalyzer.processing.TriangleStripTileTopology;
import java.util.ArrayList;
import java.util.List;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;

public final class BigAreaCoveringTile {
    private static final double UV_TOLERANCE = 1e-3;
    private static final int REQUIRED_TRIANGLE_STRIP_COUNT = 320;
    private static final int REQUIRED_TRIANGLE_STRIP_VERTEX_COUNT = 20;

    private final TriangleStripTileClassifier classifier = new TriangleStripTileClassifier();

    public TopLevelTileSet buildGlobeLevelTileSet(TileInstance tile) {
        if (tile == null) {
            return null;
        }
        List<TileInstance.TriangleStripGeometry> geometries = tile.getTriangleStripGeometries();
        if (geometries.size() <= 1) {
            return null;
        }
        if (geometries.size() != REQUIRED_TRIANGLE_STRIP_COUNT) {
            return new TopLevelTileSet(tile.getContentId(), null, null, null, null, null, geometries.size(), false);
        }

        List<TileInstance.TriangleStripVertex> allVertices = new ArrayList<>();
        for (TileInstance.TriangleStripGeometry geometry : geometries) {
            if (geometry == null || geometry.vertexCount() != REQUIRED_TRIANGLE_STRIP_VERTEX_COUNT) {
                return new TopLevelTileSet(tile.getContentId(), null, null, null, null, null, geometries.size(), false);
            }
            TriangleStripTileTopology topology = classifier.classify(geometry);
            if (topology != TriangleStripTileTopology.DEDUPLICATED_9_VERTICES_QUAD
                && topology != TriangleStripTileTopology.DEDUPLICATED_7_VERTICES_NORTH_POLE_TRIANGLE) {
                return new TopLevelTileSet(tile.getContentId(), null, null, null, null, null, geometries.size(), false);
            }
            allVertices.addAll(classifier.deduplicateVertices(
                geometry.vertices(),
                TriangleMeshVertexComparator.vertexEpsilon(geometry)
            ));
        }
        if (allVertices.isEmpty()) {
            return new TopLevelTileSet(tile.getContentId(), null, null, null, null, null, geometries.size(), false);
        }

        UvBounds bounds = computeBounds(allVertices);
        Vector3Dd center = average(allVertices, v -> true);
        Vector3Dd north = average(allVertices, v -> Math.abs(v.v() - bounds.maxV) <= UV_TOLERANCE);
        Vector3Dd south = average(allVertices, v -> Math.abs(v.v() - bounds.minV) <= UV_TOLERANCE);
        Vector3Dd east = average(allVertices, v -> Math.abs(v.u() - bounds.maxU) <= UV_TOLERANCE);
        Vector3Dd west = average(allVertices, v -> Math.abs(v.u() - bounds.minU) <= UV_TOLERANCE);
        if (center == null || north == null || south == null || east == null || west == null) {
            return new TopLevelTileSet(tile.getContentId(), null, null, null, null, null, geometries.size(), false);
        }

        return new TopLevelTileSet(tile.getContentId(), center, north, south, east, west, geometries.size(), true);
    }

    private static UvBounds computeBounds(List<TileInstance.TriangleStripVertex> vertices) {
        double minU = Double.POSITIVE_INFINITY;
        double maxU = Double.NEGATIVE_INFINITY;
        double minV = Double.POSITIVE_INFINITY;
        double maxV = Double.NEGATIVE_INFINITY;
        for (TileInstance.TriangleStripVertex vertex : vertices) {
            if (vertex == null) {
                continue;
            }
            minU = Math.min(minU, vertex.u());
            maxU = Math.max(maxU, vertex.u());
            minV = Math.min(minV, vertex.v());
            maxV = Math.max(maxV, vertex.v());
        }
        return new UvBounds(minU, maxU, minV, maxV);
    }

    private static Vector3Dd average(
        List<TileInstance.TriangleStripVertex> vertices,
        java.util.function.Predicate<TileInstance.TriangleStripVertex> predicate
    ) {
        double sx = 0.0;
        double sy = 0.0;
        double sz = 0.0;
        int count = 0;
        for (TileInstance.TriangleStripVertex vertex : vertices) {
            if (vertex == null || !predicate.test(vertex)) {
                continue;
            }
            sx += vertex.x();
            sy += vertex.y();
            sz += vertex.z();
            count++;
        }
        if (count <= 0) {
            return null;
        }
        double inv = 1.0 / count;
        return new Vector3Dd(sx * inv, sy * inv, sz * inv);
    }

    private record UvBounds(double minU, double maxU, double minV, double maxV) {
    }
}
