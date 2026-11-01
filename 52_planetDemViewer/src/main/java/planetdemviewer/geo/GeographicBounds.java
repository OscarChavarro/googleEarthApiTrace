package planetdemviewer.geo;

/** Geographic rectangular coverage in decimal degrees, without antimeridian wrapping. */
public record GeographicBounds(double westDegrees, double southDegrees,
                               double eastDegrees, double northDegrees) {
    public GeographicBounds {
        if (!Double.isFinite(westDegrees) || !Double.isFinite(southDegrees)
            || !Double.isFinite(eastDegrees) || !Double.isFinite(northDegrees)
            || westDegrees < -180.0 || eastDegrees > 180.0
            || southDegrees < -90.0 || northDegrees > 90.0
            || westDegrees >= eastDegrees || southDegrees >= northDegrees) {
            throw new IllegalArgumentException("Invalid non-wrapping geographic bounds");
        }
    }

    public GeographicCoordinate center() {
        return new GeographicCoordinate((westDegrees + eastDegrees) * 0.5,
            (southDegrees + northDegrees) * 0.5);
    }
}
