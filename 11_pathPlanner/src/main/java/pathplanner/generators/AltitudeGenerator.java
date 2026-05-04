package pathplanner.generators;

import java.util.ArrayList;
import java.util.List;
import pathplanner.model.Point;

public final class AltitudeGenerator {
    private static final double BASE_ALTITUDE_METERS = 100.0;
    private static final int LANDMARK_COUNT = 18;
    private static final int BASE_LANDMARK_INDEX = LANDMARK_COUNT - 1;

    public List<Point> buildAltitudeLandmarks(Point origin) {
        List<Point> landmarks = new ArrayList<>(LANDMARK_COUNT);
        for (int i = 0; i < LANDMARK_COUNT; i++) {
            int levelsAboveBase = BASE_LANDMARK_INDEX - i;
            double altitudeMeters = Math.scalb(BASE_ALTITUDE_METERS, levelsAboveBase);
            landmarks.add(new Point(origin.latDeg(), origin.lonDeg(), altitudeMeters));
        }
        return landmarks;
    }
}
