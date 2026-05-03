package pathplanner.generators;

import java.util.ArrayList;
import java.util.List;
import pathplanner.model.Point;
import pathplanner.processing.Geodesy;

public final class ZigzagGenerator implements CurveGenerator {
    private static final double LEG_METERS = 2000.0;
    private static final double SHIFT_EAST_METERS = 200.0;
    private static final int CYCLES = 10;

    @Override
    public List<Point> buildTurtleCurve(double startLat, double startLon, double stepMeters, double maxDistanceFromStartMeters) {
        List<Point> points = new ArrayList<>();
        Point current = new Point(startLat, startLon);
        points.add(current);

        for (int i = 0; i < CYCLES; i++) {
            current = Geodesy.destinationWgs84(current.latDeg(), current.lonDeg(), 0.0, LEG_METERS);
            points.add(current);

            current = Geodesy.destinationWgs84(current.latDeg(), current.lonDeg(), 90.0, SHIFT_EAST_METERS);
            points.add(current);

            current = Geodesy.destinationWgs84(current.latDeg(), current.lonDeg(), 180.0, LEG_METERS);
            points.add(current);

            current = Geodesy.destinationWgs84(current.latDeg(), current.lonDeg(), 90.0, SHIFT_EAST_METERS);
            points.add(current);
        }

        return points;
    }
}
