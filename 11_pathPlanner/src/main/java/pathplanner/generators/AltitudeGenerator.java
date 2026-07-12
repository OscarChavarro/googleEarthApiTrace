package pathplanner.generators;

import java.util.ArrayList;
import java.util.List;
import pathplanner.model.Point;

public final class AltitudeGenerator {
    private static final double FIRST_ALTITUDE_METERS = 8_600_000.0;
    private static final double FINAL_ALTITUDE_METERS = 100.0;

    public List<Point> buildAltitudeLandmarks(Point origin) {
        List<Point> landmarks = new ArrayList<>();
        double altitudeMeters = FIRST_ALTITUDE_METERS;
        landmarks.add(new Point(origin.latDeg(), origin.lonDeg(), altitudeMeters));
        while (altitudeMeters > FINAL_ALTITUDE_METERS) {
            altitudeMeters /= 2.0;
            landmarks.add(new Point(origin.latDeg(), origin.lonDeg(), altitudeMeters));
        }
        return landmarks;
    }
}
