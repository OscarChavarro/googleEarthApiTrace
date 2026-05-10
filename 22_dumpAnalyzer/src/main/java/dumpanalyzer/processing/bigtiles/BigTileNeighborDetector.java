package dumpanalyzer.processing.bigtiles;

import dumpanalyzer.model.BigTile;
import dumpanalyzer.model.Frame;
import dumpanalyzer.model.TileInstance;
import dumpanalyzer.processing.TriangleMeshVertexComparator;
import java.util.ArrayList;
import java.util.List;
import vsdk.toolkit.common.linealAlgebra.Vector3D;

public final class BigTileNeighborDetector {
    private static final double BORDER_CENTER_EPSILON = 1e-4;

    private BigTileNeighborDetector() {
    }

    public static void populateNeighbors(Frame frame) {
        if (frame == null) {
            return;
        }
        List<Candidate> candidates = new ArrayList<>();
        List<TileInstance> tiles = frame.getTiles();
        for (int i = 0; i < tiles.size(); i++) {
            TileInstance tile = tiles.get(i);
            BigTile bigTile = tile == null ? null : tile.getBigTile();
            if (bigTile == null) {
                continue;
            }
            bigTile.setDetectedNeighborContentIds(null, null, null, null);
            candidates.add(new Candidate(i, tile, bigTile));
        }

        for (Candidate source : candidates) {
            Candidate north = null;
            Candidate south = null;
            Candidate east = null;
            Candidate west = null;
            double northD2 = Double.POSITIVE_INFINITY;
            double southD2 = Double.POSITIVE_INFINITY;
            double eastD2 = Double.POSITIVE_INFINITY;
            double westD2 = Double.POSITIVE_INFINITY;
            for (Candidate target : candidates) {
                if (source == target) {
                    continue;
                }
                TriangleMeshVertexComparator.Direction direction = detectDirection(source.bigTile(), target.bigTile());
                if (direction == null) {
                    continue;
                }
                double d2 = distanceSquared(source.bigTile().getCenter(), target.bigTile().getCenter());
                if (direction == TriangleMeshVertexComparator.Direction.NORTH && d2 < northD2) {
                    north = target;
                    northD2 = d2;
                }
                else if (direction == TriangleMeshVertexComparator.Direction.SOUTH && d2 < southD2) {
                    south = target;
                    southD2 = d2;
                }
                else if (direction == TriangleMeshVertexComparator.Direction.EAST && d2 < eastD2) {
                    east = target;
                    eastD2 = d2;
                }
                else if (direction == TriangleMeshVertexComparator.Direction.WEST && d2 < westD2) {
                    west = target;
                    westD2 = d2;
                }
            }
            source.bigTile().setDetectedNeighborContentIds(
                contentIdOf(south),
                contentIdOf(north),
                contentIdOf(east),
                contentIdOf(west)
            );
        }
    }

    private static TriangleMeshVertexComparator.Direction detectDirection(BigTile a, BigTile b) {
        if (matches(a.getEastBorderCenter(), b.getWestBorderCenter())) {
            return TriangleMeshVertexComparator.Direction.EAST;
        }
        if (matches(a.getWestBorderCenter(), b.getEastBorderCenter())) {
            return TriangleMeshVertexComparator.Direction.WEST;
        }
        if (matches(a.getNorthBorderCenter(), b.getSouthBorderCenter())) {
            return TriangleMeshVertexComparator.Direction.NORTH;
        }
        if (matches(a.getSouthBorderCenter(), b.getNorthBorderCenter())) {
            return TriangleMeshVertexComparator.Direction.SOUTH;
        }
        return null;
    }

    private static boolean matches(Vector3D a, Vector3D b) {
        return distanceSquared(a, b) <= (BORDER_CENTER_EPSILON * BORDER_CENTER_EPSILON);
    }

    private static double distanceSquared(Vector3D a, Vector3D b) {
        if (a == null || b == null) {
            return Double.POSITIVE_INFINITY;
        }
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static String contentIdOf(Candidate candidate) {
        return candidate == null || candidate.tile() == null ? null : candidate.tile().getContentId();
    }

    private record Candidate(int tileIndex, TileInstance tile, BigTile bigTile) {}
}
