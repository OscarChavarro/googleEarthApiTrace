package planetdemviewer.spatial;

import planetdemviewer.geo.GeographicBounds;

/**
 * FABDEM's global quadkey convention: 0=south-west, 1=south-east,
 * 2=north-east, and 3=north-west. It is spatial metadata only and makes no
 * assumption about the source's directory layout.
 */
public final class FabdemQuadkeyTileScheme implements TileScheme {
    public static final FabdemQuadkeyTileScheme INSTANCE = new FabdemQuadkeyTileScheme();

    private FabdemQuadkeyTileScheme() {
    }

    @Override
    public String id() {
        return TileAddress.FABDEM_QUADKEY_SCHEME;
    }

    @Override
    public TileAddress root() {
        return TileAddress.fabdem("0");
    }

    @Override
    public GeographicBounds geographicBounds(TileAddress address) {
        if (address == null || !id().equals(address.scheme())) {
            throw new IllegalArgumentException("Address does not belong to the FABDEM quadkey scheme");
        }
        double west = -180.0;
        double east = 180.0;
        double south = -90.0;
        double north = 90.0;
        String quadkey = address.quadkey();
        for (int index = 1; index < quadkey.length(); index++) {
            double middleLongitude = (west + east) * 0.5;
            double middleLatitude = (south + north) * 0.5;
            switch (quadkey.charAt(index)) {
                case '0' -> { east = middleLongitude; north = middleLatitude; }
                case '1' -> { west = middleLongitude; north = middleLatitude; }
                case '2' -> { west = middleLongitude; south = middleLatitude; }
                case '3' -> { east = middleLongitude; south = middleLatitude; }
                default -> throw new IllegalArgumentException("Invalid FABDEM quadkey digit");
            }
        }
        return new GeographicBounds(west, south, east, north);
    }
}
