package planetdemviewer.geo;

import planetdemviewer.spatial.WorldPosition;

/** World-space position and outward surface normal produced by a SurfaceModel. */
public record SurfacePoint(WorldPosition worldPosition, WorldPosition normal) {
    public SurfacePoint {
        if (worldPosition == null || normal == null) {
            throw new IllegalArgumentException("Surface point and normal are required");
        }
    }
}
