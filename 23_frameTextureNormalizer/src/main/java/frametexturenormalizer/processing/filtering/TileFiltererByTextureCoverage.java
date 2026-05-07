package frametexturenormalizer.processing.filtering;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.TileInstance;

public final class TileFiltererByTextureCoverage {
    public FrameData removeNonFullResolutionTiles(FrameData frame) {
        if (frame == null || frame.getTiles() == null || frame.getTiles().isEmpty()) {
            return frame;
        }

        Set<Integer> keptIds = new LinkedHashSet<>();
        List<TileInstance> kept = new ArrayList<>(frame.getTiles().size());
        for (TileInstance tile : frame.getTiles()) {
            if (tile == null || !tile.isFullResolutionWithRespectToTexture()) {
                continue;
            }
            keptIds.add(tile.getTileId());
            kept.add(tile);
        }

        if (kept.size() == frame.getTiles().size()) {
            return frame;
        }

        List<TileInstance> filtered = new ArrayList<>(kept.size());
        for (TileInstance tile : kept) {
            filtered.add(new TileInstance(
                tile.getTileId(),
                tile.getFrameId(),
                tile.getTextureFile(),
                keepIfPresent(tile.getSouthNeighbor(), keptIds),
                keepIfPresent(tile.getNorthNeighbor(), keptIds),
                keepIfPresent(tile.getEastNeighbor(), keptIds),
                keepIfPresent(tile.getWestNeighbor(), keptIds),
                tile.getTriangleStrip(),
                tile.getModelViewMatrix(),
                tile.getMatrixI(),
                tile.getMatrixJ(),
                tile.isIncorrectMatrixMapping(),
                tile.isWestCuttingCell(),
                tile.isSelected()
            ));
        }

        return new FrameData(
            frame.getId(),
            filtered,
            frame.getLines(),
            frame.getCameraState(),
            frame.getProjectionMatrix(),
            frame.getModelViewMatrix(),
            frame.isWithMatrixErrors()
        );
    }

    private static Integer keepIfPresent(Integer neighborId, Set<Integer> keptIds) {
        if (neighborId == null || keptIds == null || !keptIds.contains(neighborId)) {
            return null;
        }
        return neighborId;
    }
}
