package planetdemviewer.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import planetdemviewer.config.Configuration;

class PlanarSurfaceModelTest {
    @Test
    void mapsTheCurrentNormalizedPlaneWithoutChangingItsCoordinates() {
        SurfacePoint point = new PlanarSurfaceModel().locate(
            new GeographicPosition(new GeographicCoordinate(90.0, -45.0), Configuration.WORLD_WIDTH_METRES));

        assertEquals(0.25, point.worldPosition().x(), 1e-12);
        assertEquals(-0.25, point.worldPosition().y(), 1e-12);
        assertEquals(1.0, point.worldPosition().z(), 1e-12);
        assertEquals(1.0, point.normal().z(), 1e-12);
    }
}
