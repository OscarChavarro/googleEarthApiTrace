package matrixmerger.config;

public final class Configuration {
    private Configuration() {
    }

    public static final long MAX_GPU_TEXTURE_MEMORY = 1024L * 1024L * 1024L;
    public static final double MAX_TEXTURED_QUAD_DISTANCE = 100.0;
    public static final float FAR_QUAD_SCALE = 0.98f;
}
