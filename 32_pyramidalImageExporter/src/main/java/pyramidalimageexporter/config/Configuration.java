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

    public static String outputDirectory() {
        Properties properties = new Properties();
        try (InputStream in = Configuration.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (in != null) {
                properties.load(in);
            }
        }
        catch (IOException ex) {
            return DEFAULT_OUTPUT_DIRECTORY;
        }
        String value = properties.getProperty("output.directory");
        if (value == null || value.isBlank()) {
            return DEFAULT_OUTPUT_DIRECTORY;
        }
        return value.trim();
    }
}
