package planetdemviewer.geo;

/** Longitude/latitude on the WGS84 reference ellipsoid, expressed in degrees. */
public record GeographicCoordinate(double longitudeDegrees, double latitudeDegrees) {
    public GeographicCoordinate {
        if (!Double.isFinite(longitudeDegrees) || !Double.isFinite(latitudeDegrees)
            || latitudeDegrees < -90.0 || latitudeDegrees > 90.0) {
            throw new IllegalArgumentException("Invalid geographic coordinate");
        }
    }
}

