package frametexturenormalizer.processing.filtering;

// Java classes
import java.util.List;
import java.util.Objects;

// App classes
import frametexturenormalizer.model.TileInstance;

public final class TileFiltererByGeometricNullNeighbors {
    public List<TileInstance> filter(List<TileInstance> tiles) {
        if (tiles == null || tiles.isEmpty()) {
            return List.of();
        }

        List<TileInstance> withNeighbors = tiles.stream()
            .filter(Objects::nonNull)
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
            .filter(Objects::nonNull)
            .filter(tile -> tile.getTriangleStrip() != null)
            .toList();
    }
}
