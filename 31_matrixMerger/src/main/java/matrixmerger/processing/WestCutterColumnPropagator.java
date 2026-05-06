package matrixmerger.processing;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import matrixmerger.io.FrameMatrices;
import matrixmerger.io.TileMatrix;
import matrixmerger.io.WestCutterReader;

public final class WestCutterColumnPropagator {
    public Set<String> propagate(List<FrameMatrices> frames, Set<String> westCutterTileIds) {
        Set<String> normalizedIds = new LinkedHashSet<>();
        if (westCutterTileIds != null) {
            for (String id : westCutterTileIds) {
                String normalized = WestCutterReader.normalizeScopedTileId(id);
                if (normalized != null) {
                    normalizedIds.add(normalized);
                }
            }
        }
        if (frames == null || frames.isEmpty() || normalizedIds.isEmpty()) {
            return normalizedIds;
        }

        for (FrameMatrices frame : frames) {
            TileMatrix matrix = firstMatrix(frame);
            if (matrix == null || matrix.getTiles() == null || matrix.getTiles().isEmpty()) {
                continue;
            }
            Set<Integer> markedColumns = new LinkedHashSet<>();
            for (TileMatrix.TileCoord tile : matrix.getTiles()) {
                if (tile == null) {
                    continue;
                }
                if (normalizedIds.contains(tile.getId())) {
                    markedColumns.add(tile.getJ());
                }
            }
            if (markedColumns.isEmpty()) {
                continue;
            }
            for (TileMatrix.TileCoord tile : matrix.getTiles()) {
                if (tile != null && markedColumns.contains(tile.getJ())) {
                    normalizedIds.add(tile.getId());
                }
            }
        }
        return normalizedIds;
    }

    private static TileMatrix firstMatrix(FrameMatrices frame) {
        if (frame == null || frame.getMatrices() == null || frame.getMatrices().isEmpty()) {
            return null;
        }
        return frame.getMatrices().get(0);
    }
}
