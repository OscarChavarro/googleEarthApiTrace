package frametexturenormalizer.processing.matrix;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.TileInstance;
import frametexturenormalizer.model.TileMatrix;

public final class TileMatrixProcessor {
    private final TileSetToMatrixConverter convertor = new TileSetToMatrixConverter();

    public TileMatrixProcessingResult convertTileMatrices(List<FrameData> frames) {
        if (frames == null || frames.isEmpty()) {
            return new TileMatrixProcessingResult(List.of(), List.of());
        }

        List<FrameData> out = new ArrayList<>(frames.size());
        List<TileMatrix> matrices = new ArrayList<>(frames.size());
        for (FrameData frame : frames) {
            if (frame == null) {
                continue;
            }
            TileMatrix matrix = convertor.convert(frame);
            if (matrix == null) {
                frame.setWithMatrixErrors(true);
                Set<Integer> conflictIds = convertor.getLastConflictingTileIds();
                Map<Integer, MatrixTileCoordinate> partialCoords = convertor.getLastCoordinatesByTileId();
                List<TileInstance> flaggedTiles = markIncorrectMatrixMappings(frame.getTiles(), conflictIds, partialCoords);
                out.add(new FrameData(
                    frame.getId(),
                    flaggedTiles,
                    frame.getLines(),
                    frame.getCameraState(),
                    frame.getProjectionMatrix(),
                    frame.getModelViewMatrix(),
                    true
                ));
                continue;
            }
            List<TileInstance> tilesWithCoords = applyMatrixCoordinates(frame.getTiles(), matrix);
            FrameData frameWithMatrix = new FrameData(
                frame.getId(),
                tilesWithCoords,
                frame.getLines(),
                frame.getCameraState(),
                frame.getProjectionMatrix(),
                frame.getModelViewMatrix(),
                false
            );
            out.add(frameWithMatrix);
            matrices.add(matrix);
        }
        return new TileMatrixProcessingResult(out, matrices);
    }

    public List<TileInstance> applyMatrixCoordinates(List<TileInstance> tiles, TileMatrix matrix) {
        if (tiles == null || tiles.isEmpty() || matrix == null || matrix.getTiles() == null) {
            return List.of();
        }

        Map<Integer, TileMatrix.TileCoord> byId = new HashMap<>();
        for (TileMatrix.TileCoord c : matrix.getTiles()) {
            if (c != null) {
                byId.put(c.tileId(), c);
            }
        }

        List<TileInstance> out = new ArrayList<>(tiles.size());
        for (TileInstance tile : tiles) {
            if (tile == null) {
                continue;
            }
            TileMatrix.TileCoord c = byId.get(tile.getTileId());
            out.add(new TileInstance(
                tile.getTileId(),
                tile.getFrameId(),
                tile.getTextureFile(),
                tile.getSouthNeighbor(),
                tile.getNorthNeighbor(),
                tile.getEastNeighbor(),
                tile.getWestNeighbor(),
                tile.getTriangleStrip(),
                tile.getModelViewMatrix(),
                c == null ? null : c.i(),
                c == null ? null : c.j(),
                tile.isIncorrectMatrixMapping()
            ));
        }
        return out;
    }

    private List<TileInstance> markIncorrectMatrixMappings(
        List<TileInstance> tiles,
        Set<Integer> conflictIds,
        Map<Integer, MatrixTileCoordinate> partialCoords
    ) {
        if (tiles == null || tiles.isEmpty()) {
            return List.of();
        }
        List<TileInstance> out = new ArrayList<>(tiles.size());
        for (TileInstance tile : tiles) {
            if (tile == null) {
                continue;
            }
            boolean incorrect = conflictIds != null && conflictIds.contains(tile.getTileId());
            MatrixTileCoordinate coord = partialCoords == null ? null : partialCoords.get(tile.getTileId());
            out.add(new TileInstance(
                tile.getTileId(),
                tile.getFrameId(),
                tile.getTextureFile(),
                tile.getSouthNeighbor(),
                tile.getNorthNeighbor(),
                tile.getEastNeighbor(),
                tile.getWestNeighbor(),
                tile.getTriangleStrip(),
                tile.getModelViewMatrix(),
                coord == null ? tile.getMatrixI() : coord.i(),
                coord == null ? tile.getMatrixJ() : coord.j(),
                incorrect
            ));
        }
        return out;
    }
}
