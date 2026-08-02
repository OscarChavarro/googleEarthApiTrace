package pyramidalimagecoverage.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TileDeltaTest {
    @Test
    void calculatesSignedDeltasAndGreatCircleDistanceFromPrimaryToSecondary() {
        TileAddress primary = TileAddress.fromCoordinates(1, 0, 0);
        TileAddress secondary = TileAddress.fromCoordinates(1, 1, 1);

        TileDelta delta = TileDelta.between(primary, secondary);

        assertEquals(90.0, delta.latitudeDegrees());
        assertEquals(180.0, delta.longitudeDegrees());
        assertEquals(20015.114, delta.distanceKilometers(), 0.001);
    }

    @Test
    void preservesNegativeDirectionInCoordinateDeltas() {
        TileAddress primary = TileAddress.fromCoordinates(2, 2, 2);
        TileAddress secondary = TileAddress.fromCoordinates(2, 1, 1);

        TileDelta delta = TileDelta.between(primary, secondary);

        assertEquals(-90.0, delta.latitudeDegrees());
        assertEquals(-90.0, delta.longitudeDegrees());
    }
}
