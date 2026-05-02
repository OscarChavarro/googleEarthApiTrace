package pathplanner;

import java.util.List;

interface CurveGenerator {
    List<Point> buildTurtleCurve(double startLat, double startLon, double stepMeters, double maxDistanceFromStartMeters);
}
