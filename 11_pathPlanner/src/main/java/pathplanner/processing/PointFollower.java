package pathplanner.processing;

import java.util.ArrayList;
import java.util.List;
import pathplanner.model.Point;

public final class PointFollower {
    public List<Point> samplePointsOnCurve(List<Point> curvePoints, double maxSpacingMeters) {
        List<Point> out = new ArrayList<>();
        if (curvePoints.isEmpty()) return out;

        out.add(curvePoints.get(0));
        for (int i = 1; i < curvePoints.size(); i++) {
            Point a = curvePoints.get(i - 1);
            Point b = curvePoints.get(i);
            Geodesy.InverseResult inv = Geodesy.inverseWgs84(a, b);
            if (inv.distanceMeters() <= 1e-9) {
                continue;
            }

            int parts = Math.max(1, (int) Math.ceil(inv.distanceMeters() / maxSpacingMeters));
            double partLen = inv.distanceMeters() / parts;
            for (int j = 1; j <= parts; j++) {
                if (j == parts) {
                    out.add(b);
                } else {
                    out.add(Geodesy.destinationWgs84(a.latDeg(), a.lonDeg(), inv.initialBearingDeg(), partLen * j));
                }
            }
        }
        return out;
    }
}
