package frametexturenormalizer.processing.matrix;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import frametexturenormalizer.model.TileInstance;

final class HorizontalSegmentBuilder {
    List<RowSegment> build(Map<Integer, TileInstance> byId, MatrixConflictTracker conflictTracker) {
        List<RowSegment> chunks = new ArrayList<>();
        if (byId == null || byId.isEmpty()) {
            return chunks;
        }

        for (Integer seedId : byId.keySet()) {
            if (!conflictTracker.isUnassigned(seedId)) {
                continue;
            }

            RowSegment chunk = new RowSegment();
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            TileInstance seed = byId.get(seedId);
            MatrixCell seedCell = new MatrixCell(0, 0, seed);
            chunk.put(seedCell);
            MatrixDebug.debug(null, "New row segment seed tile=%d", seedId);
            conflictTracker.setCoordinate(seedId, new MatrixTileCoordinate(0, 0));
            queue.add(seedId);

            while (!queue.isEmpty()) {
                int currentId = queue.removeFirst();
                MatrixCell currentCell = chunk.getByTileId(currentId);
                TileInstance currentTile = byId.get(currentId);
                if (currentCell == null || currentTile == null) {
                    continue;
                }

                if (!assignHorizontal(chunk, byId, currentCell, currentTile.getEastNeighbor(), +1, queue, conflictTracker)) {
                    return null;
                }
                if (!assignHorizontal(chunk, byId, currentCell, currentTile.getWestNeighbor(), -1, queue, conflictTracker)) {
                    return null;
                }
            }

            chunk.normalizeRowStartToZero();
            if (!VerticalSegmentMerger.validateRowContiguity(chunk, byId, conflictTracker)) {
                return null;
            }
            MatrixDebug.debug(null, "Row segment finalized size=%d width=%d minJ->0", chunk.size(), chunk.width());
            for (MatrixCell cell : chunk.cells()) {
                conflictTracker.setCoordinate(cell.tileId(), new MatrixTileCoordinate(cell.i(), cell.j()));
            }
            chunks.add(chunk);
        }

        return chunks;
    }

    private boolean assignHorizontal(
        RowSegment chunk,
        Map<Integer, TileInstance> byId,
        MatrixCell currentCell,
        Integer neighborId,
        int deltaJ,
        ArrayDeque<Integer> queue,
        MatrixConflictTracker conflictTracker
    ) {
        if (neighborId == null) {
            return true;
        }

        TileInstance neighborTile = byId.get(neighborId);
        if (neighborTile == null) {
            return true;
        }

        int expectedI = currentCell.i();
        int expectedJ = currentCell.j() + deltaJ;

        MatrixCell assigned = chunk.getByTileId(neighborId);
        if (assigned == null) {
            MatrixCell occupant = chunk.getByCoord(expectedI, expectedJ);
            if (occupant != null && occupant.tileId() != neighborId) {
                conflictTracker.registerConflict(neighborId, occupant.tileId());
                return false;
            }
            MatrixCell cell = new MatrixCell(expectedI, expectedJ, neighborTile);
            chunk.put(cell);
            MatrixDebug.debug(
                null,
                "Assign H: tile=%d neighbor=%d -> (%d,%d)",
                currentCell.tileId(),
                neighborId,
                expectedI,
                expectedJ
            );
            conflictTracker.setCoordinate(neighborId, new MatrixTileCoordinate(expectedI, expectedJ));
            queue.add(neighborId);
            return true;
        }

        if (assigned.i() != expectedI || assigned.j() != expectedJ) {
            conflictTracker.registerConflict(currentCell.tileId(), neighborId);
            return false;
        }

        return true;
    }
}
