package planetdemviewer.spatial;

/** A pixel position in a viewport with top-left origin. */
public record ViewportPoint(double u, double v) {
    public ViewportPoint {
        if (!Double.isFinite(u) || !Double.isFinite(v)) {
            throw new IllegalArgumentException("Viewport coordinates must be finite");
        }
    }
}

