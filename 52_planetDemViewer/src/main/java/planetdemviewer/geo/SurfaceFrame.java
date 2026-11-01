package planetdemviewer.geo;

import planetdemviewer.spatial.WorldPosition;

/** Right-handed local east/north/up frame, ready for curved-surface meshing. */
public record SurfaceFrame(WorldPosition origin, WorldPosition east, WorldPosition north, WorldPosition up) {
    public SurfaceFrame {
        if (origin == null || east == null || north == null || up == null) {
            throw new IllegalArgumentException("Surface frame axes are required");
        }
    }
}
