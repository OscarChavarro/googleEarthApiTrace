package planetdemviewer.geo;

/** Canonical application datum: height above the WGS84 reference ellipsoid. */
public final class Wgs84EllipsoidalDatum implements VerticalDatum {
    public static final Wgs84EllipsoidalDatum INSTANCE = new Wgs84EllipsoidalDatum();

    private Wgs84EllipsoidalDatum() {
    }

    @Override
    public String id() {
        return "WGS84-ellipsoidal";
    }

    @Override
    public double toEllipsoidalHeight(GeographicCoordinate coordinate, double sourceHeightMetres) {
        return sourceHeightMetres;
    }

    @Override
    public double fromEllipsoidalHeight(GeographicCoordinate coordinate, double ellipsoidalHeightMetres) {
        return ellipsoidalHeightMetres;
    }
}

