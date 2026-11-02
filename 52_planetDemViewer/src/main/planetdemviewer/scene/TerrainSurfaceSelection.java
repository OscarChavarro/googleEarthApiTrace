package planetdemviewer.scene;

import planetdemviewer.geo.GeographicSurfacePosition;
import planetdemviewer.spatial.TileAddress;
import planetdemviewer.spatial.WorldPosition;

/**
 * A terrain hit with geographic identity and local tile-centred coordinates.
 * geoidX/geoidY are sea-level arc lengths, not distances over terrain relief.
 */
public record TerrainSurfaceSelection(
    GeographicSurfacePosition position,
    WorldPosition worldPosition,
    TileAddress tile,
    double heightAboveMeanSeaLevelMetres,
    double geoidXMetres,
    double geoidYMetres
) {
    public TerrainSurfaceSelection {
        if (position == null || worldPosition == null || tile == null
            || !Double.isFinite(heightAboveMeanSeaLevelMetres)
            || !Double.isFinite(geoidXMetres) || !Double.isFinite(geoidYMetres)) {
            throw new IllegalArgumentException("Invalid terrain surface selection");
        }
    }
}

