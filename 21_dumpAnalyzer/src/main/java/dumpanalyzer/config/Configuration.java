package dumpanalyzer.config;

import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public final class Configuration {
    public static final Path OUTPUT_ROOT = loadOutputRoot();
    public static final int MAX_FRAME = 100000;
    public static final long GPU_RAM_TEXTURE_LIMIT = 10L * 1024L * 1024L * 1024L;
    public static final double FRAME_WRITER_THREADS_CORES_RATIO = 0.50;

    private Configuration() {
    }

    private static Path loadOutputRoot() {
        Properties properties = new Properties();
        try (InputStream input = Configuration.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) {
                properties.load(input);
            }
        }
        catch (Exception e) {
            throw new IllegalStateException("Could not load application.properties", e);
        }
        return Paths.get(properties.getProperty("output.directory", "/tmp/output"));
    }
}
