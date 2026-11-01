package planetdemviewer.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import planetdemviewer.geo.GeographicBounds;

class FabdemQuadkeyTileSchemeTest {
    private final FabdemQuadkeyTileScheme scheme = FabdemQuadkeyTileScheme.INSTANCE;

    @Test
    void rootCoversTheEntireGlobe() {
        GeographicBounds bounds = scheme.geographicBounds(scheme.root());

        assertEquals(-180.0, bounds.westDegrees());
        assertEquals(-90.0, bounds.southDegrees());
        assertEquals(180.0, bounds.eastDegrees());
        assertEquals(90.0, bounds.northDegrees());
    }

    @Test
    void preservesFabdemSouthWestAndNorthEastQuadrantConvention() {
        GeographicBounds southWest = scheme.geographicBounds(TileAddress.fabdem("00"));
        GeographicBounds northEast = scheme.geographicBounds(TileAddress.fabdem("02"));

        assertEquals(-180.0, southWest.westDegrees());
        assertEquals(0.0, southWest.eastDegrees());
        assertEquals(-90.0, southWest.southDegrees());
        assertEquals(0.0, southWest.northDegrees());
        assertEquals(0.0, northEast.westDegrees());
        assertEquals(180.0, northEast.eastDegrees());
        assertEquals(0.0, northEast.southDegrees());
        assertEquals(90.0, northEast.northDegrees());
    }
}
