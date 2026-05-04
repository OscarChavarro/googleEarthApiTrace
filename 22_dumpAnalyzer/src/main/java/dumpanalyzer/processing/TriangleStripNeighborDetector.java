package dumpanalyzer.processing;

import dumpanalyzer.model.Frame;
import dumpanalyzer.model.TileInstance;
import java.util.ArrayList;
import java.util.List;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4;

public final class TriangleStripNeighborDetector {
    private static final TriangleMeshVertexComparator COMPARATOR = new TriangleMeshVertexComparator();

    private TriangleStripNeighborDetector() {
    }

    public static void populateNeighbors(
        Frame frame,
        Matrix4x4 projection,
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
            candidates.add(new Candidate(i, tile.getContentId()));
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
                TriangleMeshVertexComparator.ComparisonResult bToA = COMPARATOR.compare(
                    tileB,
                    tileA,
                    projection,
                    viewportWidth,
                    viewportHeight,
                    frameModelView,
                    useGoogleCameraView
                );
                assignDirectionalNeighbor(tiles, a, b, aToB.directionFromAtoB(), aToB.distanceSquared());
                if (bToA.areNeighbors() && bToA.directionFromAtoB() != null) {
                    assignDirectionalNeighbor(tiles, b, a, bToA.directionFromAtoB(), bToA.distanceSquared());
                }
            }
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
            east = selectClosest(tiles, source.tileIndex(), east, target.contentId(), d2);
        }
        else if (direction == TriangleMeshVertexComparator.Direction.WEST) {
            west = selectClosest(tiles, source.tileIndex(), west, target.contentId(), d2);
        }
        else if (direction == TriangleMeshVertexComparator.Direction.NORTH) {
            north = selectClosest(tiles, source.tileIndex(), north, target.contentId(), d2);
        }
        else if (direction == TriangleMeshVertexComparator.Direction.SOUTH) {
            south = selectClosest(tiles, source.tileIndex(), south, target.contentId(), d2);
        }

        tile.setDetectedNeighbors(south, north, east, west);
    }

    private static int selectClosest(
        List<TileInstance> tiles,
        int sourceTileIndex,
        int currentNeighborContentId,
        int candidateNeighborContentId,
        double candidateDistance2
    ) {
        if (currentNeighborContentId == TileInstance.NO_NEIGHBOR) {
            return candidateNeighborContentId;
        }
        double currentDistance2 = distanceSquaredBetweenTilesByContentId(tiles, sourceTileIndex, currentNeighborContentId);
        if (candidateDistance2 < currentDistance2) {
            return candidateNeighborContentId;
        }
        return currentNeighborContentId;
    }

    private static double distanceSquaredBetweenTilesByContentId(
        List<TileInstance> tiles,
        int sourceTileIndex,
        int neighborContentId
    ) {
        TileInstance source = tiles.get(sourceTileIndex);
        TileInstance.TriangleStripGeometry sourceGeometry = source.getTriangleStrip();
        if (sourceGeometry == null) {
            return Double.POSITIVE_INFINITY;
        }
        double[] sourceCenter = center(sourceGeometry.vertices());
        for (TileInstance tile : tiles) {
            if (tile.getContentId() != neighborContentId) {
                continue;
            }
            TileInstance.TriangleStripGeometry g = tile.getTriangleStrip();
            if (g == null) {
                return Double.POSITIVE_INFINITY;
            }
            double[] c = center(g.vertices());
            double dx = c[0] - sourceCenter[0];
            double dy = c[1] - sourceCenter[1];
            double dz = c[2] - sourceCenter[2];
            return dx * dx + dy * dy + dz * dz;
        }
        return Double.POSITIVE_INFINITY;
    }

    private static double[] center(List<TileInstance.TriangleStripVertex> vertices) {
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

    private record Candidate(
        int tileIndex,
        int contentId
    ) {}
}
