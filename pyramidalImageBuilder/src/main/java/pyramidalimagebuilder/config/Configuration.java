package pyramidalimagebuilder.config;

public final class Configuration {
    public static final String INPUT_PATH = "/tmp/output";
    public static final long MAX_GPU_TEXTURE_MEMORY = 2L * 1024L * 1024L * 1024L;
    public static final double INITIAL_IMAGE_BORDER_THRESHOLD = 30000.0;

    private Configuration() {
    }
}
