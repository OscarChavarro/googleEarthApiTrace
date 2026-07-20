package pathplanner.generators;

import java.util.ArrayList;
import java.util.List;
import pathplanner.model.Point;
import pathplanner.processing.Geodesy;

public final class SpiralGenerator implements CurveGenerator {
    private final double altitudeMeters;

    public SpiralGenerator() {
        this(0.0);
    }

    public SpiralGenerator(double altitudeMeters) {
        this.altitudeMeters = altitudeMeters;
    }

    @Override
    public List<Point> buildTurtleCurve(double startLat, double startLon, double stepMeters, double maxDistanceFromStartMeters) {
        List<Point> points = new ArrayList<>();
        Point start = new Point(startLat, startLon, altitudeMeters);
        Point current = start;
        points.add(current);

        int lengthUnits = 1;
        int dir = 0;

        while (true) {
            for (int repeat = 0; repeat < 2; repeat++) {
                double legMeters = lengthUnits * stepMeters;
                Point legEnd = withAltitude(Geodesy.moveByMeters(current, dir, legMeters));
                double endDistance = Geodesy.distanceWgs84Meters(start, legEnd);
                if (endDistance <= maxDistanceFromStartMeters) {
                    current = legEnd;
                    points.add(current);
                } else {
                    double allowed = maxDistanceAlongDirection(current, start, dir, legMeters, maxDistanceFromStartMeters);
                    if (allowed > 1e-6) {
                        current = withAltitude(Geodesy.moveByMeters(current, dir, allowed));
                        points.add(current);
                    }
                    return points;
                }
                dir = (dir + 1) % 4;
            }
            lengthUnits++;
        }
    }

    private double maxDistanceAlongDirection(
            Point from,
            Point start,
            int dir,
            double maxLegMeters,
            double maxDistanceFromStartMeters
    ) {
        double low = 0.0;
        double high = maxLegMeters;
        for (int i = 0; i < 60; i++) {
            double mid = (low + high) * 0.5;
            Point probe = Geodesy.moveByMeters(from, dir, mid);
            double probeDistance = Geodesy.distanceWgs84Meters(start, probe);
            if (probeDistance <= maxDistanceFromStartMeters) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private Point withAltitude(Point point) {
        return new Point(point.latDeg(), point.lonDeg(), altitudeMeters);
    }
}
