package pathplanner.generators;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import pathplanner.model.Point;

class AltitudeGeneratorTest {
    @Test
    void skipsL0AndL1AndStartsAtL2() {
        AltitudeGenerator generator = new AltitudeGenerator();

        List<Point> landmarks = generator.buildAltitudeLandmarks(new Point(39.0, -9.0, 0.0), 0.0);

        assertEquals(15, landmarks.size());
        assertEquals(4_000_000.0, landmarks.get(0).altitudeMeters());
        assertEquals(2_000_000.0, landmarks.get(1).altitudeMeters());
    }
}
