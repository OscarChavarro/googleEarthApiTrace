package planetdemviewer.logger;

public final class Logger {
    private static final String PREFIX = "PlanetDemViewer: ";

    private Logger() {
    }

    public static void info(String message) {
        System.out.println(PREFIX + message);
    }

    public static void error(String message) {
        System.err.println("ERROR: " + message);
    }
}
