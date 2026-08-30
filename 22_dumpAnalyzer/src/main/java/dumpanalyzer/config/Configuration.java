package dumpanalyzer.config;

import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public final class Configuration {
    public static final Path OUTPUT_ROOT = loadOutputRoot();
    public static final long GPU_RAM_TEXTURE_LIMIT = 10L * 1024L * 1024L * 1024L;
    public static final int FRAME_IMPORT_THREADS = loadPositiveInt("frame.import.threads", 8);
    public static final int FRAME_WRITER_THREADS = loadPositiveInt("frame.writer.threads", 2);
    public static final int FRAME_NEIGHBOR_THREADS = loadPositiveInt("frame.neighbor.threads", 8);
    public static final int TOP_LEVEL_TILE_THREADS = loadPositiveInt("topLevelTile.threads", 8);
    public static final boolean VALIDATE_TRACE_SYNTAX = loadBoolean("frame.validateTraceSyntax", false);
    public static final boolean LOCAL_OCR_ENABLED = loadBoolean(
        "localOcr.enabled",
        true,
        "LOCAL_OCR_ENABLED"
    );
    public static final int LOCAL_OCR_THREADS = loadPositiveInt("localOcr.threads", 1);
    public static final String LOCAL_OCR_LANG = loadString("localOcr.lang", "en", "LOCAL_OCR_LANG");
    public static final Path LOCAL_OCR_LIBRARY = loadPath(
        "localOcr.library.path",
        "../24_localOcr/build/liblocalOcr.so",
        "LOCAL_OCR_LIBRARY"
    );

    private Configuration() {
    }

    private static Path loadOutputRoot() {
        Properties properties = loadProperties();
        String environmentValue = System.getenv("PIPELINE_OUTPUT_DIRECTORY");
        return Paths.get(System.getProperty(
            "output.directory",
            environmentValue == null || environmentValue.isBlank()
                ? properties.getProperty("output.directory", "/media/ramdisk/output")
                : environmentValue.trim()
        ));
    }

    private static int loadPositiveInt(String key, int defaultValue) {
        Properties properties = loadProperties();
        String rawValue = properties.getProperty(key);
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(rawValue.trim());
            return value > 0 ? value : defaultValue;
        }
        catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean loadBoolean(String key, boolean defaultValue) {
        return loadBoolean(key, defaultValue, null);
    }

    private static boolean loadBoolean(String key, boolean defaultValue, String environmentVariable) {
        Properties properties = loadProperties();
        String environmentValue = environmentVariable == null ? null : System.getenv(environmentVariable);
        String systemValue = System.getProperty(key);
        String rawValue = environmentValue != null && !environmentValue.isBlank()
            ? environmentValue
            : systemValue != null && !systemValue.isBlank()
                ? systemValue
                : properties.getProperty(key);
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(rawValue.trim());
    }

    private static String loadString(String key, String defaultValue, String environmentVariable) {
        Properties properties = loadProperties();
        String environmentValue = System.getenv(environmentVariable);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue.trim();
        }
        String systemValue = System.getProperty(key);
        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue.trim();
        }
        String rawValue = properties.getProperty(key);
        return rawValue == null || rawValue.isBlank() ? defaultValue : rawValue.trim();
    }

    private static Path loadPath(String key, String defaultValue, String environmentVariable) {
        return Paths.get(loadString(key, defaultValue, environmentVariable));
    }

    private static Properties loadProperties() {
        Properties properties = new Properties();
        try (InputStream input = Configuration.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) {
                properties.load(input);
            }
        }
        catch (Exception e) {
            throw new IllegalStateException("Could not load application.properties", e);
        }
        return properties;
    }
}
