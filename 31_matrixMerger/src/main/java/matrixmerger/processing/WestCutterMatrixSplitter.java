package matrixmerger.processing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import matrixmerger.model.contract.FrameMatrixSet;
import matrixmerger.model.contract.FrameTileMatrix;
import matrixmerger.io.WestCuttersJsonReader;

public final class WestCutterMatrixSplitter {
    public FrameSplitResult splitFrame(FrameMatrixSet frame, Set<String> westCutterTileIds) {
        if (frame == null) {
            return new FrameSplitResult(null, null, false);
        }
        SplitResult split = splitFrameInternal(copyFrame(frame), normalizeIds(westCutterTileIds));
        return new FrameSplitResult(split.mainFrame(), split.transientFrame(), split.transientFrame() != null);
    }

    public List<FrameMatrixSet> split(
        List<FrameMatrixSet> frames,
        Set<String> westCutterTileIds,
        FrameValidationSummary validation
    ) {
        if (frames == null || frames.isEmpty()) {
            return List.of();
        }

        Set<String> normalizedWestIds = normalizeIds(westCutterTileIds);
        if (normalizedWestIds.isEmpty()) {
            return copyFrames(frames);
        }

        List<FrameMatrixSet> out = new ArrayList<>();
        List<FrameMatrixSet> transientFrames = new ArrayList<>();
        Set<Integer> invalidFrameIds = validation == null
            ? Set.of()
            : new LinkedHashSet<>(validation.getInvalidReasonByFrameId().keySet());

        for (int index = 0; index < frames.size(); index++) {
            FrameMatrixSet frame = copyFrame(frames.get(index));
            if (frame == null) {
                continue;
            }
            if (invalidFrameIds.contains(frame.getFrameId())) {
                for (int remaining = index; remaining < frames.size(); remaining++) {
                    FrameMatrixSet tail = copyFrame(frames.get(remaining));
                    if (tail != null) {
                        out.add(tail);
                    }
                }
                out.addAll(transientFrames);
                return out;
            }

            SplitResult split = splitFrameInternal(frame, normalizedWestIds);
            out.add(split.mainFrame());
            if (split.transientFrame() != null) {
                transientFrames.add(split.transientFrame());
            }
        }

        out.addAll(transientFrames);
        return out;
    }

    private SplitResult splitFrameInternal(FrameMatrixSet frame, Set<String> westCutterTileIds) {
        FrameTileMatrix matrix = firstMatrix(frame);
        if (matrix == null || matrix.getTiles() == null || matrix.getTiles().isEmpty()) {
            return new SplitResult(frame, null);
        }

        Integer splitColumn = null;
        for (FrameTileMatrix.TileCoord tile : matrix.getTiles()) {
            if (tile == null || !westCutterTileIds.contains(tile.getId())) {
                continue;
            }
            splitColumn = tile.getJ();
            break;
        }
        if (splitColumn == null || splitColumn.intValue() <= 0) {
            return new SplitResult(frame, null);
        }

        List<FrameTileMatrix.TileCoord> leftTiles = new ArrayList<>();
        List<FrameTileMatrix.TileCoord> rightTiles = new ArrayList<>();
        int rightMaxJ = Integer.MIN_VALUE;
        int maxI = Integer.MIN_VALUE;
        for (FrameTileMatrix.TileCoord tile : matrix.getTiles()) {
            if (tile == null) {
                continue;
            }
            maxI = Math.max(maxI, tile.getI());
            if (tile.getJ() < splitColumn.intValue()) {
                leftTiles.add(copyTile(tile, tile.getI(), tile.getJ()));
            }
            else {
                int shiftedJ = tile.getJ() - splitColumn.intValue();
                rightTiles.add(copyTile(tile, tile.getI(), shiftedJ));
                rightMaxJ = Math.max(rightMaxJ, shiftedJ);
            }
        }

        if (leftTiles.isEmpty() || rightTiles.isEmpty()) {
            return new SplitResult(frame, null);
        }

        FrameTileMatrix rightMatrix = new FrameTileMatrix();
        rightMatrix.setFrameId(frame.getFrameId());
        rightMatrix.setRows(maxI + 1);
        rightMatrix.setCols(rightMaxJ + 1);
        rightMatrix.setTiles(rightTiles);

        FrameMatrixSet mainFrame = new FrameMatrixSet();
        mainFrame.setFrameId(frame.getFrameId());
        mainFrame.setMatrices(List.of(rightMatrix));

        int leftMaxJ = Integer.MIN_VALUE;
        for (FrameTileMatrix.TileCoord tile : leftTiles) {
            leftMaxJ = Math.max(leftMaxJ, tile.getJ());
        }
        FrameTileMatrix leftMatrix = new FrameTileMatrix();
        leftMatrix.setFrameId(-1);
        leftMatrix.setRows(maxI + 1);
        leftMatrix.setCols(leftMaxJ + 1);
        leftMatrix.setTiles(leftTiles);

        FrameMatrixSet transientFrame = new FrameMatrixSet();
        transientFrame.setFrameId(-1);
        transientFrame.setMatrices(List.of(leftMatrix));
        return new SplitResult(mainFrame, transientFrame);
    }

    private static FrameTileMatrix.TileCoord copyTile(FrameTileMatrix.TileCoord source, int i, int j) {
        FrameTileMatrix.TileCoord copy = new FrameTileMatrix.TileCoord();
        copy.setId(source.getId());
        copy.setI(i);
        copy.setJ(j);
        copy.setTextureFile(source.getTextureFile());
        copy.setUncles(source.getUncles());
        return copy;
    }

    private static Set<String> normalizeIds(Set<String> westCutterTileIds) {
        Set<String> out = new LinkedHashSet<>();
        if (westCutterTileIds == null) {
            return out;
        }
        for (String id : westCutterTileIds) {
            String normalized = WestCuttersJsonReader.normalizeScopedTileId(id);
            if (normalized != null) {
                out.add(normalized);
            }
        }
        return out;
    }

    private static List<FrameMatrixSet> copyFrames(List<FrameMatrixSet> frames) {
        List<FrameMatrixSet> out = new ArrayList<>();
        for (FrameMatrixSet frame : frames) {
            FrameMatrixSet copy = copyFrame(frame);
            if (copy != null) {
                out.add(copy);
            }
        }
        return out;
    }

    private static FrameMatrixSet copyFrame(FrameMatrixSet frame) {
        FrameTileMatrix matrix = firstMatrix(frame);
        if (frame == null || matrix == null) {
            return null;
        }
        FrameTileMatrix matrixCopy = new FrameTileMatrix();
        matrixCopy.setFrameId(matrix.getFrameId());
        matrixCopy.setRows(matrix.getRows());
        matrixCopy.setCols(matrix.getCols());
        List<FrameTileMatrix.TileCoord> tiles = new ArrayList<>();
        for (FrameTileMatrix.TileCoord tile : matrix.getTiles()) {
            if (tile != null) {
                tiles.add(copyTile(tile, tile.getI(), tile.getJ()));
            }
        }
        matrixCopy.setTiles(tiles);

        FrameMatrixSet out = new FrameMatrixSet();
        out.setFrameId(frame.getFrameId());
        out.setMatrices(List.of(matrixCopy));
        return out;
    }

    private static FrameTileMatrix firstMatrix(FrameMatrixSet frame) {
        if (frame == null || frame.getMatrices() == null || frame.getMatrices().isEmpty()) {
            return null;
        }
        return frame.getMatrices().get(0);
    }

    private record SplitResult(FrameMatrixSet mainFrame, FrameMatrixSet transientFrame) {
    }

    public record FrameSplitResult(FrameMatrixSet mainFrame, FrameMatrixSet transientFrame, boolean changed) {
    }
}
