package pathplanner.generators;

import java.util.ArrayList;
import java.util.List;
import pathplanner.model.Point;
import pathplanner.processing.Geodesy;

public final class GlobeGenerator implements CurveGenerator {
    private static final double WEB_MERCATOR_R = 6378137.0;
    private static final double WORLD_SPAN_METERS = 2.0 * Math.PI * WEB_MERCATOR_R;
    private static final double MIN_LAT_DEG = -85.05112878;
    private static final double MAX_LAT_DEG = 85.05112878;
    private static final double POLAR_MARGIN_MULTIPLIER = 2.0;
    private static final double RETURN_MARGIN_MULTIPLIER = 4.0;
    private static final double EQUATOR_ALTITUDE_MULTIPLIER = 4;
    private static final double POLE_ALTITUDE_MULTIPLIER = 1.0;
    private static final double EQUATOR_EXTRA_WIDTH_FRACTION = 3.0 / 32.0;

    @Override
    public List<Point> buildTurtleCurve(double startLat, double startLon, double stepMeters, double maxDistanceFromStartMeters) {
        List<Point> points = new ArrayList<>();
        double polarMargin = stepMeters * POLAR_MARGIN_MULTIPLIER;
        double returnMargin = stepMeters * RETURN_MARGIN_MULTIPLIER;

        double northLatDeg = mercatorYToLatDeg(polarMargin);
        double southLatDeg = mercatorYToLatDeg(WORLD_SPAN_METERS - polarMargin);
        double rowLatDeg = northLatDeg;
        boolean leftToRight = true;
        for (int row = 0; row < 200000; row++) {
            double rowHalfExtraWidth = extraHalfWidthForLatitude(rowLatDeg);
            double minX = Math.max(0.0, returnMargin - rowHalfExtraWidth);
            double maxX = Math.min(WORLD_SPAN_METERS, WORLD_SPAN_METERS - returnMargin + rowHalfExtraWidth);
            if (minX >= maxX) {
                break;
            }
            double startLonDeg = mercatorXToLonDeg(leftToRight ? minX : maxX);
            double endLonDeg = mercatorXToLonDeg(leftToRight ? maxX : minX);
            double altitudeMeters = altitudeForLatitude(rowLatDeg, stepMeters);

            appendParallel(points, rowLatDeg, startLonDeg, endLonDeg, stepMeters, leftToRight, altitudeMeters);
            leftToRight = !leftToRight;

            if (rowLatDeg <= southLatDeg + 1e-10) {
                break;
            }

            double nextLatDeg = Geodesy.destinationWgs84(rowLatDeg, 0.0, 180.0, 2.0 * stepMeters).latDeg();
            if (nextLatDeg < southLatDeg) {
                rowLatDeg = southLatDeg;
            } else {
                rowLatDeg = nextLatDeg;
            }
        }
        return points;
    }

    private double altitudeForLatitude(double latDeg, double baseAltitudeMeters) {
        double latitudeFactor = Math.pow(Math.abs(Math.sin(Math.toRadians(latDeg))), 0.4);
        double altitudeMultiplier =
                EQUATOR_ALTITUDE_MULTIPLIER
                        - (EQUATOR_ALTITUDE_MULTIPLIER - POLE_ALTITUDE_MULTIPLIER) * latitudeFactor;
        return baseAltitudeMeters * altitudeMultiplier;
    }

    private double extraHalfWidthForLatitude(double latDeg) {
        double latitudeFactor = Math.pow(Math.abs(Math.sin(Math.toRadians(latDeg))), 0.5);
        double equatorWeight = 1.0 - latitudeFactor;
        return WORLD_SPAN_METERS * EQUATOR_EXTRA_WIDTH_FRACTION * equatorWeight * 0.5;
    }

    private void appendParallel(
            List<Point> out,
            double latDeg,
            double startLonDeg,
            double endLonDeg,
            double stepMeters,
            boolean eastbound,
            double altitudeMeters
    ) {
        double currentLon = startLonDeg;
        double targetLon = endLonDeg;
        if (eastbound && targetLon <= currentLon) {
            targetLon += 360.0;
        } else if (!eastbound && targetLon >= currentLon) {
            targetLon -= 360.0;
        }

        Point rowStart = new Point(latDeg, normalizeLonDeg(currentLon), altitudeMeters);
        appendIfDifferent(out, rowStart);

        int guard = 0;
        while (guard++ < 200000) {
            double remainingLon = Math.abs(targetLon - currentLon);
            if (remainingLon <= 1e-12) {
                break;
            }
            double deltaLon = lonDeltaForDistanceMeters(latDeg, currentLon, stepMeters, eastbound);
            if (deltaLon <= 0.0) {
                break;
            }
            double boundedDelta = Math.min(deltaLon, remainingLon);
            currentLon = currentLon + (eastbound ? boundedDelta : -boundedDelta);
            appendIfDifferent(out, new Point(latDeg, normalizeLonDeg(currentLon), altitudeMeters));
        }
        appendIfDifferent(out, new Point(latDeg, normalizeLonDeg(targetLon), altitudeMeters));
    }

    private double parallelDistanceMeters(double latDeg, double lonA, double lonB) {
        Point a = new Point(latDeg, normalizeLonDeg(lonA));
        Point b = new Point(latDeg, normalizeLonDeg(lonB));
        return Geodesy.distanceWgs84Meters(a, b);
    }

    private double lonDeltaForDistanceMeters(double latDeg, double startLonDeg, double distanceMeters, boolean eastbound) {
        double low = 0.0;
        double high = 10.0;
        for (int i = 0; i < 60; i++) {
            double mid = (low + high) * 0.5;
            double probeLon = normalizeLonDeg(startLonDeg + (eastbound ? mid : -mid));
            double probeDistance = parallelDistanceMeters(latDeg, startLonDeg, probeLon);
            if (probeDistance < distanceMeters) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return low;
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

    private double mercatorXToLonDeg(double xMeters) {
        return Math.toDegrees(xMeters / WEB_MERCATOR_R - Math.PI);
    }

    private double mercatorYToLatDeg(double yMeters) {
        double mercatorY = Math.PI - (yMeters / WEB_MERCATOR_R);
        double latRad = 2.0 * Math.atan(Math.exp(mercatorY)) - Math.PI / 2.0;
        double latDeg = Math.toDegrees(latRad);
        if (latDeg < MIN_LAT_DEG) return MIN_LAT_DEG;
        if (latDeg > MAX_LAT_DEG) return MAX_LAT_DEG;
        return latDeg;
    }

    private double normalizeLonDeg(double lonDeg) {
        double out = lonDeg;
        while (out > 180.0) out -= 360.0;
        while (out < -180.0) out += 360.0;
        return out;
    }
}
