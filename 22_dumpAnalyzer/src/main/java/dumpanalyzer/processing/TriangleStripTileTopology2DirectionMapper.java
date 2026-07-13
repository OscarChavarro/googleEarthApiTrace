package dumpanalyzer.processing;

import java.util.List;

import dumpanalyzer.model.TileInstance;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;

public final class TriangleStripTileTopology2DirectionMapper {
    private final TriangleStripTileClassifier classifier = new TriangleStripTileClassifier();

    public List<Vector3Dd> directionBorderPoints(TileInstance tile, Direction direction) {
        if (tile == null) {
            return List.of();
        }
        return directionBorderPoints(tile.getTriangleStrip(), direction);
    }

    public List<Vector3Dd> directionBorderPoints(TileInstance.TriangleStripGeometry geometry, Direction direction) {
        if (geometry == null || direction == null) {
            return List.of();
        }

        double epsilon = TriangleMeshVertexComparator.vertexEpsilon(geometry);
        List<TileInstance.TriangleStripVertex> deDuplicated = classifier.deduplicateVertices(geometry.vertices(), epsilon);
        TriangleStripTileTopology topology = classifier.classify(geometry);

        return switch (topology) {
            case DEDUPLICATED_9_VERTICES_QUAD -> mapQuad(direction, deDuplicated);
            case DEDUPLICATED_7_VERTICES_NORTH_POLE_TRIANGLE -> mapNorthPoleTriangle(direction, deDuplicated);
            case UNKNOWN -> List.of();
        };
    }

    private static List<Vector3Dd> mapQuad(Direction direction, List<TileInstance.TriangleStripVertex> deDuplicated) {
        return switch (direction) {
            case WEST -> pick(deDuplicated, 7, 8, 1);
            case EAST -> pick(deDuplicated, 5, 4, 3);
            case NORTH -> pick(deDuplicated, 7, 6, 5);
            case SOUTH -> pick(deDuplicated, 1, 2, 3);
        };
    }

    private static List<Vector3Dd> mapNorthPoleTriangle(Direction direction, List<TileInstance.TriangleStripVertex> deDuplicated) {
        return switch (direction) {
            case NORTH -> List.of();
            //case WEST -> pick(deDuplicated, 5, 6, 1);
            //case EAST -> pick(deDuplicated, 5, 4, 3);
            case WEST, EAST -> List.of();
            case SOUTH -> pick(deDuplicated, 1, 2, 3);
        };
    }

    private static List<Vector3Dd> pick(List<TileInstance.TriangleStripVertex> vertices, int a, int b, int c) {
        int max = Math.max(a, Math.max(b, c));
        if (vertices == null || vertices.size() <= max) {
            return List.of();
        }
        return List.of(toVector3D(vertices.get(a)), toVector3D(vertices.get(b)), toVector3D(vertices.get(c)));
    }

    private static Vector3Dd toVector3D(TileInstance.TriangleStripVertex v) {
        return new Vector3Dd(v.x(), v.y(), v.z());
    }
}
