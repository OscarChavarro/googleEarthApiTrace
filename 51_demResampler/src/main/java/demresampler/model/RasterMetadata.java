package demresampler.model;

public record RasterMetadata(
    int width,
    int height,
    double originX,
    double pixelWidth,
    double rotationX,
    double originY,
    double rotationY,
    double pixelHeight,
    double noData,
    boolean hasNoData,
    int gdalDataType
) {
    public double angularResolution() {
        return Math.max(Math.abs(pixelWidth), Math.abs(pixelHeight));
    }
}
