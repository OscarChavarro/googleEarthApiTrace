package dumpanalyzer.config;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class Configuration {
    public static final Path OUTPUT_ROOT = Paths.get("/tmp/output");
    public static final int MAX_FRAME = 100000;
    public static final long GPU_RAM_TEXTURE_LIMIT = 10L * 1024L * 1024L * 1024L;

    private Configuration() {
    }
}
