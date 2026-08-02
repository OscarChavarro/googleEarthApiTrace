package pyramidalimagecoverage.model;

public record TileDelta(double latitudeDegrees, double longitudeDegrees, double distanceKilometers) {
    private static final double EARTH_MEAN_RADIUS_KILOMETERS = 6371.0088;

    public static TileDelta between(TileAddress primary, TileAddress secondary) {
        if (primary == null || secondary == null) {
            throw new IllegalArgumentException("Both tile addresses are required");
        }
        double primaryLatitude = primary.centerLatitude();
        double primaryLongitude = primary.centerLongitude();
        double secondaryLatitude = secondary.centerLatitude();
        double secondaryLongitude = secondary.centerLongitude();
        double deltaLatitude = secondaryLatitude - primaryLatitude;
        double deltaLongitude = secondaryLongitude - primaryLongitude;

        double latitudeRadians = Math.toRadians(deltaLatitude);
        double longitudeRadians = Math.toRadians(deltaLongitude);
        double primaryLatitudeRadians = Math.toRadians(primaryLatitude);
        double secondaryLatitudeRadians = Math.toRadians(secondaryLatitude);
        double haversine = square(Math.sin(latitudeRadians / 2.0))
            + Math.cos(primaryLatitudeRadians) * Math.cos(secondaryLatitudeRadians)
            * square(Math.sin(longitudeRadians / 2.0));
        double centralAngle = 2.0 * Math.atan2(
            Math.sqrt(haversine),
            Math.sqrt(Math.max(0.0, 1.0 - haversine))
        );
        return new TileDelta(
            deltaLatitude,
            deltaLongitude,
            EARTH_MEAN_RADIUS_KILOMETERS * centralAngle
        );
    }

    private static double square(double value) {
        return value * value;
    }
}
