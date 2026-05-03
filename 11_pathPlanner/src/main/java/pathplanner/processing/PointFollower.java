package pathplanner.processing;

import java.util.ArrayList;
import java.util.List;
import pathplanner.model.Point;

public final class PointFollower {
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
        }
        appendIfDifferent(out, curvePoints.get(curvePoints.size() - 1));
        return out;
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
