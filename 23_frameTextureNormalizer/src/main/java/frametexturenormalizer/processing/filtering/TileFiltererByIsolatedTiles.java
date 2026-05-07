package frametexturenormalizer.processing.filtering;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.TileInstance;

public final class TileFiltererByIsolatedTiles {
    public FrameData removeIsolatedTiles(FrameData frame) {
        if (frame == null || frame.getTiles() == null || frame.getTiles().isEmpty()) {
            return frame;
        }

        List<TileInstance> current = new ArrayList<>(frame.getTiles());
        boolean changed = false;
        while (true) {
            Set<Integer> presentIds = new LinkedHashSet<>();
            for (TileInstance tile : current) {
                if (tile != null) {
                    presentIds.add(tile.getTileId());
                }
            }

            Set<Integer> keptIds = new LinkedHashSet<>();
            for (TileInstance tile : current) {
                if (tile != null && hasAnyNeighbor(tile, presentIds)) {
                    keptIds.add(tile.getTileId());
                }
            }

            if (keptIds.size() == current.size()) {
                break;
            }
            changed = true;

            List<TileInstance> next = new ArrayList<>(keptIds.size());
            for (TileInstance tile : current) {
                if (tile == null || !keptIds.contains(tile.getTileId())) {
                    continue;
                }
                next.add(new TileInstance(
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
            current = next;
            if (current.isEmpty()) {
                break;
            }
        }

        if (!changed) {
            return frame;
        }

        return new FrameData(
            frame.getId(),
            current,
            frame.getLines(),
            frame.getCameraState(),
            frame.getProjectionMatrix(),
            frame.getModelViewMatrix(),
            frame.isWithMatrixErrors()
        );
    }

    private static boolean hasAnyNeighbor(TileInstance tile, Set<Integer> presentIds) {
        return keepIfPresent(tile.getSouthNeighbor(), presentIds) != null
            || keepIfPresent(tile.getNorthNeighbor(), presentIds) != null
            || keepIfPresent(tile.getEastNeighbor(), presentIds) != null
            || keepIfPresent(tile.getWestNeighbor(), presentIds) != null;
    }

    private static Integer keepIfPresent(Integer neighborId, Set<Integer> keptIds) {
        if (neighborId == null || keptIds == null || !keptIds.contains(neighborId)) {
            return null;
        }
        return neighborId;
    }
}
