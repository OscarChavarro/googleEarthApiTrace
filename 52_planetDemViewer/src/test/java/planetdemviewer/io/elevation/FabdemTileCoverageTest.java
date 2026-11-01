package planetdemviewer.io.elevation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import planetdemviewer.geo.GeographicCoordinate;
import planetdemviewer.spatial.TileAddress;
import org.junit.jupiter.api.Test;

class FabdemTileCoverageTest {
    @Test
    void decodesTheResamplersNorthUpQuadkeyConvention() {
        FabdemTileCoverage.GeographicBounds bounds = FabdemTileCoverage.bounds(TileAddress.fabdem("020"));

        assertEquals(0.0, bounds.westLongitudeDegrees());
        assertEquals(90.0, bounds.eastLongitudeDegrees());
        assertEquals(90.0, bounds.northLatitudeDegrees());
        assertEquals(0.0, bounds.southLatitudeDegrees());

        GeographicCoordinate firstCoreSample =
            FabdemTileCoverage.storedSampleCenter(TileAddress.fabdem("020"), 1, 1);
        assertEquals(90.0 - 90.0 / 512.0, firstCoreSample.latitudeDegrees(), 1e-12);
        assertEquals(90.0 / 512.0, firstCoreSample.longitudeDegrees(), 1e-12);
    }
}

