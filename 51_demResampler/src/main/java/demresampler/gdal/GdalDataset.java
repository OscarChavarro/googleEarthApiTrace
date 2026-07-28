package demresampler.gdal;

import demresampler.model.RasterMetadata;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

public final class GdalDataset implements AutoCloseable {
    static {
        System.loadLibrary("demresampler_gdal");
    }

    private final AtomicLong handle;

    private GdalDataset(long handle) {
        this.handle = new AtomicLong(handle);
    }

    public static GdalDataset open(Path path) throws IOException {
        return new GdalDataset(nativeOpen(path.toAbsolutePath().toString()));
    }

    public RasterMetadata describe() throws IOException {
        double[] values = nativeDescribe(requireOpen());
        return new RasterMetadata(
            (int) values[0],
            (int) values[1],
            values[2],
            values[3],
            values[4],
            values[5],
            values[6],
            values[7],
            values[8],
            values[9] != 0.0,
            (int) values[10]
        );
    }

    public boolean isWgs84Geographic() {
        return nativeIsWgs84Geographic(requireOpen());
    }

    public float[] read(double west, double north, double east, double south, int width, int height)
        throws IOException {
        return nativeRead(requireOpen(), west, north, east, south, width, height);
    }

    @Override
    public void close() {
        long value = handle.getAndSet(0);
        if (value != 0) {
            nativeClose(value);
        }
    }

    private long requireOpen() {
        long value = handle.get();
        if (value == 0) {
            throw new IllegalStateException("GDAL dataset is already closed");
        }
        return value;
    }

    private static native long nativeOpen(String path) throws IOException;

    private static native void nativeClose(long handle);

    private static native double[] nativeDescribe(long handle) throws IOException;

    private static native boolean nativeIsWgs84Geographic(long handle);

    private static native float[] nativeRead(
        long handle,
        double west,
        double north,
        double east,
        double south,
        int width,
        int height
    ) throws IOException;
}
