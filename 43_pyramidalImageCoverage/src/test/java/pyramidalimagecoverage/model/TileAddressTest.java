package pyramidalimagecoverage.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TileAddressTest {
    @Test
    void mapsQuadrantsUsingSouthOriginCoordinates() {
        assertEquals(new TileAddress("00", 1, 0, 0), TileAddress.fromQuadKey("00"));
        assertEquals(new TileAddress("01", 1, 1, 0), TileAddress.fromQuadKey("01"));
        assertEquals(new TileAddress("02", 1, 1, 1), TileAddress.fromQuadKey("02"));
        assertEquals(new TileAddress("03", 1, 0, 1), TileAddress.fromQuadKey("03"));
        assertEquals(new TileAddress("002", 2, 1, 1), TileAddress.fromQuadKey("002"));
    }

    @Test
    void estimatesLowerLeftGeographicCoordinate() {
        TileAddress root = TileAddress.fromQuadKey("0");
        assertEquals(-180.0, root.lowerLeftLongitude());
        assertEquals(-90.0, root.lowerLeftLatitude());

        TileAddress northEast = TileAddress.fromQuadKey("02");
        assertEquals(0.0, northEast.lowerLeftLongitude());
        assertEquals(0.0, northEast.lowerLeftLatitude());

        TileAddress tile = TileAddress.fromQuadKey("002");
        assertEquals(-90.0, tile.lowerLeftLongitude());
        assertEquals(-90.0, tile.lowerLeftLatitude());
    }

    @Test
    void createsAddressFromMatrixCoordinatesAndCalculatesItsCenter() {
        TileAddress address = TileAddress.fromCoordinates(1, 1, 1);

        assertEquals("02", address.quadKey());
        assertEquals(45.0, address.centerLatitude());
        assertEquals(90.0, address.centerLongitude());
    }

    @Test
    void clipsSquareQuadtreeTilesToValidEarthLatitudes() {
        TileAddress root = TileAddress.fromCoordinates(0, 0, 0);
        TileAddress levelOneSouth = TileAddress.fromCoordinates(1, 0, 0);
        TileAddress levelOneNorth = TileAddress.fromCoordinates(1, 0, 1);
        TileAddress levelTwoOutsideSouth = TileAddress.fromCoordinates(2, 0, 0);
        TileAddress levelTwoSouth = TileAddress.fromCoordinates(2, 0, 1);
        TileAddress levelTwoNorth = TileAddress.fromCoordinates(2, 0, 2);
        TileAddress levelTwoOutsideNorth = TileAddress.fromCoordinates(2, 0, 3);

        assertEquals(-90.0, root.lowerLeftLatitude());
        assertEquals(180.0, root.latitudeSpan());
        assertEquals(-45.0, levelOneSouth.centerLatitude());
        assertEquals(45.0, levelOneNorth.centerLatitude());
        assertEquals(-45.0, levelTwoSouth.centerLatitude());
        assertEquals(45.0, levelTwoNorth.centerLatitude());
        assertFalse(levelTwoOutsideSouth.hasGeographicCoverage());
        assertFalse(levelTwoOutsideNorth.hasGeographicCoverage());
        assertTrue(levelTwoSouth.hasGeographicCoverage());
        assertTrue(levelTwoNorth.hasGeographicCoverage());
    }
}
