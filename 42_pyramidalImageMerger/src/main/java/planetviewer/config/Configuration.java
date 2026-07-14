package planetviewer.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class Configuration {
    private Configuration() {
    }

    public static final long MAX_GPU_TEXTURE_MEMORY = 512L * 1024L * 1024L;
    public static final long MAX_RAM_TILE_CACHE_BYTES = 1536L * 1024L * 1024L;
    // Fraction of the viewport a tile quad must project to before its
    // children are drawn instead of the tile itself (ported from the old
    // prototype's drawCuadtreeNode area > 0.8 test).
    public static final double SCREEN_AREA_SUBDIVISION_THRESHOLD = 0.8;
    public static final String DEFAULT_DATASET_DIRECTORY = "/samples/datasets/googleEarth";

    public static String defaultDatasetDirectory() {
        Properties properties = new Properties();
        try (InputStream in = Configuration.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (in != null) {
                properties.load(in);
            }
        }
        catch (IOException ex) {
            return DEFAULT_DATASET_DIRECTORY;
        }
        String value = properties.getProperty("default.dataset.directory");
        if (value == null || value.isBlank()) {
            return DEFAULT_DATASET_DIRECTORY;
        }
        return value.trim();
    }
}
