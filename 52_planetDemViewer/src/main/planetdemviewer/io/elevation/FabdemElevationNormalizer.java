package planetdemviewer.io.elevation;

import java.nio.file.Path;
import java.util.Objects;
import planetdemviewer.geo.Egm2008OrthometricDatum;
import planetdemviewer.geo.GeographicCoordinate;
import planetdemviewer.model.DemTile;
import planetdemviewer.spatial.TileAddress;

/** Normalizes FABDEM EGM2008 orthometric samples to WGS84 ellipsoidal metres. */
public final class FabdemElevationNormalizer {
    private final Egm2008OrthometricDatum sourceDatum;

    public FabdemElevationNormalizer(Egm2008GeoidGrid grid) {
        this.sourceDatum = new Egm2008OrthometricDatum(Objects.requireNonNull(grid, "grid"));
    }

    public static FabdemElevationNormalizer load(Path gridFile) throws java.io.IOException {
        return new FabdemElevationNormalizer(Egm2008GeoidGrid.load(gridFile));
    }

    public DemTile.ElevationNormalizer normalizerFor(TileAddress address) {
        Objects.requireNonNull(address, "address");
        return (storedRow, storedColumn, sourceMetres) -> {
            if (sourceMetres == DemTile.NO_DATA) {
                return Float.NaN;
            }
            GeographicCoordinate coordinate = FabdemTileCoverage.storedSampleCenter(
                address, storedRow, storedColumn);
            return (float) sourceDatum.toEllipsoidalHeight(coordinate, sourceMetres);
        };
    }

    /** Converts canonical WGS84 ellipsoidal height h back to EGM2008 H (MSL). */
    public double orthometricHeight(GeographicCoordinate coordinate, double ellipsoidalHeightMetres) {
        return sourceDatum.fromEllipsoidalHeight(coordinate, ellipsoidalHeightMetres);
    }
}

