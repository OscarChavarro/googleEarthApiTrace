package pathplanner;

import java.util.List;
import pathplanner.generators.AltitudeGenerator;
import pathplanner.generators.CurveGenerator;
import pathplanner.generators.GlobeGenerator;
import pathplanner.generators.SpiralGenerator;
import pathplanner.generators.ZeroLongitudeSeamGenerator;
import pathplanner.generators.ZigzagGenerator;
import pathplanner.io.KmlPersistence;
import pathplanner.model.Point;
import pathplanner.processing.PointFollower;

public class Main {
    private static final String KML_PATH = System.getProperty("user.home") + "/.googleearth/myplaces.kml";
    private static final String TURTLE_FOLDER_NAME = "turtle";
    private static final String TURTLE_STYLE_ID = "turtleLineStyle";
    private static final String GENERATOR_SPIRAL = "spiral";
    private static final String GENERATOR_ZIGZAG = "zigzag";
    private static final String GENERATOR_GLOBE = "globe";
    private static final double DEFAULT_ALTITUDE_METERS = 0.0;

    public static void main(String[] args) throws Exception {
        if (args.length != 5 && args.length != 6 && args.length != 8) {
            printUsageAndExit();
        }

        String generatorName = args[0];
        if (args.length == 8 && !GENERATOR_ZIGZAG.equalsIgnoreCase(generatorName)) {
            System.err.println("Rectangle spans are only supported by the zigzag generator.");
            printUsageAndExit();
        }
        if (args.length == 6 && GENERATOR_GLOBE.equalsIgnoreCase(generatorName)) {
            System.err.println("Altitude is only supported by spiral and zigzag.");
            printUsageAndExit();
        }

        double lat = Double.parseDouble(args[1]);
        double lon = Double.parseDouble(args[2]);
        double stepMeters = Double.parseDouble(args[3]);
        double maxDistanceMeters = Double.parseDouble(args[4]);
        double altitudeMeters = args.length >= 6 ? Double.parseDouble(args[5]) : DEFAULT_ALTITUDE_METERS;
        Double latSpanDeg = args.length == 8 ? Double.parseDouble(args[6]) : null;
        Double lonSpanDeg = args.length == 8 ? Double.parseDouble(args[7]) : null;
        if (stepMeters <= 0.0) {
            throw new IllegalArgumentException("Step distance must be positive.");
        }
        if (maxDistanceMeters <= 0.0) {
            throw new IllegalArgumentException("Maximum distance must be positive.");
        }
        if (args.length == 8 && (latSpanDeg <= 0.0 || lonSpanDeg <= 0.0)) {
            throw new IllegalArgumentException("Rectangle latitude and longitude spans must be positive.");
        }

        CurveGenerator curveGenerator = buildGenerator(generatorName, altitudeMeters, latSpanDeg, lonSpanDeg);
        PointFollower pointFollower = new PointFollower();
        KmlPersistence kmlPersistence = new KmlPersistence();

        List<Point> curve = curveGenerator.buildTurtleCurve(lat, lon, stepMeters, maxDistanceMeters);
        List<Point> zeroLongitudeSeam = new ZeroLongitudeSeamGenerator().buildTurtleCurve(0.0, 0.0, 0.0, 0.0);
        List<Point> markerPoints = pointFollower.samplePointsOnCurve(curve, stepMeters);
        int altitudeLandmarkCount = 0;
        if (usesAltitudeLandmarks(generatorName) && !curve.isEmpty()) {
            List<Point> altitudeLandmarks = new AltitudeGenerator().buildAltitudeLandmarks(curve.get(0));
            markerPoints.addAll(0, altitudeLandmarks);
            altitudeLandmarkCount = altitudeLandmarks.size();
        }
        kmlPersistence.updateKml(
            KML_PATH,
            TURTLE_FOLDER_NAME,
            TURTLE_STYLE_ID,
            curve,
            markerPoints,
            zeroLongitudeSeam,
            altitudeLandmarkCount
        );

        System.out.println("Generated turtle curve with " + curve.size() + " vertices and " + markerPoints.size() + " z-points in " + KML_PATH);
    }

    private static CurveGenerator buildGenerator(String generatorName, double altitudeMeters, Double latSpanDeg, Double lonSpanDeg) {
        if (GENERATOR_SPIRAL.equalsIgnoreCase(generatorName)) {
            return new SpiralGenerator(altitudeMeters);
        }
        if (GENERATOR_ZIGZAG.equalsIgnoreCase(generatorName)) {
            if (latSpanDeg != null && lonSpanDeg != null) {
                return ZigzagGenerator.forRectangle(altitudeMeters, latSpanDeg, lonSpanDeg);
            }
            return new ZigzagGenerator(altitudeMeters);
        }
        if (GENERATOR_GLOBE.equalsIgnoreCase(generatorName)) {
            return new GlobeGenerator();
        }
        throw new IllegalArgumentException("Unsupported generator: " + generatorName + ". Use: spiral, zigzag or globe");
    }

    private static boolean usesAltitudeLandmarks(String generatorName) {
        return GENERATOR_SPIRAL.equalsIgnoreCase(generatorName) || GENERATOR_ZIGZAG.equalsIgnoreCase(generatorName);
    }

    private static void printUsageAndExit() {
        System.err.println("Usage: gradle run --args=\"<spiral|zigzag> <lat> <lon> <step_distance_m> <max_distance_m> [altitude_m]\"");
        System.err.println("Usage: gradle run --args=\"zigzag <lower_left_lat> <lower_left_lon> <step_distance_m> <max_distance_m> <altitude_m> <lat_span_deg> <lon_span_deg>\"");
        System.err.println("Usage: gradle run --args=\"globe <lat> <lon> <step_distance_m> <max_distance_m>\"");
        System.err.println("Supported generators: spiral, zigzag, globe");
        System.exit(1);
    }
}
