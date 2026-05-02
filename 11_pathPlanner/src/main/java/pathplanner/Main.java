package pathplanner;

import java.util.List;

public class Main {
    private static final String KML_PATH = "/home/jedilink/.googleearth/myplaces.kml";
    private static final String TURTLE_FOLDER_NAME = "turtle";
    private static final String TURTLE_STYLE_ID = "turtleLineStyle";
    private static final String GENERATOR_SPIRAL = "spiral";
    private static final String GENERATOR_ZIGZAG = "zigzag";

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println("Uso: gradle run --args=\"<generador> <lat> <lon> <distancia_paso_m> <distancia_maxima_m>\"");
            System.err.println("Generadores soportados: spiral, zigzag");
            System.exit(1);
        }

        String generatorName = args[0];
        double lat = Double.parseDouble(args[1]);
        double lon = Double.parseDouble(args[2]);
        double stepMeters = Double.parseDouble(args[3]);
        double maxDistanceMeters = Double.parseDouble(args[4]);
        if (stepMeters <= 0.0) {
            throw new IllegalArgumentException("La distancia debe ser positiva.");
        }
        if (maxDistanceMeters <= 0.0) {
            throw new IllegalArgumentException("La distancia máxima debe ser positiva.");
        }

        CurveGenerator curveGenerator = buildGenerator(generatorName);
        PointFollower pointFollower = new PointFollower();
        KmzPersistance kmzPersistance = new KmzPersistance();

        List<Point> curve = curveGenerator.buildTurtleCurve(lat, lon, stepMeters, maxDistanceMeters);
        List<Point> markerPoints = pointFollower.samplePointsOnCurve(curve, 2.0 * stepMeters);
        kmzPersistance.updateKml(KML_PATH, TURTLE_FOLDER_NAME, TURTLE_STYLE_ID, curve, markerPoints);

        System.out.println("Curva turtle generada con " + curve.size() + " vértices y " + markerPoints.size() + " puntos z en " + KML_PATH);
    }

    private static CurveGenerator buildGenerator(String generatorName) {
        if (GENERATOR_SPIRAL.equalsIgnoreCase(generatorName)) {
            return new SpiralGenerator();
        }
        if (GENERATOR_ZIGZAG.equalsIgnoreCase(generatorName)) {
            return new ZigzagGenerator();
        }
        throw new IllegalArgumentException("Generador no soportado: " + generatorName + ". Usa: spiral o zigzag");
    }
}
