package pyramidalimageexporter.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class Configuration {
    private Configuration() {
    }

    public static final long MAX_GPU_TEXTURE_MEMORY = 1024L * 1024L * 1024L;
    public static final double MAX_TEXTURED_QUAD_DISTANCE = 100.0;
    public static final float FAR_QUAD_SCALE = 0.98f;
    public static final String DEFAULT_OUTPUT_DIRECTORY = "/media/ramdisk/output";
    public static final int DEFAULT_CAPTURE_BOUNDARY_LEVEL = -1;

    public static String outputDirectory() {
        String environmentValue = System.getenv("PIPELINE_OUTPUT_DIRECTORY");
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue.trim();
        }
        String value = loadProperties().getProperty("output.directory");
        if (value == null || value.isBlank()) {
            return DEFAULT_OUTPUT_DIRECTORY;
        }
        return value.trim();
    }

    /**
     * Absolute quadtree level of the deepest matrix still anchored by the
     * session's shallow capture pass, or -1 if this session was captured in
     * a single pass (no correction applies). Uncle relationships whose child
     * is deeper than this level but whose reference tile is at or above it
     * cross into a separate, later capture pass with different camera
     * zigzag parameters and carry a constant one-row-south placement error;
     * see the level12-anchoring-pipeline project memory for how this was
     * root-caused and validated.
     */
    public static int captureBoundaryLevel() {
        String value = loadProperties().getProperty("capture.boundary.level");
        if (value == null || value.isBlank()) {
            return DEFAULT_CAPTURE_BOUNDARY_LEVEL;
        }
        try {
            return Integer.parseInt(value.trim());
        }
        catch (NumberFormatException ex) {
            return DEFAULT_CAPTURE_BOUNDARY_LEVEL;
        }
    }

    private static Properties loadProperties() {
        Properties properties = new Properties();
        try (InputStream in = Configuration.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (in != null) {
                properties.load(in);
            }
        }
        catch (IOException ex) {
            return properties;
        }
        return properties;
    }
}
