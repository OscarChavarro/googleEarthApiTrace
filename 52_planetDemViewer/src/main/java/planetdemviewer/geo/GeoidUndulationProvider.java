package planetdemviewer.geo;

/** Supplies geoid undulation N, in metres, above the WGS84 ellipsoid. */
@FunctionalInterface
public interface GeoidUndulationProvider {
    double undulationMetres(GeographicCoordinate coordinate);
}

