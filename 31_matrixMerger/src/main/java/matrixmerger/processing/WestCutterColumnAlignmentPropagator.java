package matrixmerger.processing;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import matrixmerger.model.contract.FrameMatrixSet;
import matrixmerger.model.contract.FrameTileMatrix;
import matrixmerger.io.WestCuttersJsonReader;

public final class WestCutterColumnAlignmentPropagator {
    public Set<String> propagate(List<FrameMatrixSet> frames, Set<String> westCutterTileIds) {
        Set<String> normalizedIds = new LinkedHashSet<>();
        if (westCutterTileIds != null) {
            for (String id : westCutterTileIds) {
                String normalized = WestCuttersJsonReader.normalizeScopedTileId(id);
                if (normalized != null) {
                    normalizedIds.add(normalized);
                }
            }
        }
        if (frames == null || frames.isEmpty() || normalizedIds.isEmpty()) {
            return normalizedIds;
        }

        for (FrameMatrixSet frame : frames) {
            if (frame == null || frame.getMatrices() == null) {
                continue;
            }
            for (FrameTileMatrix matrix : frame.getMatrices()) {
                if (matrix == null || matrix.getTiles() == null || matrix.getTiles().isEmpty()) {
                    continue;
                }
                Set<Integer> markedColumns = new LinkedHashSet<>();
                for (FrameTileMatrix.TileCoord tile : matrix.getTiles()) {
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
                for (FrameTileMatrix.TileCoord tile : matrix.getTiles()) {
                    if (tile != null && markedColumns.contains(tile.getJ())) {
                        normalizedIds.add(tile.getId());
                    }
                }
            }
        }
        return normalizedIds;
    }
}
