package dumpanalyzer.processing;

import dumpanalyzer.model.TileInstance;
import java.util.List;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;

public final class TriangleMeshVertexComparator {
    public static final double VERTEX_EPSILON = 1e-4;
    private static final double MIN_VERTEX_EPSILON = 1e-12;
    private static final double RELATIVE_VERTEX_EPSILON_FACTOR = 1e-3;
    private static final TriangleStripTileTopology2DirectionMapper TOPOLOGY_MAPPER =
        new TriangleStripTileTopology2DirectionMapper();

    public enum Direction {
        NORTH,
        SOUTH,
        EAST,
        WEST
    }

    public record ComparisonResult(boolean areNeighbors, Direction directionFromAtoB, double distanceSquared) {}

    public ComparisonResult compare(
        TileInstance a,
        TileInstance b,
        Matrix4x4d projection,
        int viewportWidth,
        int viewportHeight,
        double[] frameModelView,
        boolean useGoogleCameraView
    ) {
        if (a == null || b == null) {
            return new ComparisonResult(false, null, Double.POSITIVE_INFINITY);
        }

        TileInstance.TriangleStripGeometry ga = a.getTriangleStrip();
        TileInstance.TriangleStripGeometry gb = b.getTriangleStrip();
        if (ga == null || gb == null || ga.vertices().isEmpty() || gb.vertices().isEmpty()) {
            return new ComparisonResult(false, null, Double.POSITIVE_INFINITY);
        }

        Direction direction = detectDirectionFromOrderedBorders(a, b);
        if (direction == null) {
            return new ComparisonResult(false, null, Double.POSITIVE_INFINITY);
        }

        double distanceSquared = distanceSquaredBetweenCenters(ga.vertices(), gb.vertices());
        return new ComparisonResult(true, direction, distanceSquared);
    }

    private static Direction detectDirectionFromOrderedBorders(TileInstance a, TileInstance b) {
        if (bordersMatch(a, Direction.EAST, b, Direction.WEST)) {
            return Direction.EAST;
        }
        if (bordersMatch(a, Direction.WEST, b, Direction.EAST)) {
            return Direction.WEST;
        }
        if (bordersMatch(a, Direction.NORTH, b, Direction.SOUTH)) {
            return Direction.NORTH;
        }
        if (bordersMatch(a, Direction.SOUTH, b, Direction.NORTH)) {
            return Direction.SOUTH;
        }
        return null;
    }

    private static boolean bordersMatch(
        TileInstance a,
        Direction dirA,
        TileInstance b,
        Direction dirB
    ) {
        List<Vector3Dd> borderA = TOPOLOGY_MAPPER.directionBorderPoints(a, toProcessingDirection(dirA));
        List<Vector3Dd> borderB = TOPOLOGY_MAPPER.directionBorderPoints(b, toProcessingDirection(dirB));
        if (borderA.isEmpty() || borderB.isEmpty() || borderA.size() != borderB.size()) {
            return false;
        }
        double epsilon = vertexEpsilon(borderA, borderB);
        for (int i = 0; i < borderA.size(); i++) {
            Vector3Dd va = borderA.get(i);
            Vector3Dd vb = borderB.get(i);
            if (Math.abs(va.x() - vb.x()) > epsilon
                || Math.abs(va.y() - vb.y()) > epsilon
                || Math.abs(va.z() - vb.z()) > epsilon) {
                return false;
            }
        }
        return true;
    }

    public static double vertexEpsilon(TileInstance tile) {
        if (tile == null) {
            return MIN_VERTEX_EPSILON;
        }
        return vertexEpsilon(tile.getTriangleStrip());
    }

    public static double vertexEpsilon(TileInstance.TriangleStripGeometry geometry) {
        if (geometry == null) {
            return MIN_VERTEX_EPSILON;
        }
        return vertexEpsilonForVertices(geometry.vertices());
    }

    public static double vertexEpsilon(List<TileInstance.TriangleStripVertex> vertices) {
        return vertexEpsilonForVertices(vertices);
    }

    public static double vertexEpsilon(Vector3Dd[] vertices) {
        if (vertices == null || vertices.length == 0) {
            return MIN_VERTEX_EPSILON;
        }
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        boolean found = false;
        for (Vector3Dd vertex : vertices) {
            if (vertex == null) {
                continue;
            }
            found = true;
            minX = Math.min(minX, vertex.x());
            maxX = Math.max(maxX, vertex.x());
            minY = Math.min(minY, vertex.y());
            maxY = Math.max(maxY, vertex.y());
            minZ = Math.min(minZ, vertex.z());
            maxZ = Math.max(maxZ, vertex.z());
        }
        if (!found) {
            return MIN_VERTEX_EPSILON;
        }
        return scaleToEpsilon(maxX - minX, maxY - minY, maxZ - minZ);
    }

    public static double vertexEpsilon(List<Vector3Dd> a, List<Vector3Dd> b) {
        return Math.max(vertexEpsilonFromVectors(a), vertexEpsilonFromVectors(b));
    }

    private static double vertexEpsilonForVertices(List<TileInstance.TriangleStripVertex> vertices) {
        if (vertices == null || vertices.isEmpty()) {
            return MIN_VERTEX_EPSILON;
        }
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (TileInstance.TriangleStripVertex vertex : vertices) {
            if (vertex == null) {
                continue;
            }
            minX = Math.min(minX, vertex.x());
            maxX = Math.max(maxX, vertex.x());
            minY = Math.min(minY, vertex.y());
            maxY = Math.max(maxY, vertex.y());
            minZ = Math.min(minZ, vertex.z());
            maxZ = Math.max(maxZ, vertex.z());
        }
        return scaleToEpsilon(maxX - minX, maxY - minY, maxZ - minZ);
    }

    private static double vertexEpsilonFromVectors(List<Vector3Dd> vertices) {
        if (vertices == null || vertices.isEmpty()) {
            return MIN_VERTEX_EPSILON;
        }
        return vertexEpsilon(vertices.toArray(Vector3Dd[]::new));
    }

    private static double scaleToEpsilon(double dx, double dy, double dz) {
        double scale = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        if (!Double.isFinite(scale) || scale <= 0.0) {
            return MIN_VERTEX_EPSILON;
        }
        double relative = scale * RELATIVE_VERTEX_EPSILON_FACTOR;
        return Math.max(MIN_VERTEX_EPSILON, Math.min(VERTEX_EPSILON, relative));
    }

    private static double distanceSquaredBetweenCenters(
        List<TileInstance.TriangleStripVertex> a,
        List<TileInstance.TriangleStripVertex> b
    ) {
        double[] ca = center(a);
        double[] cb = center(b);
        double dx = cb[0] - ca[0];
        double dy = cb[1] - ca[1];
        double dz = cb[2] - ca[2];
        return dx * dx + dy * dy + dz * dz;
    }

    private static double[] center(List<TileInstance.TriangleStripVertex> vertices) {
        if (vertices == null || vertices.isEmpty()) {
            return new double[] {0.0, 0.0, 0.0};
        }
        double sx = 0.0;
        double sy = 0.0;
        double sz = 0.0;
        for (TileInstance.TriangleStripVertex v : vertices) {
            sx += v.x();
            sy += v.y();
            sz += v.z();
        }
        double inv = 1.0 / vertices.size();
        return new double[] {sx * inv, sy * inv, sz * inv};
    }

    private static dumpanalyzer.processing.Direction toProcessingDirection(Direction d) {
        return switch (d) {
            case NORTH -> dumpanalyzer.processing.Direction.NORTH;
            case SOUTH -> dumpanalyzer.processing.Direction.SOUTH;
            case EAST -> dumpanalyzer.processing.Direction.EAST;
            case WEST -> dumpanalyzer.processing.Direction.WEST;
        };
    }
}
