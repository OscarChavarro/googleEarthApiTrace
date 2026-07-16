package dumpanalyzer.processing;

import dumpanalyzer.model.Frame;
import dumpanalyzer.model.TileInstance;
import java.util.ArrayList;
import java.util.List;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;

public final class TriangleStripNeighborDetector {
    private static final TriangleMeshVertexComparator COMPARATOR = new TriangleMeshVertexComparator();

    private TriangleStripNeighborDetector() {
    }

    public static void populateNeighbors(
        Frame frame,
        Matrix4x4d projection,
        int viewportWidth,
        int viewportHeight,
        double[] frameModelView,
        boolean useGoogleCameraView
    ) {
        if (frame == null) {
            return;
        }
        List<TileInstance> tiles = frame.getTiles();
        if (tiles.isEmpty()) {
            return;
        }

        for (TileInstance tile : tiles) {
            tile.setDetectedNeighbors(
                TileInstance.NO_NEIGHBOR,
                TileInstance.NO_NEIGHBOR,
                TileInstance.NO_NEIGHBOR,
                TileInstance.NO_NEIGHBOR
            );
        }

        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < tiles.size(); i++) {
            TileInstance tile = tiles.get(i);
            TileInstance.TriangleStripGeometry geometry = tile.getTriangleStrip();
            if (geometry == null || geometry.vertices() == null || geometry.vertices().isEmpty()) {
                continue;
            }
            if (isDegenerateGeometry(geometry)) {
                continue;
            }
            if (tile.getTriangleStripTopology() == TriangleStripTileTopology.UNKNOWN) {
                continue;
            }
            candidates.add(new Candidate(i));
        }

        for (int i = 0; i < candidates.size(); i++) {
            for (int j = i + 1; j < candidates.size(); j++) {
                Candidate a = candidates.get(i);
                Candidate b = candidates.get(j);
                TileInstance tileA = tiles.get(a.tileIndex());
                TileInstance tileB = tiles.get(b.tileIndex());
                TriangleMeshVertexComparator.ComparisonResult aToB = COMPARATOR.compare(
                    tileA,
                    tileB,
                    projection,
                    viewportWidth,
                    viewportHeight,
                    frameModelView,
                    useGoogleCameraView
                );
                if (!aToB.areNeighbors() || aToB.directionFromAtoB() == null) {
                    continue;
                }
                assignDirectionalNeighbor(tiles, a, b, aToB.directionFromAtoB(), aToB.distanceSquared());
                assignDirectionalNeighbor(tiles, b, a, opposite(aToB.directionFromAtoB()), aToB.distanceSquared());
            }
        }

        for (TileInstance tile : tiles) {
            if (tile == null) {
                continue;
            }
            tile.setDetectedNeighborContentIds(
                contentIdAt(tiles, tile.getDetectedSouthNeighborIndex()),
                contentIdAt(tiles, tile.getDetectedNorthNeighborIndex()),
                contentIdAt(tiles, tile.getDetectedEastNeighborIndex()),
                contentIdAt(tiles, tile.getDetectedWestNeighborIndex())
            );
        }
    }

    private static void assignDirectionalNeighbor(
        List<TileInstance> tiles,
        Candidate source,
        Candidate target,
        TriangleMeshVertexComparator.Direction direction,
        double d2
    ) {
        TileInstance tile = tiles.get(source.tileIndex());
        int south = tile.getDetectedSouthNeighborIndex();
        int north = tile.getDetectedNorthNeighborIndex();
        int east = tile.getDetectedEastNeighborIndex();
        int west = tile.getDetectedWestNeighborIndex();

        if (direction == TriangleMeshVertexComparator.Direction.EAST) {
            east = selectClosest(tiles, source.tileIndex(), east, target.tileIndex(), d2);
        }
        else if (direction == TriangleMeshVertexComparator.Direction.WEST) {
            west = selectClosest(tiles, source.tileIndex(), west, target.tileIndex(), d2);
        }
        else if (direction == TriangleMeshVertexComparator.Direction.NORTH) {
            north = selectClosest(tiles, source.tileIndex(), north, target.tileIndex(), d2);
        }
        else if (direction == TriangleMeshVertexComparator.Direction.SOUTH) {
            south = selectClosest(tiles, source.tileIndex(), south, target.tileIndex(), d2);
        }

        tile.setDetectedNeighbors(south, north, east, west);
    }

    private static int selectClosest(
        List<TileInstance> tiles,
        int sourceTileIndex,
        int currentNeighborIndex,
        int candidateNeighborIndex,
        double candidateDistance2
    ) {
        if (currentNeighborIndex == TileInstance.NO_NEIGHBOR) {
            return candidateNeighborIndex;
        }
        double currentDistance2 = distanceSquaredBetweenTilesByIndex(tiles, sourceTileIndex, currentNeighborIndex);
        if (candidateDistance2 < currentDistance2) {
            return candidateNeighborIndex;
        }
        return currentNeighborIndex;
    }

    private static double distanceSquaredBetweenTilesByIndex(
        List<TileInstance> tiles,
        int sourceTileIndex,
        int neighborTileIndex
    ) {
        TileInstance source = tiles.get(sourceTileIndex);
        TileInstance.TriangleStripGeometry sourceGeometry = source.getTriangleStrip();
        if (sourceGeometry == null) {
            return Double.POSITIVE_INFINITY;
        }
        double[] sourceCenter = source.getTriangleStripCenter();
        if (neighborTileIndex < 0 || neighborTileIndex >= tiles.size()) {
            return Double.POSITIVE_INFINITY;
        }
        TileInstance tile = tiles.get(neighborTileIndex);
        TileInstance.TriangleStripGeometry g = tile.getTriangleStrip();
        if (g == null) {
            return Double.POSITIVE_INFINITY;
        }
        double[] c = tile.getTriangleStripCenter();
        double dx = c[0] - sourceCenter[0];
        double dy = c[1] - sourceCenter[1];
        double dz = c[2] - sourceCenter[2];
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * A tile whose strip collapses to a point or a line (zero extent along x
     * or y) can not be a grid cell: it is a mid-transition quad that was
     * fully clipped. Excluding it from neighbor candidacy keeps it from
     * stealing a cardinal-neighbor slot from the real abutting tile (the
     * closest candidate wins in {@link #selectClosest}, and a collapsed quad
     * sitting on a tile's edge is always closest), which would otherwise
     * leave a hole in the grid once downstream filtering drops the
     * degenerate tile. Real tiles keep their neighbor analysis intact.
     */
    private static boolean isDegenerateGeometry(TileInstance.TriangleStripGeometry geometry) {
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (TileInstance.TriangleStripVertex vertex : geometry.vertices()) {
            minX = Math.min(minX, vertex.x());
            maxX = Math.max(maxX, vertex.x());
            minY = Math.min(minY, vertex.y());
            maxY = Math.max(maxY, vertex.y());
        }
        double epsilon = 1.0e-9;
        return (maxX - minX) <= epsilon || (maxY - minY) <= epsilon;
    }

    private static String contentIdAt(List<TileInstance> tiles, int index) {
        if (index < 0 || index >= tiles.size()) {
            return null;
        }
        TileInstance tile = tiles.get(index);
        return tile == null ? null : tile.getContentId();
    }

    private static TriangleMeshVertexComparator.Direction opposite(TriangleMeshVertexComparator.Direction direction) {
        return switch (direction) {
            case EAST -> TriangleMeshVertexComparator.Direction.WEST;
            case WEST -> TriangleMeshVertexComparator.Direction.EAST;
            case NORTH -> TriangleMeshVertexComparator.Direction.SOUTH;
            case SOUTH -> TriangleMeshVertexComparator.Direction.NORTH;
        };
    }

    private record Candidate(int tileIndex) {}
}
