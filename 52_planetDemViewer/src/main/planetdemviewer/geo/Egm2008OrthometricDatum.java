package planetdemviewer.geo;

import java.util.Objects;

/**
 * EGM2008 orthometric heights.  h = H + N converts FABDEM's source height H
 * to the canonical WGS84 ellipsoidal height h.
 */
public final class Egm2008OrthometricDatum implements VerticalDatum {
    private final GeoidUndulationProvider undulation;

    public Egm2008OrthometricDatum(GeoidUndulationProvider undulation) {
        this.undulation = Objects.requireNonNull(undulation, "undulation");
    }

    @Override
    public String id() {
        return "EGM2008-orthometric";
    }

    @Override
    public double toEllipsoidalHeight(GeographicCoordinate coordinate, double sourceHeightMetres) {
        return sourceHeightMetres + undulation.undulationMetres(coordinate);
    }

    @Override
    public double fromEllipsoidalHeight(GeographicCoordinate coordinate, double ellipsoidalHeightMetres) {
        return ellipsoidalHeightMetres - undulation.undulationMetres(coordinate);
    }
}

