package planetdemviewer.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import planetdemviewer.spatial.SpatialPosition;

class EquirectangularSpatialReferenceTest {
    @Test
    void roundTripsGeographicCoordinatesAndEllipsoidalHeight() {
        GeographicPosition expected = new GeographicPosition(new GeographicCoordinate(-74.0817, 4.6097), 2_640.0);
        SpatialPosition projected = EquirectangularSpatialReference.INSTANCE.fromGeographic(expected);
        GeographicPosition actual = EquirectangularSpatialReference.INSTANCE.toGeographic(projected);

        assertEquals(expected.horizontal().longitudeDegrees(), actual.horizontal().longitudeDegrees(), 1e-12);
        assertEquals(expected.horizontal().latitudeDegrees(), actual.horizontal().latitudeDegrees(), 1e-12);
        assertEquals(expected.heightMetres(), actual.heightMetres(), 1e-12);
    }
}
