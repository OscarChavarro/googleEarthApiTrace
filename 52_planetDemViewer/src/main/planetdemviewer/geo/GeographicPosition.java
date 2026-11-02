package planetdemviewer.geo;

/** Geographic coordinate plus a height whose datum is explicit at API boundaries. */
public record GeographicPosition(GeographicCoordinate horizontal, double heightMetres) {
    public GeographicPosition {
        if (horizontal == null || !Double.isFinite(heightMetres)) {
            throw new IllegalArgumentException("A geographic position requires finite values");
        }
    }
}

