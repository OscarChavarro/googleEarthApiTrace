package planetviewer.logger;

public final class Logger {
    private static final String PREFIX = "PlanetViewer: ";

    private Logger() {
    }

    public static void info(String message) {
        System.out.println(PREFIX + message);
    }

    public static void error(String message) {
        System.err.println("ERROR: " + message);
    }
}
