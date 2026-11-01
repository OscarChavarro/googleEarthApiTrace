package planetdemviewer.geo;

import planetdemviewer.config.Configuration;
import planetdemviewer.spatial.WorldPosition;

/** Adapter for the viewer's present normalized, equirectangular plane. */
public final class PlanarSurfaceModel implements SurfaceModel {
    @Override
    public SurfacePoint locate(GeographicPosition position) {
        GeographicCoordinate coordinate = position.horizontal();
        WorldPosition world = new WorldPosition(coordinate.longitudeDegrees() / 360.0,
            coordinate.latitudeDegrees() / 180.0,
            position.heightMetres() / Configuration.WORLD_WIDTH_METRES);
        return new SurfacePoint(world, new WorldPosition(0.0, 0.0, 1.0));
    }

    @Override
    public SurfaceFrame localFrame(GeographicPosition position) {
        WorldPosition origin = locate(position).worldPosition();
        return new SurfaceFrame(origin, new WorldPosition(1.0, 0.0, 0.0),
            new WorldPosition(0.0, 1.0, 0.0), new WorldPosition(0.0, 0.0, 1.0));
    }
}
