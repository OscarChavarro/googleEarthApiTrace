package planetdemviewer.geo;

/**
 * A decimal-degree position constrained to the terrain surface. Height is
 * resolved by terrain intersection and is intentionally not stored here.
 */
public final class GeographicSurfacePosition {
    private final double longitude;
    private final double latitude;

    public GeographicSurfacePosition(double longitude, double latitude) {
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
            || latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("Invalid geographic surface position");
        }
        this.longitude = longitude;
        this.latitude = latitude;
    }

    public double longitude() {
        return longitude;
    }

    public double latitude() {
        return latitude;
    }
}

