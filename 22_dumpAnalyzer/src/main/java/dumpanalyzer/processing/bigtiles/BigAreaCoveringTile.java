package dumpanalyzer.processing.bigtiles;

import dumpanalyzer.model.BigTile;
import dumpanalyzer.model.TileInstance;
import dumpanalyzer.processing.TriangleMeshVertexComparator;
import dumpanalyzer.processing.TriangleStripTileClassifier;
import dumpanalyzer.processing.TriangleStripTileTopology;
import java.util.ArrayList;
import java.util.List;
import vsdk.toolkit.common.linealAlgebra.Vector3D;

public final class BigAreaCoveringTile {
    private static final double UV_TOLERANCE = 1e-3;

    private final TriangleStripTileClassifier classifier = new TriangleStripTileClassifier();

    public BigTile groupTilesIntoBigTile(TileInstance tile) {
        if (tile == null) {
            return null;
        }
        List<TileInstance.TriangleStripGeometry> geometries = tile.getTriangleStripGeometries();
        if (geometries.size() <= 1) {
            return null;
        }

        List<TileInstance.TriangleStripVertex> allVertices = new ArrayList<>();
        for (TileInstance.TriangleStripGeometry geometry : geometries) {
            TriangleStripTileTopology topology = classifier.classify(geometry);
            if (topology != TriangleStripTileTopology.DEDUPLICATED_9_VERTICES_QUAD
                && topology != TriangleStripTileTopology.DEDUPLICATED_7_VERTICES_NORTH_POLE_TRIANGLE) {
                return null;
            }
            if (coversFullUvRange(geometry.vertices())) {
                return null;
            }
            allVertices.addAll(classifier.deduplicateVertices(
                geometry.vertices(),
                TriangleMeshVertexComparator.VERTEX_EPSILON
            ));
        }
        if (allVertices.isEmpty()) {
            return null;
        }

        UvBounds bounds = computeBounds(allVertices);
        if (!bounds.coversWholeTexture() && !isEarlyTextureMiddleBandCase(tile, bounds)) {
            return null;
        }

        Vector3D center = average(allVertices, v -> true);
        Vector3D north = average(allVertices, v -> Math.abs(v.v() - bounds.maxV) <= UV_TOLERANCE);
        Vector3D south = average(allVertices, v -> Math.abs(v.v() - bounds.minV) <= UV_TOLERANCE);
        Vector3D east = average(allVertices, v -> Math.abs(v.u() - bounds.maxU) <= UV_TOLERANCE);
        Vector3D west = average(allVertices, v -> Math.abs(v.u() - bounds.minU) <= UV_TOLERANCE);
        if (center == null || north == null || south == null || east == null || west == null) {
            return null;
        }

        return new BigTile(tile.getContentId(), center, north, south, east, west);
    }

    private static boolean coversFullUvRange(List<TileInstance.TriangleStripVertex> vertices) {
        return computeBounds(vertices).coversWholeTexture();
    }

    private static boolean isEarlyTextureMiddleBandCase(TileInstance tile, UvBounds bounds) {
        Integer legacyTextureId = legacyTextureIdFromContent(tile == null ? null : tile.getContentId());
        if (legacyTextureId == null || legacyTextureId < 0 || legacyTextureId > 5) {
            return false;
        }
        return bounds.minU <= UV_TOLERANCE
            && bounds.maxU >= (1.0 - UV_TOLERANCE)
            && Math.abs(bounds.minV - 0.25) <= UV_TOLERANCE
            && Math.abs(bounds.maxV - 0.75) <= UV_TOLERANCE;
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

    private static Vector3D average(
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
        return new Vector3D(sx * inv, sy * inv, sz * inv);
    }

    private static Integer legacyTextureIdFromContent(String contentId) {
        if (contentId == null || contentId.isBlank()) {
            return null;
        }
        int separator = contentId.lastIndexOf('_');
        String suffix = separator >= 0 ? contentId.substring(separator + 1) : contentId;
        try {
            return Integer.parseInt(suffix);
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    private record UvBounds(double minU, double maxU, double minV, double maxV) {
        boolean coversWholeTexture() {
            return minU <= UV_TOLERANCE
                && maxU >= (1.0 - UV_TOLERANCE)
                && minV <= UV_TOLERANCE
                && maxV >= (1.0 - UV_TOLERANCE);
        }
    }
}
