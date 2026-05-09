package matrixmerger.processing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import matrixmerger.io.FrameMatrices;
import matrixmerger.io.TileMatrix;
import matrixmerger.io.WestCutterReader;

public final class MatrixByWestCutterSplitter {
    public FrameSplitResult splitFrame(FrameMatrices frame, Set<String> westCutterTileIds) {
        if (frame == null) {
            return new FrameSplitResult(null, null, false);
        }
        SplitResult split = splitFrameInternal(copyFrame(frame), normalizeIds(westCutterTileIds));
        return new FrameSplitResult(split.mainFrame(), split.transientFrame(), split.transientFrame() != null);
    }

    public List<FrameMatrices> split(
        List<FrameMatrices> frames,
        Set<String> westCutterTileIds,
        WestCutterValidationResult validation
    ) {
        if (frames == null || frames.isEmpty()) {
            return List.of();
        }

        Set<String> normalizedWestIds = normalizeIds(westCutterTileIds);
        if (normalizedWestIds.isEmpty()) {
            return copyFrames(frames);
        }

        List<FrameMatrices> out = new ArrayList<>();
        List<FrameMatrices> transientFrames = new ArrayList<>();
        Set<Integer> invalidFrameIds = validation == null
            ? Set.of()
            : new LinkedHashSet<>(validation.getInvalidReasonByFrameId().keySet());

        for (int index = 0; index < frames.size(); index++) {
            FrameMatrices frame = copyFrame(frames.get(index));
            if (frame == null) {
                continue;
            }
            if (invalidFrameIds.contains(frame.getFrameId())) {
                for (int remaining = index; remaining < frames.size(); remaining++) {
                    FrameMatrices tail = copyFrame(frames.get(remaining));
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

    private SplitResult splitFrameInternal(FrameMatrices frame, Set<String> westCutterTileIds) {
        TileMatrix matrix = firstMatrix(frame);
        if (matrix == null || matrix.getTiles() == null || matrix.getTiles().isEmpty()) {
            return new SplitResult(frame, null);
        }

        Integer splitColumn = null;
        for (TileMatrix.TileCoord tile : matrix.getTiles()) {
            if (tile == null || !westCutterTileIds.contains(tile.getId())) {
                continue;
            }
            splitColumn = tile.getJ();
            break;
        }
        if (splitColumn == null || splitColumn.intValue() <= 0) {
            return new SplitResult(frame, null);
        }

        List<TileMatrix.TileCoord> leftTiles = new ArrayList<>();
        List<TileMatrix.TileCoord> rightTiles = new ArrayList<>();
        int rightMaxJ = Integer.MIN_VALUE;
        int maxI = Integer.MIN_VALUE;
        for (TileMatrix.TileCoord tile : matrix.getTiles()) {
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

        TileMatrix rightMatrix = new TileMatrix();
        rightMatrix.setFrameId(frame.getFrameId());
        rightMatrix.setRows(maxI + 1);
        rightMatrix.setCols(rightMaxJ + 1);
        rightMatrix.setTiles(rightTiles);

        FrameMatrices mainFrame = new FrameMatrices();
        mainFrame.setFrameId(frame.getFrameId());
        mainFrame.setMatrices(List.of(rightMatrix));

        int leftMaxJ = Integer.MIN_VALUE;
        for (TileMatrix.TileCoord tile : leftTiles) {
            leftMaxJ = Math.max(leftMaxJ, tile.getJ());
        }
        TileMatrix leftMatrix = new TileMatrix();
        leftMatrix.setFrameId(-1);
        leftMatrix.setRows(maxI + 1);
        leftMatrix.setCols(leftMaxJ + 1);
        leftMatrix.setTiles(leftTiles);

        FrameMatrices transientFrame = new FrameMatrices();
        transientFrame.setFrameId(-1);
        transientFrame.setMatrices(List.of(leftMatrix));
        return new SplitResult(mainFrame, transientFrame);
    }

    private static TileMatrix.TileCoord copyTile(TileMatrix.TileCoord source, int i, int j) {
        TileMatrix.TileCoord copy = new TileMatrix.TileCoord();
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
            String normalized = WestCutterReader.normalizeScopedTileId(id);
            if (normalized != null) {
                out.add(normalized);
            }
        }
        return out;
    }

    private static List<FrameMatrices> copyFrames(List<FrameMatrices> frames) {
        List<FrameMatrices> out = new ArrayList<>();
        for (FrameMatrices frame : frames) {
            FrameMatrices copy = copyFrame(frame);
            if (copy != null) {
                out.add(copy);
            }
        }
        return out;
    }

    private static FrameMatrices copyFrame(FrameMatrices frame) {
        TileMatrix matrix = firstMatrix(frame);
        if (frame == null || matrix == null) {
            return null;
        }
        TileMatrix matrixCopy = new TileMatrix();
        matrixCopy.setFrameId(matrix.getFrameId());
        matrixCopy.setRows(matrix.getRows());
        matrixCopy.setCols(matrix.getCols());
        List<TileMatrix.TileCoord> tiles = new ArrayList<>();
        for (TileMatrix.TileCoord tile : matrix.getTiles()) {
            if (tile != null) {
                tiles.add(copyTile(tile, tile.getI(), tile.getJ()));
            }
        }
        matrixCopy.setTiles(tiles);

        FrameMatrices out = new FrameMatrices();
        out.setFrameId(frame.getFrameId());
        out.setMatrices(List.of(matrixCopy));
        return out;
    }

    private static TileMatrix firstMatrix(FrameMatrices frame) {
        if (frame == null || frame.getMatrices() == null || frame.getMatrices().isEmpty()) {
            return null;
        }
        return frame.getMatrices().get(0);
    }

    private record SplitResult(FrameMatrices mainFrame, FrameMatrices transientFrame) {
    }

    public record FrameSplitResult(FrameMatrices mainFrame, FrameMatrices transientFrame, boolean changed) {
    }
}
