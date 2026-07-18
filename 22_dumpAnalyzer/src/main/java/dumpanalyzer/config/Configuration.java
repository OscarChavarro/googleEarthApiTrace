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

    private Configuration() {
    }

    private static Path loadOutputRoot() {
        Properties properties = loadProperties();
        return Paths.get(properties.getProperty("output.directory", "/media/ramdisk/output"));
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
        Properties properties = loadProperties();
        String rawValue = properties.getProperty(key);
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(rawValue.trim());
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
