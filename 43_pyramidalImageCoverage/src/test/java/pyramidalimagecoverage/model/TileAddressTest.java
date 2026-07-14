package pyramidalimagecoverage.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
