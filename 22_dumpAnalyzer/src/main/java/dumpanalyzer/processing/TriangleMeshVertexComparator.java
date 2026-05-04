package dumpanalyzer.processing;

import dumpanalyzer.model.TileInstance;
import java.util.List;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4;
import vsdk.toolkit.common.linealAlgebra.Vector3D;

public final class TriangleMeshVertexComparator {
    public static final double VERTEX_EPSILON = 1e-4;
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
        Matrix4x4 projection,
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
        List<Vector3D> borderA = TOPOLOGY_MAPPER.directionBorderPoints(a, toProcessingDirection(dirA));
        List<Vector3D> borderB = TOPOLOGY_MAPPER.directionBorderPoints(b, toProcessingDirection(dirB));
        if (borderA.isEmpty() || borderB.isEmpty() || borderA.size() != borderB.size()) {
            return false;
        }
        for (int i = 0; i < borderA.size(); i++) {
            Vector3D va = borderA.get(i);
            Vector3D vb = borderB.get(i);
            if (Math.abs(va.x() - vb.x()) > VERTEX_EPSILON
                || Math.abs(va.y() - vb.y()) > VERTEX_EPSILON
                || Math.abs(va.z() - vb.z()) > VERTEX_EPSILON) {
                return false;
            }
        }
        return true;
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
