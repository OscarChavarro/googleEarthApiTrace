package planetdemviewer.render.terrain;

import planetdemviewer.processing.DrawCommand;
import planetdemviewer.spatial.TileAddress;
import planetdemviewer.spatial.WorldPosition;

/** The closest point where a ray meets the terrain drawn in the last frame; a null imagery tile means no color there. */
public record TerrainPickHit(DrawCommand command, WorldPosition worldPosition, double distanceMetres,
                             double localX, double localY, double ellipsoidalElevationMetres,
                             TileAddress imageryTile) {
}
