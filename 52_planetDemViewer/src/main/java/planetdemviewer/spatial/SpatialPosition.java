package planetdemviewer.spatial;

/**
 * Coordinate in the native units of a declared SpatialReference. Its x/y
 * values are never interpreted without that reference; height is canonical
 * WGS84 ellipsoidal metres.
 */
public record SpatialPosition(double x, double y, double ellipsoidalHeightMetres) {
    public SpatialPosition {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(ellipsoidalHeightMetres)) {
            throw new IllegalArgumentException("Spatial coordinates must be finite");
        }
    }
}
