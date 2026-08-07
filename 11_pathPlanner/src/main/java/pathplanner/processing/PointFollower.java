package pathplanner.processing;

import java.util.ArrayList;
import java.util.List;
import pathplanner.model.Point;

public final class PointFollower {
    private static final double TURN_MARKER_THRESHOLD_FRACTION = 0.1;
    private static final double RIGHT_ANGLE_TOLERANCE_DEG = 15.0;

    public List<Point> samplePointsOnCurve(List<Point> curvePoints, double maxSpacingMeters) {
        List<Point> out = new ArrayList<>();
        if (curvePoints.isEmpty()) return out;

        Point first = curvePoints.get(0);
        out.add(first);
        double spacingToNext = maxSpacingMeters;
        double distanceSinceLastMarker = 0.0;

        for (int i = 1; i < curvePoints.size(); i++) {
            Point a = curvePoints.get(i - 1);
            Point b = curvePoints.get(i);
            Geodesy.InverseResult inv = Geodesy.inverseWgs84(a, b);
            double segmentLength = inv.distanceMeters();
            if (segmentLength <= 1e-9) {
                continue;
            }

            double traversedOnSegment = 0.0;
            while (traversedOnSegment < segmentLength - 1e-9) {
                double remainingToNextMarker = spacingToNext - distanceSinceLastMarker;
                double remainingOnSegment = segmentLength - traversedOnSegment;
                double step = Math.min(remainingToNextMarker, remainingOnSegment);
                traversedOnSegment += step;
                distanceSinceLastMarker += step;

                if (distanceSinceLastMarker + 1e-9 >= spacingToNext) {
                    double t = traversedOnSegment / segmentLength;
                    Point sampled = Geodesy.destinationWgs84(a.latDeg(), a.lonDeg(), inv.initialBearingDeg(), traversedOnSegment);
                    double altitudeMeters = a.altitudeMeters() + (b.altitudeMeters() - a.altitudeMeters()) * t;
                    Point marker = new Point(sampled.latDeg(), sampled.lonDeg(), altitudeMeters);
                    appendIfDifferent(out, marker);
                    spacingToNext = maxSpacingMeters;
                    distanceSinceLastMarker = 0.0;
                }
            }

            if (isRightAngleTurn(curvePoints, i)
                    && distanceSinceLastMarker > maxSpacingMeters * TURN_MARKER_THRESHOLD_FRACTION) {
                appendIfDifferent(out, b);
                spacingToNext = maxSpacingMeters;
                distanceSinceLastMarker = 0.0;
            }
        }
        appendIfDifferent(out, curvePoints.get(curvePoints.size() - 1));
        return out;
    }

    private boolean isRightAngleTurn(List<Point> curvePoints, int vertexIndex) {
        if (vertexIndex <= 0 || vertexIndex >= curvePoints.size() - 1) {
            return false;
        }

        Point previous = curvePoints.get(vertexIndex - 1);
        Point vertex = curvePoints.get(vertexIndex);
        Point next = curvePoints.get(vertexIndex + 1);
        double incomingBearing = Geodesy.inverseWgs84(previous, vertex).initialBearingDeg();
        double outgoingBearing = Geodesy.inverseWgs84(vertex, next).initialBearingDeg();
        double turnAngle = smallestAngleDifferenceDeg(incomingBearing, outgoingBearing);
        return Math.abs(turnAngle - 90.0) <= RIGHT_ANGLE_TOLERANCE_DEG;
    }

    private double smallestAngleDifferenceDeg(double aDeg, double bDeg) {
        double diff = Math.abs(aDeg - bDeg) % 360.0;
        return diff > 180.0 ? 360.0 - diff : diff;
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
