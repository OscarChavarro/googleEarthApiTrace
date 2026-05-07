package frametexturenormalizer.processing.filtering;

import java.util.List;
import frametexturenormalizer.model.TileInstance;
import vsdk.toolkit.environment.Camera;

public final class TileFiltererByGeometricNullNeighbors {
    public List<TileInstance> filter(List<TileInstance> tiles, Camera camera) {
        if (tiles == null || tiles.isEmpty()) {
            return List.of();
        }

        List<TileInstance> withNeighbors = tiles.stream()
            .filter(tile -> tile != null)
            .filter(tile ->
                tile.getSouthNeighbor() != null ||
                tile.getNorthNeighbor() != null ||
                tile.getEastNeighbor() != null ||
                tile.getWestNeighbor() != null
            )
            .toList();

        if (!withNeighbors.isEmpty()) {
            return withNeighbors;
        }

        // Fallback for traces without neighbor metadata: keep drawable tiles.
        return tiles.stream()
            .filter(tile -> tile != null)
            .filter(tile -> tile.getTriangleStrip() != null)
            .toList();
    }
}
