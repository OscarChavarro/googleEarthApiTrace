package pathplanner;

import java.util.List;
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

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println("Usage: gradle run --args=\"<generator> <lat> <lon> <step_distance_m> <max_distance_m>\"");
            System.err.println("Supported generators: spiral, zigzag, globe");
            System.exit(1);
        }

        String generatorName = args[0];
        double lat = Double.parseDouble(args[1]);
        double lon = Double.parseDouble(args[2]);
        double stepMeters = Double.parseDouble(args[3]);
        double maxDistanceMeters = Double.parseDouble(args[4]);
        if (stepMeters <= 0.0) {
            throw new IllegalArgumentException("Step distance must be positive.");
        }
        if (maxDistanceMeters <= 0.0) {
            throw new IllegalArgumentException("Maximum distance must be positive.");
        }

        CurveGenerator curveGenerator = buildGenerator(generatorName);
        PointFollower pointFollower = new PointFollower();
        KmlPersistence kmlPersistence = new KmlPersistence();

        List<Point> curve = curveGenerator.buildTurtleCurve(lat, lon, stepMeters, maxDistanceMeters);
        List<Point> zeroLongitudeSeam = new ZeroLongitudeSeamGenerator().buildTurtleCurve(0.0, 0.0, 0.0, 0.0);
        List<Point> markerPoints = pointFollower.samplePointsOnCurve(curve, stepMeters);
        kmlPersistence.updateKml(
            KML_PATH,
            TURTLE_FOLDER_NAME,
            TURTLE_STYLE_ID,
            curve,
            markerPoints,
            zeroLongitudeSeam
        );

        System.out.println("Generated turtle curve with " + curve.size() + " vertices and " + markerPoints.size() + " z-points in " + KML_PATH);
    }

    private static CurveGenerator buildGenerator(String generatorName) {
        if (GENERATOR_SPIRAL.equalsIgnoreCase(generatorName)) {
            return new SpiralGenerator();
        }
        if (GENERATOR_ZIGZAG.equalsIgnoreCase(generatorName)) {
            return new ZigzagGenerator();
        }
        if (GENERATOR_GLOBE.equalsIgnoreCase(generatorName)) {
            return new GlobeGenerator();
        }
        throw new IllegalArgumentException("Unsupported generator: " + generatorName + ". Use: spiral, zigzag or globe");
    }
}
