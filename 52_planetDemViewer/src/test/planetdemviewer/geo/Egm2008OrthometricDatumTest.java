package planetdemviewer.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class Egm2008OrthometricDatumTest {
    @Test
    void convertsFabdemOrthometricHeightToWgs84EllipsoidalHeight() {
        GeographicCoordinate coordinate = new GeographicCoordinate(-74.0817, 4.6097);
        Egm2008OrthometricDatum datum = new Egm2008OrthometricDatum(ignored -> 27.25);

        assertEquals(2_627.25, datum.toEllipsoidalHeight(coordinate, 2_600.0), 1e-12);
        assertEquals(2_600.0, datum.fromEllipsoidalHeight(coordinate, 2_627.25), 1e-12);
    }
}

