package pyramidalimageexporter.logger;

public final class AppLogger {
    private AppLogger() {
    }

    public static void info(String message) {
        System.out.println("[pyramidalImageExporter] " + message);
    }

    public static void warn(String message) {
        System.out.println("[pyramidalImageExporter][WARN] " + message);
    }

    public static void error(String message) {
        System.err.println("[pyramidalImageExporter][ERROR] " + message);
    }
}
