package matrixmerger.logger;

public final class AppLogger {
    private AppLogger() {
    }

    public static void info(String message) {
        System.out.println("[matrixMerger] " + message);
    }

    public static void warn(String message) {
        System.out.println("[matrixMerger][WARN] " + message);
    }

    public static void error(String message) {
        System.err.println("[matrixMerger][ERROR] " + message);
    }
}
