package planetdemviewer.geo;

import planetdemviewer.config.Configuration;
import planetdemviewer.spatial.SpatialPosition;

/**
 * Current planar reference, expressed in metres on a sphere whose circumference
 * equals the viewer's configured world width. It is an explicit CRS adapter,
 * not an implicit assumption by render or terrain code.
 */
public final class EquirectangularSpatialReference implements SpatialReference {
    public static final EquirectangularSpatialReference INSTANCE = new EquirectangularSpatialReference();
    private static final double RADIUS_METRES = Configuration.WORLD_WIDTH_METRES / (2.0 * Math.PI);

    private EquirectangularSpatialReference() {
    }

    @Override
    public String id() {
        return "planetdem-equirectangular-v1";
    }

    @Override
    public SpatialPosition fromGeographic(GeographicPosition geographic) {
        GeographicCoordinate coordinate = geographic.horizontal();
        return new SpatialPosition(Math.toRadians(coordinate.longitudeDegrees()) * RADIUS_METRES,
            Math.toRadians(coordinate.latitudeDegrees()) * RADIUS_METRES,
            geographic.heightMetres());
    }

    @Override
    public GeographicPosition toGeographic(SpatialPosition position) {
        return new GeographicPosition(new GeographicCoordinate(
            Math.toDegrees(position.x() / RADIUS_METRES),
            Math.toDegrees(position.y() / RADIUS_METRES)), position.ellipsoidalHeightMetres());
    }
}
