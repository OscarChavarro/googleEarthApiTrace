package frametexturenormalizer.processing.matrix;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import frametexturenormalizer.model.TileMatrix;
import frametexturenormalizer.model.TileMatrix.TileCoord;

final class MatrixAssembler {
    TileMatrix build(int frameId, List<RowSegment> chunks, MatrixConflictTracker conflictTracker) {
        if (chunks == null || chunks.isEmpty()) {
            return null;
        }
        List<TileMatrix> matrices = new ArrayList<>(chunks.size());
        for (int idx = 0; idx < chunks.size(); idx++) {
            RowSegment chunk = chunks.get(idx);
            if (chunk == null || chunk.cells().isEmpty()) {
                continue;
            }
            validateRowSegmentInvariants(chunk, "final-placement-before-shift", idx);
            TileMatrix matrix = buildMatrixFromChunk(frameId, chunk, conflictTracker);
            if (matrix != null && matrix.getRows() > 0 && matrix.getCols() > 0 && !matrix.getTiles().isEmpty()) {
                matrices.add(matrix);
            }
        }
        return collapseMatrices(frameId, matrices, conflictTracker);
    }

    private TileMatrix collapseMatrices(
        int frameId,
        List<TileMatrix> matrices,
        MatrixConflictTracker conflictTracker
    ) {
        if (matrices == null || matrices.isEmpty()) {
            return null;
        }
        if (matrices.size() == 1) {
            return matrices.get(0);
        }

        int minI = Integer.MAX_VALUE;
        int minJ = Integer.MAX_VALUE;
        int maxI = Integer.MIN_VALUE;
        int maxJ = Integer.MIN_VALUE;
        Map<Integer, TileCoord> byTileId = new HashMap<>();
        for (TileMatrix matrix : matrices) {
            if (matrix == null || matrix.getTiles() == null) {
                continue;
            }
            for (TileCoord tile : matrix.getTiles()) {
                if (tile == null) {
                    continue;
                }
                byTileId.putIfAbsent(tile.tileId(), tile);
                minI = Math.min(minI, tile.i());
                minJ = Math.min(minJ, tile.j());
                maxI = Math.max(maxI, tile.i());
                maxJ = Math.max(maxJ, tile.j());
            }
        }
        if (byTileId.isEmpty()) {
            return null;
        }

        List<TileCoord> collapsedTiles = new ArrayList<>(byTileId.size());
        for (TileCoord tile : byTileId.values()) {
            TileCoord normalized = new TileCoord(
                tile.tileId(),
                tile.i() - minI,
                tile.j() - minJ,
                tile.textureFile(),
                tile.uncles()
            );
            collapsedTiles.add(normalized);
            conflictTracker.setCoordinate(tile.tileId(), new MatrixTileCoordinate(normalized.i(), normalized.j()));
        }
        return new TileMatrix(frameId, maxI - minI + 1, maxJ - minJ + 1, collapsedTiles);
    }

    private TileMatrix buildMatrixFromChunk(int frameId, RowSegment chunk, MatrixConflictTracker conflictTracker) {
        int minI = Integer.MAX_VALUE;
        int minJ = Integer.MAX_VALUE;
        int maxI = Integer.MIN_VALUE;
        int maxJ = Integer.MIN_VALUE;

        for (MatrixCell cell : chunk.cells()) {
            if (cell == null) {
                continue;
            }
            minI = Math.min(minI, cell.i());
            minJ = Math.min(minJ, cell.j());
            maxI = Math.max(maxI, cell.i());
            maxJ = Math.max(maxJ, cell.j());
        }
        if (minI == Integer.MAX_VALUE || minJ == Integer.MAX_VALUE) {
            return null;
        }

        List<TileCoord> result = new ArrayList<>(chunk.size());
        for (MatrixCell cell : chunk.cells()) {
            if (cell == null || cell.tile() == null) {
                continue;
            }
            int normalizedI = cell.i() - minI;
            int normalizedJ = cell.j() - minJ;
            conflictTracker.setCoordinate(cell.tileId(), new MatrixTileCoordinate(normalizedI, normalizedJ));
            result.add(new TileCoord(
                cell.tileId(),
                normalizedI,
                normalizedJ,
                cell.tile().getTextureFile(),
                cell.tile().getUncles()
            ));
        }

        int rows = maxI - minI + 1;
        int cols = maxJ - minJ + 1;
        return new TileMatrix(frameId, rows, cols, result);
    }

    private void validateRowSegmentInvariants(RowSegment segment, String stage, int index) {
        Map<Integer, List<MatrixCell>> byRow = new HashMap<>();
        for (MatrixCell cell : segment.cells()) {
            byRow.computeIfAbsent(cell.i(), __ -> new ArrayList<>()).add(cell);
        }
        for (Map.Entry<Integer, List<MatrixCell>> rowEntry : byRow.entrySet()) {
            int row = rowEntry.getKey();
            List<MatrixCell> cells = rowEntry.getValue();
            cells.sort(Comparator.comparingInt(MatrixCell::j));
            int expected = 0;
            for (MatrixCell cell : cells) {
                if (cell.j() != expected) {
                    MatrixDebug.debug(
                        null,
                        "INVARIANT WARNING stage=%s seg=%d row=%d expectedJ=%d gotJ=%d tile=%d",
                        stage,
                        index,
                        row,
                        expected,
                        cell.j(),
                        cell.tileId()
                    );
                    break;
                }
                expected++;
            }
        }
    }
}
