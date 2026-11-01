package planetdemviewer.spatial;

/** Immutable position in the current rendered world coordinate system. */
public record WorldPosition(double x, double y, double z) {
    public WorldPosition {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("World coordinates must be finite");
        }
    }
}

