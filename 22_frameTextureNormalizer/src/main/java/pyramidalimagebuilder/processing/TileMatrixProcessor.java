package pyramidalimagebuilder.processing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import pyramidalimagebuilder.io.MatrixWriter;
import pyramidalimagebuilder.model.FrameData;
import pyramidalimagebuilder.model.TileInstance;
import pyramidalimagebuilder.model.TileMatrix;

public final class TileMatrixProcessor {
    private final TileSetToMatrixConvertor convertor = new TileSetToMatrixConvertor();

    public List<FrameData> convertAndExportTileMatrices(List<FrameData> frames) {
        if (frames == null || frames.isEmpty()) {
            return List.of();
        }

        List<FrameData> out = new ArrayList<>(frames.size());
        for (FrameData frame : frames) {
            if (frame == null) {
                continue;
            }
            TileMatrix matrix = convertor.convert(frame);
            if (matrix == null) {
                frame.setWithMatrixErrors(true);
                System.out.println(
                    "Matrix conversion failed for frame id " + frame.getId() + " (conflicting tile coordinates)"
                );
                out.add(frame);
                continue;
            }
            List<TileInstance> tilesWithCoords = applyMatrixCoordinates(frame.getTiles(), matrix);
            FrameData frameWithMatrix = new FrameData(frame.getId(), tilesWithCoords, frame.getCameraState(), false);
            out.add(frameWithMatrix);
            MatrixWriter.writeMatrixJson(matrix);
        }
        return out;
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
                c == null ? null : c.i(),
                c == null ? null : c.j()
            ));
        }
        return out;
    }
}
