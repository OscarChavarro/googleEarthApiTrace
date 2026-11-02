package planetdemviewer.geo;

/**
 * Converts source heights to the application's canonical WGS84 ellipsoidal
 * height and back. Implementations must document their source datum.
 */
public interface VerticalDatum {
    String id();

    double toEllipsoidalHeight(GeographicCoordinate coordinate, double sourceHeightMetres);

    double fromEllipsoidalHeight(GeographicCoordinate coordinate, double ellipsoidalHeightMetres);
}

