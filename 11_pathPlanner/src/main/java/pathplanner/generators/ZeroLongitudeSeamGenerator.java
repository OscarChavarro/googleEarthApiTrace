package pathplanner.generators;

import java.util.ArrayList;
import java.util.List;
import pathplanner.model.Point;

public final class ZeroLongitudeSeamGenerator implements CurveGenerator {
    private static final double FIXED_LONGITUDE_DEGREES = 0.0;

    @Override
    public List<Point> buildTurtleCurve(double startLat, double startLon, double stepMeters, double maxDistanceFromStartMeters) {
        List<Point> seam = new ArrayList<>(181);
        for (int lat = 90; lat >= -90; lat--) {
            seam.add(new Point(lat, FIXED_LONGITUDE_DEGREES, 0.0));
        }
        return seam;
    }
}
