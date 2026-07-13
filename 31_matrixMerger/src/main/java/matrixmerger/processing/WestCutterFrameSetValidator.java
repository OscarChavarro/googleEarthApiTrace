package matrixmerger.processing;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import matrixmerger.model.contract.FrameMatrixSet;
import matrixmerger.model.contract.FrameTileMatrix;
import matrixmerger.io.WestCuttersJsonReader;

public final class WestCutterFrameSetValidator {
    public FrameValidationSummary validate(List<FrameMatrixSet> frames, Set<String> westCutterTileIds) {
        Map<Integer, String> invalidReasonByFrameId = new LinkedHashMap<>();
        if (frames == null || frames.isEmpty() || westCutterTileIds == null || westCutterTileIds.isEmpty()) {
            return new FrameValidationSummary(invalidReasonByFrameId);
        }

        Set<String> normalizedIds = new LinkedHashSet<>();
        for (String id : westCutterTileIds) {
            String normalized = WestCuttersJsonReader.normalizeScopedTileId(id);
            if (normalized != null) {
                normalizedIds.add(normalized);
            }
        }

        for (FrameMatrixSet frame : frames) {
            if (frame == null || frame.getMatrices() == null || frame.getMatrices().isEmpty()) {
                continue;
            }
            boolean invalid = false;
            for (FrameTileMatrix matrix : frame.getMatrices()) {
                if (matrix == null || matrix.getTiles() == null || matrix.getTiles().isEmpty()) {
                    continue;
                }
                Set<Integer> markedColumns = new LinkedHashSet<>();
                for (FrameTileMatrix.TileCoord tile : matrix.getTiles()) {
                    if (tile != null && normalizedIds.contains(tile.getId())) {
                        markedColumns.add(tile.getJ());
                    }
                }
                if (markedColumns.size() > 1) {
                    invalid = true;
                    break;
                }
            }
            if (invalid) {
                invalidReasonByFrameId.put(
                    frame.getFrameId(),
                    "INVALID FRAME: multiple west-cutter columns detected"
                );
            }
        }
        return new FrameValidationSummary(invalidReasonByFrameId);
    }
}
