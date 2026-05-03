package pathplanner.generators;

import java.util.List;
import pathplanner.model.Point;

public interface CurveGenerator {
    List<Point> buildTurtleCurve(double startLat, double startLon, double stepMeters, double maxDistanceFromStartMeters);
}
