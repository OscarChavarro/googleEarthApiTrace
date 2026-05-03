package pathplanner.model;

public record Point(double latDeg, double lonDeg, double altitudeMeters) {
    public Point(double latDeg, double lonDeg) {
        this(latDeg, lonDeg, 0.0);
    }
}
