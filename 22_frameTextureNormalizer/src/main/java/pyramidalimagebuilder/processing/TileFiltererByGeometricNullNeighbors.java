package pyramidalimagebuilder.processing;

import java.util.List;
import pyramidalimagebuilder.model.TileInstance;
import vsdk.toolkit.environment.Camera;

public final class TileFiltererByGeometricNullNeighbors {
    public List<TileInstance> filter(List<TileInstance> tiles, Camera camera) {
        if (tiles == null || tiles.isEmpty()) {
            return List.of();
        }

        return tiles.stream()
            .filter(tile -> tile != null)
            .filter(tile ->
                tile.getSouthNeighbor() != null ||
                tile.getNorthNeighbor() != null ||
                tile.getEastNeighbor() != null ||
                tile.getWestNeighbor() != null
            )
            .toList();
    }
}
