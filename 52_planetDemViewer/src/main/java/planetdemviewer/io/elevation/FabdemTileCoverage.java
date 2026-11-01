package planetdemviewer.io.elevation;

import planetdemviewer.geo.GeographicCoordinate;
import planetdemviewer.model.DemTile;
import planetdemviewer.spatial.TileAddress;

/**
 * Geographic coverage of the square equirectangular FABDEM pyramid produced
 * by demResampler. The nominal root spans [-180, 180] in both axes; valid
 * FABDEM samples occupy latitude [-90, 90].
 */
public final class FabdemTileCoverage {
    private FabdemTileCoverage() {
    }

    public static GeographicBounds bounds(TileAddress address) {
        if (!TileAddress.FABDEM_QUADKEY_SCHEME.equals(address.scheme())) {
            throw new IllegalArgumentException("Not a FABDEM quadkey address: " + address);
        }
        int row = 0;
        int column = 0;
        for (int index = 1; index < address.quadkey().length(); index++) {
            row <<= 1;
            column <<= 1;
            switch (address.quadkey().charAt(index)) {
                case '0' -> row++;
                case '1' -> { row++; column++; }
                case '2' -> column++;
                case '3' -> { }
                default -> throw new IllegalArgumentException("Invalid FABDEM quadkey");
            }
        }
        double span = 360.0 / (1L << address.level());
        double west = -180.0 + column * span;
        double north = 180.0 - row * span;
        return new GeographicBounds(west, west + span, north, north - span);
    }

    /**
     * Geographic center of a stored sample. Rows/columns 1..256 are the core;
     * halo coordinates naturally extend half a sample beyond a tile edge.
     */
    public static GeographicCoordinate storedSampleCenter(TileAddress address, int storedRow, int storedColumn) {
        if (storedRow < 0 || storedRow >= DemTile.STORED_SIZE
            || storedColumn < 0 || storedColumn >= DemTile.STORED_SIZE) {
            throw new IndexOutOfBoundsException("Stored coordinate outside DEM tile");
        }
        GeographicBounds bounds = bounds(address);
        double step = bounds.longitudeSpanDegrees() / DemTile.CORE_SIZE;
        double longitude = bounds.westLongitudeDegrees() + (storedColumn - 0.5) * step;
        double latitude = bounds.northLatitudeDegrees() - (storedRow - 0.5) * step;
        return new GeographicCoordinate(longitude, latitude);
    }

    public record GeographicBounds(
        double westLongitudeDegrees,
        double eastLongitudeDegrees,
        double northLatitudeDegrees,
        double southLatitudeDegrees
    ) {
        public double longitudeSpanDegrees() {
            return eastLongitudeDegrees - westLongitudeDegrees;
        }
    }
}

