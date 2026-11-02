package planetdemviewer.io.elevation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import planetdemviewer.config.Configuration;
import planetdemviewer.geo.GeographicCoordinate;
import org.junit.jupiter.api.Test;

class Egm2008GeoidGridTest {
    @Test
    void loadsTheCommittedGlobalGridAndWrapsLongitude() throws Exception {
        Egm2008GeoidGrid grid = Egm2008GeoidGrid.load(Configuration.EGM2008_GRID);

        double atPrimeMeridian = grid.undulationMetres(new GeographicCoordinate(0.0, 0.0));
        assertTrue(Double.isFinite(atPrimeMeridian));
        assertEquals(atPrimeMeridian,
            grid.undulationMetres(new GeographicCoordinate(360.0, 0.0)), 1e-6);
    }
}
