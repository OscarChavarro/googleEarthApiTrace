package frametexturenormalizer.processing.matrix;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.TileInstance;
import frametexturenormalizer.model.TileMatrix;

public final class TileSetToMatrixConverter {
    private final MatrixConflictTracker conflictTracker = new MatrixConflictTracker();
    private final HorizontalSegmentBuilder horizontalSegmentBuilder = new HorizontalSegmentBuilder();
    private final VerticalSegmentMerger verticalSegmentMerger = new VerticalSegmentMerger();
    private final MatrixAssembler matrixAssembler = new MatrixAssembler();

    public TileMatrix convert(FrameData frameData) {
        conflictTracker.clear();

        if (frameData == null || frameData.getTiles() == null || frameData.getTiles().isEmpty()) {
            return null;
        }
        MatrixDebug.setCurrentFrame(frameData.getId());
        MatrixDebug.debug(
            frameData.getId(),
            "=== Frame %d conversion start (%d tiles) ===",
            frameData.getId(),
            frameData.getTiles().size()
        );

        Map<Integer, TileInstance> byId = new HashMap<>();
        for (TileInstance tile : frameData.getTiles()) {
            if (tile != null) {
                byId.put(tile.getTileId(), tile);
            }
        }
        if (byId.isEmpty()) {
            MatrixDebug.clearCurrentFrame();
            return null;
        }

        conflictTracker.initializeWorkingCoordinates(byId.keySet());

        List<RowSegment> chunks = horizontalSegmentBuilder.build(byId, conflictTracker);
        if (chunks == null) {
            MatrixDebug.debug(frameData.getId(), "Frame %d FAILED during horizontal chunk build", frameData.getId());
            MatrixDebug.clearCurrentFrame();
            return null;
        }
        MatrixDebug.debug(frameData.getId(), "Frame %d horizontal row segments built: %d", frameData.getId(), chunks.size());

        if (!verticalSegmentMerger.merge(chunks, byId, conflictTracker)) {
            MatrixDebug.debug(frameData.getId(), "Frame %d FAILED during vertical row-segment merge", frameData.getId());
            MatrixDebug.clearCurrentFrame();
            return null;
        }
        MatrixDebug.debug(frameData.getId(), "Frame %d merged row segments remaining: %d", frameData.getId(), chunks.size());

        TileMatrix matrix = matrixAssembler.build(frameData.getId(), chunks, conflictTracker);
        MatrixDebug.debug(frameData.getId(), "=== Frame %d conversion end ===", frameData.getId());
        MatrixDebug.clearCurrentFrame();
        return matrix;
    }

    public Set<Integer> getLastConflictingTileIds() {
        return conflictTracker.getConflictingTileIds();
    }

    public Map<Integer, MatrixTileCoordinate> getLastCoordinatesByTileId() {
        return conflictTracker.getCoordinatesByTileId();
    }
}
