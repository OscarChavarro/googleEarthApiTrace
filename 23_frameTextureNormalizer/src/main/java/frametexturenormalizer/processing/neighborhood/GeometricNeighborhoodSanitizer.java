package frametexturenormalizer.processing.neighborhood;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.TileInstance;
import frametexturenormalizer.model.TileMatrix;
import frametexturenormalizer.processing.matrix.TileSetToMatrixConverter;

public final class GeometricNeighborhoodSanitizer {
    public FrameData sanitizeFrame(FrameData frame) {
        if (frame == null || frame.getTiles() == null || frame.getTiles().isEmpty()) {
            return frame;
        }
        return tightenNeighborhoodsByMatrixLayout(frame);
    }

    private static FrameData tightenNeighborhoodsByMatrixLayout(FrameData frame) {
        TileSetToMatrixConverter converter = new TileSetToMatrixConverter();
        TileMatrix matrix = converter.convert(frame);
        if (matrix == null || matrix.getTiles() == null || matrix.getTiles().isEmpty()) {
            return frame;
        }

        Map<Integer, TileInstance> byId = new HashMap<>();
        for (TileInstance tile : frame.getTiles()) {
            if (tile != null) {
                byId.put(tile.getTileId(), tile);
            }
        }
        if (byId.isEmpty()) {
            return frame;
        }

        Map<Integer, NeighborSet> tightenedByTileId = new HashMap<>();
        Map<String, TileMatrix.TileCoord> byPos = new HashMap<>();
        for (TileMatrix.TileCoord tile : matrix.getTiles()) {
            if (tile != null) {
                byPos.put(key(tile.i(), tile.j()), tile);
            }
        }
        for (TileMatrix.TileCoord tile : matrix.getTiles()) {
            if (tile == null) {
                continue;
            }
            Integer north = tileIdAt(byPos, tile.i() - 1, tile.j());
            Integer south = tileIdAt(byPos, tile.i() + 1, tile.j());
            Integer east = tileIdAt(byPos, tile.i(), tile.j() + 1);
            Integer west = tileIdAt(byPos, tile.i(), tile.j() - 1);
            tightenedByTileId.put(tile.tileId(), new NeighborSet(south, north, east, west));
        }

        if (tightenedByTileId.isEmpty()) {
            return frame;
        }

        List<TileInstance> tightenedTiles = new ArrayList<>(frame.getTiles().size());
        for (TileInstance tile : frame.getTiles()) {
            if (tile == null) {
                continue;
            }
            NeighborSet tightened = tightenedByTileId.get(tile.getTileId());
            if (tightened == null) {
                tightenedTiles.add(tile);
                continue;
            }
            Integer south = chooseNeighbor(tile.getSouthNeighbor(), tightened.south());
            Integer north = chooseNeighbor(tile.getNorthNeighbor(), tightened.north());
            Integer east = chooseNeighbor(tile.getEastNeighbor(), tightened.east());
            Integer west = tile.isWestCuttingCell() ? null : chooseNeighbor(tile.getWestNeighbor(), tightened.west());
            tightenedTiles.add(copyTileWithNeighbors(tile, south, north, east, west));
        }
        return rebuildFrame(frame, tightenedTiles);
    }

    private static FrameData rebuildFrame(FrameData originalFrame, List<TileInstance> sanitizedTiles) {
        return new FrameData(
            originalFrame.getId(),
            sanitizedTiles,
            originalFrame.getLines(),
            originalFrame.getCameraState(),
            originalFrame.getProjectionMatrix(),
            originalFrame.getModelViewMatrix(),
            originalFrame.isWithMatrixErrors()
        );
    }

    private static TileInstance copyTileWithNeighbors(
        TileInstance tile,
        Integer south,
        Integer north,
        Integer east,
        Integer west
    ) {
        return new TileInstance(
            tile.getTileId(),
            tile.getFrameId(),
            tile.getTextureFile(),
            south,
            north,
            east,
            west,
            tile.getTriangleStrip(),
            tile.getModelViewMatrix(),
            tile.getMatrixI(),
            tile.getMatrixJ(),
            tile.isIncorrectMatrixMapping(),
            tile.isWestCuttingCell(),
            tile.isSelected()
        );
    }

    private static Integer tileIdAt(Map<String, TileMatrix.TileCoord> byPos, int i, int j) {
        TileMatrix.TileCoord tile = byPos.get(key(i, j));
        return tile == null ? null : tile.tileId();
    }

    private static Integer chooseNeighbor(Integer preferred, Integer fallback) {
        return preferred != null ? preferred : fallback;
    }

    private static String key(int i, int j) {
        return i + ":" + j;
    }

    private record NeighborSet(Integer south, Integer north, Integer east, Integer west) {
    }
}
