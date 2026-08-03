package planetdemviewer.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;

public final class Configuration {
    private Configuration() {
    }

    public static final long MAX_GPU_TEXTURE_MEMORY = 512L * 1024L * 1024L;
    public static final double DEFAULT_RAM_TILE_CACHE_HEAP_FRACTION = 0.80;
    public static final long MINIMUM_RAM_TILE_CACHE_BYTES = 1536L * 1024L * 1024L;
    // Fraction of the viewport a tile quad must project to before its
    // children are drawn instead of the tile itself (ported from the old
    // prototype's drawCuadtreeNode area > 0.8 test).
    public static final double SCREEN_AREA_SUBDIVISION_THRESHOLD = 0.8;
    public static final String DEFAULT_DATASET_DIRECTORY = "/media/extra/FABDEM/02_rawPyramidal";
    public static final Path PALETTE_DIRECTORY = Path.of("../etc/palettes");
    public static final int MINIMUM_ELEVATION_METRES = 0;
    public static final int MAXIMUM_ELEVATION_METRES = 12_000;

    /**
     * Keeps most of a large JVM heap available for DEM data while preserving
     * headroom for JOGL, object graphs and transient colour images. An exact
     * byte budget can be supplied with PLANET_DEM_RAM_CACHE_BYTES.
     */
    public static long maxRamTileCacheBytes() {
        long heapLimit = Runtime.getRuntime().maxMemory();
        long safeLimit = (long) (heapLimit * DEFAULT_RAM_TILE_CACHE_HEAP_FRACTION);
        String configured = System.getenv("PLANET_DEM_RAM_CACHE_BYTES");
        if (configured != null && !configured.isBlank()) {
            try {
                long requested = Long.parseLong(configured.trim());
                return Math.max(1L, Math.min(requested, safeLimit));
            }
            catch (NumberFormatException ignored) {
                // Fall through to the heap-derived budget.
            }
        }
        return Math.max(1L, safeLimit);
    }

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
