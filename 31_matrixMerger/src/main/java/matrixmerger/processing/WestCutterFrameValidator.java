package matrixmerger.processing;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import matrixmerger.io.FrameMatrices;
import matrixmerger.io.TileMatrix;
import matrixmerger.io.WestCutterReader;

public final class WestCutterFrameValidator {
    public WestCutterValidationResult validate(List<FrameMatrices> frames, Set<String> westCutterTileIds) {
        Map<Integer, String> invalidReasonByFrameId = new LinkedHashMap<>();
        if (frames == null || frames.isEmpty() || westCutterTileIds == null || westCutterTileIds.isEmpty()) {
            return new WestCutterValidationResult(invalidReasonByFrameId);
        }

        Set<String> normalizedIds = new LinkedHashSet<>();
        for (String id : westCutterTileIds) {
            String normalized = WestCutterReader.normalizeScopedTileId(id);
            if (normalized != null) {
                normalizedIds.add(normalized);
            }
        }

        for (FrameMatrices frame : frames) {
            TileMatrix matrix = firstMatrix(frame);
            if (frame == null || matrix == null || matrix.getTiles() == null || matrix.getTiles().isEmpty()) {
                continue;
            }
            Set<Integer> markedColumns = new LinkedHashSet<>();
            for (TileMatrix.TileCoord tile : matrix.getTiles()) {
                if (tile != null && normalizedIds.contains(tile.getId())) {
                    markedColumns.add(tile.getJ());
                }
            }
            if (markedColumns.size() > 1) {
                invalidReasonByFrameId.put(
                    frame.getFrameId(),
                    "INVALID FRAME: multiple west-cutter columns detected"
                );
            }
        }
        return new WestCutterValidationResult(invalidReasonByFrameId);
    }

    private static TileMatrix firstMatrix(FrameMatrices frame) {
        if (frame == null || frame.getMatrices() == null || frame.getMatrices().isEmpty()) {
            return null;
        }
        return frame.getMatrices().get(0);
    }
}
