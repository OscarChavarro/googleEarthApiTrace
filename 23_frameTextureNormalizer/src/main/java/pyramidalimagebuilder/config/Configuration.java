package pyramidalimagebuilder.config;

import java.io.InputStream;
import java.util.Properties;

public final class Configuration {
    public static final String INPUT_PATH = loadOutputDirectory();
    public static final int START_FROM_FRAME = 1;
    public static final long MAX_GPU_TEXTURE_MEMORY = 2L * 1024L * 1024L * 1024L;
    public static final double INITIAL_IMAGE_BORDER_THRESHOLD = 30000.0;

    private Configuration() {
    }

    private static String loadOutputDirectory() {
        Properties properties = new Properties();
        try (InputStream input = Configuration.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) {
                properties.load(input);
            }
        }
        catch (Exception e) {
            throw new IllegalStateException("Could not load application.properties", e);
        }
        return properties.getProperty("output.directory", "/media/ramdisk/output");
    }
}
