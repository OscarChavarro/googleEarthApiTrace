package pathplanner.generators;

import java.util.ArrayList;
import java.util.List;
import pathplanner.model.Point;
import pathplanner.processing.Geodesy;

public final class ZigzagGenerator implements CurveGenerator {
    private static final double LEG_METERS = 2000.0;
    private static final double SHIFT_EAST_METERS = 200.0;
    private static final int CYCLES = 10;
    private final double altitudeMeters;
    private final Double latSpanDeg;
    private final Double lonSpanDeg;

    public ZigzagGenerator() {
        this(0.0);
    }

    public ZigzagGenerator(double altitudeMeters) {
        this(altitudeMeters, null, null);
    }

    private ZigzagGenerator(double altitudeMeters, Double latSpanDeg, Double lonSpanDeg) {
        this.altitudeMeters = altitudeMeters;
        this.latSpanDeg = latSpanDeg;
        this.lonSpanDeg = lonSpanDeg;
    }

    public static ZigzagGenerator forRectangle(double altitudeMeters, double latSpanDeg, double lonSpanDeg) {
        return new ZigzagGenerator(altitudeMeters, latSpanDeg, lonSpanDeg);
    }

    @Override
    public List<Point> buildTurtleCurve(double startLat, double startLon, double stepMeters, double maxDistanceFromStartMeters) {
        if (latSpanDeg != null && lonSpanDeg != null) {
            return buildRectangleZigzag(startLat, startLon, stepMeters);
        }

        List<Point> points = new ArrayList<>();
        Point current = new Point(startLat, startLon, altitudeMeters);
        points.add(current);

        for (int i = 0; i < CYCLES; i++) {
            current = move(current, 0.0, LEG_METERS);
            points.add(current);

            current = move(current, 90.0, SHIFT_EAST_METERS);
            points.add(current);

            current = move(current, 180.0, LEG_METERS);
            points.add(current);

            current = move(current, 90.0, SHIFT_EAST_METERS);
            points.add(current);
        }

        return points;
    }

    private List<Point> buildRectangleZigzag(double lowerLeftLat, double lowerLeftLon, double northStepMeters) {
        List<Point> points = new ArrayList<>();
        double upperLat = lowerLeftLat + latSpanDeg;
        double rightLon = lowerLeftLon + lonSpanDeg;
        double currentLat = lowerLeftLat;
        boolean leftToRight = true;

        while (currentLat <= upperLat + 1e-12) {
            if (leftToRight) {
                appendIfDifferent(points, new Point(currentLat, lowerLeftLon, altitudeMeters));
                appendIfDifferent(points, new Point(currentLat, rightLon, altitudeMeters));
            } else {
                appendIfDifferent(points, new Point(currentLat, rightLon, altitudeMeters));
                appendIfDifferent(points, new Point(currentLat, lowerLeftLon, altitudeMeters));
            }

            Point nextRow = Geodesy.destinationWgs84(currentLat, lowerLeftLon, 0.0, northStepMeters);
            double nextLat = nextRow.latDeg();
            if (nextLat >= upperLat - 1e-12) {
                if (currentLat < upperLat - 1e-12) {
                    currentLat = upperLat;
                    leftToRight = !leftToRight;
                    continue;
                }
                break;
            }
            currentLat = nextLat;
            leftToRight = !leftToRight;
        }

        return points;
    }

    private Point move(Point from, double azimuthDeg, double distanceMeters) {
        Point moved = Geodesy.destinationWgs84(from.latDeg(), from.lonDeg(), azimuthDeg, distanceMeters);
        return new Point(moved.latDeg(), moved.lonDeg(), altitudeMeters);
    }

    private void appendIfDifferent(List<Point> points, Point candidate) {
        if (points.isEmpty()) {
            points.add(candidate);
            return;
        }
        Point last = points.get(points.size() - 1);
        if (Math.abs(last.latDeg() - candidate.latDeg()) > 1e-12 || Math.abs(last.lonDeg() - candidate.lonDeg()) > 1e-12) {
            points.add(candidate);
        }
    }
}
