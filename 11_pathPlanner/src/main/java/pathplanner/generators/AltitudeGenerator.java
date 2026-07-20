package pathplanner.generators;

import java.util.ArrayList;
import java.util.List;
import pathplanner.model.Point;

public final class AltitudeGenerator {
    private static final double FIRST_ALTITUDE_METERS = 16_000_000.0;
    private static final int MAX_ALTITUDE_LEVEL = 16;

    public List<Point> buildAltitudeLandmarks(Point origin, double curveAltitudeMeters) {
        List<Point> landmarks = new ArrayList<>();
        double targetAltitudeMeters = Math.max(0.0, curveAltitudeMeters * 2.0);
        double altitudeMeters = FIRST_ALTITUDE_METERS;
        landmarks.add(new Point(origin.latDeg(), origin.lonDeg(), altitudeMeters));

        for (int level = 1; level <= MAX_ALTITUDE_LEVEL; level++) {
            double nextAltitudeMeters = altitudeMeters / 2.0;
            if (!isCloserToTarget(nextAltitudeMeters, altitudeMeters, targetAltitudeMeters)) {
                break;
            }
            altitudeMeters = nextAltitudeMeters;
            landmarks.add(new Point(origin.latDeg(), origin.lonDeg(), altitudeMeters));
        }
        return landmarks;
    }

    private boolean isCloserToTarget(double candidateAltitudeMeters, double currentAltitudeMeters, double targetAltitudeMeters) {
        return Math.abs(candidateAltitudeMeters - targetAltitudeMeters) <= Math.abs(currentAltitudeMeters - targetAltitudeMeters);
    }
}
