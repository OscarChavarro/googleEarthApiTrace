package planetdemviewer.scene;

import planetdemviewer.config.Configuration;
import planetdemviewer.geo.GeographicSurfacePosition;
import planetdemviewer.spatial.TileAddress;
import planetdemviewer.spatial.WorldPosition;

/**
 * A terrain hit with geographic identity and local tile-centred coordinates.
 * geoidX/geoidY are sea-level arc lengths, not distances over terrain relief.
 * The tile is that of the elevations, and imageryTile the one whose color was showing at that
 * point when it was picked, which can be at another level, or null when there was no color.
 */
public record TerrainSurfaceSelection(
    GeographicSurfacePosition position,
    WorldPosition worldPosition,
    TileAddress tile,
    TileAddress imageryTile,
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

    /** Computes geoidX and geoidY from the position and the center of the tile. */
    public static TerrainSurfaceSelection inTile(GeographicSurfacePosition position, WorldPosition worldPosition,
                                                 TileAddress tile, TileAddress imageryTile,
                                                 double heightAboveMeanSeaLevelMetres) {
        double centerLongitude = (tile.x0() + tile.size() * 0.5) * 360.0 - 180.0;
        double centerLatitude = (tile.y0() + tile.size() * 0.5) * 180.0 - 90.0;
        double radius = Configuration.WORLD_WIDTH_METRES / (2.0 * Math.PI);
        double geoidX = Math.toRadians(position.longitude() - centerLongitude) * radius
            * Math.cos(Math.toRadians(centerLatitude));
        double geoidY = Math.toRadians(position.latitude() - centerLatitude) * radius;
        return new TerrainSurfaceSelection(position, worldPosition, tile, imageryTile,
            heightAboveMeanSeaLevelMetres, geoidX, geoidY);
    }

    public TerrainSurfaceSelection withWorldScaledBy(double factor) {
        return new TerrainSurfaceSelection(position, new WorldPosition(worldPosition.x() * factor,
            worldPosition.y() * factor, worldPosition.z() * factor), tile, imageryTile,
            heightAboveMeanSeaLevelMetres, geoidXMetres, geoidYMetres);
    }
}
