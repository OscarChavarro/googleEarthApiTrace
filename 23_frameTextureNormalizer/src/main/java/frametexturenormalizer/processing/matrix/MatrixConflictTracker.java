package frametexturenormalizer.processing.matrix;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MatrixConflictTracker {
    private static final MatrixTileCoordinate UNASSIGNED = new MatrixTileCoordinate(-1, -1);

    private final Set<Integer> conflictingTileIds = new HashSet<>();
    private final Map<Integer, MatrixTileCoordinate> coordinatesByTileId = new HashMap<>();

    void clear() {
        conflictingTileIds.clear();
        coordinatesByTileId.clear();
    }

    void initializeWorkingCoordinates(Set<Integer> ids) {
        coordinatesByTileId.clear();
        if (ids == null) {
            return;
        }
        for (Integer id : ids) {
            coordinatesByTileId.put(id, UNASSIGNED);
        }
    }

    boolean isUnassigned(Integer id) {
        MatrixTileCoordinate coordinate = coordinatesByTileId.get(id);
        return coordinate == null || (coordinate.i() == -1 && coordinate.j() == -1);
    }

    void setCoordinate(Integer tileId, MatrixTileCoordinate coordinate) {
        if (tileId == null || coordinate == null) {
            return;
        }
        coordinatesByTileId.put(tileId, coordinate);
    }

    void refreshWorkingCoordinates(List<RowSegment> chunks, Set<Integer> allIds) {
        if (allIds != null) {
            for (Integer id : allIds) {
                coordinatesByTileId.put(id, UNASSIGNED);
            }
        }
        if (chunks == null) {
            return;
        }
        for (RowSegment chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            for (MatrixCell cell : chunk.cells()) {
                coordinatesByTileId.put(cell.tileId(), new MatrixTileCoordinate(cell.i(), cell.j()));
            }
        }
    }

    void registerConflict(Integer a, Integer b) {
        if (a != null) {
            conflictingTileIds.add(a);
        }
        if (b != null) {
            conflictingTileIds.add(b);
        }
        MatrixDebug.debug(null, "Conflict registered: a=%s b=%s", String.valueOf(a), String.valueOf(b));
    }

    Set<Integer> getConflictingTileIds() {
        return Set.copyOf(conflictingTileIds);
    }

    Map<Integer, MatrixTileCoordinate> getCoordinatesByTileId() {
        return Map.copyOf(coordinatesByTileId);
    }
}
